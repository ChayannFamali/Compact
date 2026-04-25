package io.compact.registry.codec

import io.circe.syntax.*
import io.compact.core.*
import io.compact.registry.codec.ContractCodecs.given

class ContractMigrationSpec extends munit.FunSuite:

  private def sampleContract: Contract =
    Contract.create(
      id     = ContractId("order-placed"),
      name   = ContractName("Order Placed"),
      fields = List(
        Field("id",     FieldType.Uuid,  required = true),
        Field("amount", FieldType.Int64, required = true),
      ),
      owner = OwnerId("order-service"),
    )

  //  encode / decode roundtrip 

  test("encode → decode roundtrip"):
    val contract = sampleContract
    val json     = ContractMigration.encode(contract)
    val decoded  = ContractMigration.decode(json)
    assertEquals(decoded, Right(contract))

  test("encode производит валидный JSON"):
    val json = ContractMigration.encode(sampleContract)
    assert(io.circe.parser.parse(json).isRight, "encode должен давать валидный JSON")

  test("encode содержит formatVersion"):
    val json   = ContractMigration.encode(sampleContract)
    val parsed = io.circe.parser.parse(json).getOrElse(fail("invalid json"))
    assertEquals(
      parsed.hcursor.downField("formatVersion").as[Int],
      Right(ContractEnvelope.CurrentFormatVersion),
    )

  //  decode — обработка ошибок 

  test("decode некорректного JSON → Left с описанием"):
    val result = ContractMigration.decode("not json at all {{{")
    assert(result.isLeft)
    assert(result.left.exists(_.contains("JSON")))

  test("decode JSON без formatVersion → Left"):
    val jsonWithoutVersion = """{"contract": {}}"""
    val result = ContractMigration.decode(jsonWithoutVersion)
    assert(result.isLeft)
    assert(result.left.exists(_.contains("formatVersion")))

  test("decode JSON с неизвестным полем не падает"):
    // Лишние поля игнорируются — важно для forward compatibility
    val contract = sampleContract
    val json     = ContractMigration.encode(contract)

    // Добавляем неизвестное поле
    val withExtra = io.circe.parser.parse(json)
      .getOrElse(fail("invalid json"))
      .deepMerge(io.circe.Json.obj("unknownFutureField" -> "value".asJson))

    val result = ContractMigration.decode(withExtra.spaces2)
    assertEquals(result, Right(contract))

  //  migrate — версии формата 

  test("migrate версии == current → возвращает оригинальный JSON"):
    val json   = ContractEnvelope.wrap(sampleContract).asJson
    val result = ContractMigration.migrate(json, ContractEnvelope.CurrentFormatVersion)
    assertEquals(result, Right(json))

  test("migrate версии > current → Left с предложением обновить"):
    val futureVersion = ContractEnvelope.CurrentFormatVersion + 1
    val json          = io.circe.Json.obj("formatVersion" -> futureVersion.asJson)
    val result        = ContractMigration.migrate(json, futureVersion)
    assert(result.isLeft)
    assert(result.left.exists(_.contains("обнови библиотеку compact") ||
                              result.left.exists(_.toLowerCase.contains("обнови"))))

  test("migrate неизвестной старой версии → Left"):
    val json   = io.circe.Json.obj("formatVersion" -> 0.asJson)
    val result = ContractMigration.migrate(json, 0)
    assert(result.isLeft)

  //  Сложные контракты 

  test("encode/decode контракт с вложенными типами"):
    val contract = Contract.create(
      id     = ContractId("complex-event"),
      name   = ContractName("Complex Event"),
      fields = List(
        Field("id",      FieldType.Uuid,                                        required = true),
        Field("tags",    FieldType.Collection(FieldType.Str),                   required = false),
        Field("meta",    FieldType.Mapping(FieldType.Str, FieldType.Str),       required = false),
        Field("ref",     FieldType.Nested(ContractId("other-contract")),        required = false), 
        Field("payload", FieldType.Union(List(FieldType.Str, FieldType.Int32)), required = true),
        Field("matrix",  FieldType.Collection(FieldType.Collection(FieldType.Int32)), required = false),
      ),
      owner       = OwnerId("test-service"),
      description = Some("Контракт с составными типами"),
      tags        = List("test", "complex"),
    )
    assertEquals(ContractMigration.decode(ContractMigration.encode(contract)), Right(contract))


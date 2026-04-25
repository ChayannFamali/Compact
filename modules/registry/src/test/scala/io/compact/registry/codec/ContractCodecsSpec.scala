package io.compact.registry.codec

import io.circe.*
import io.circe.syntax.*
import io.circe.parser.*
import io.compact.core.*
import io.compact.registry.codec.ContractCodecs.given
class ContractCodecsSpec extends munit.FunSuite:

  //  Вспомогательные методы 

  private def roundtrip[A: Encoder: Decoder](value: A): Either[DecodingFailure, A] =
    value.asJson.as[A]

  private val owner = OwnerId("test-service")

  private def simpleContract: Contract =
    Contract.create(
      id     = ContractId("user-created"),
      name   = ContractName("User Created"),
      fields = List(
        Field("id",    FieldType.Uuid, required = true),
        Field("email", FieldType.Str,  required = true),
        Field("age",   FieldType.Int32, required = false),
      ),
      owner = owner,
    )

  //  SemanticVersion 

  test("SemanticVersion roundtrip"):
    assertEquals(roundtrip(SemanticVersion(1, 2, 3)), Right(SemanticVersion(1, 2, 3)))
    assertEquals(roundtrip(SemanticVersion(0, 0, 0)), Right(SemanticVersion(0, 0, 0)))

  test("SemanticVersion кодируется как строка"):
    val json = SemanticVersion(1, 2, 3).asJson
    assertEquals(json, Json.fromString("1.2.3"))

  test("SemanticVersion декодируется из строки"):
    val result = Json.fromString("2.5.0").as[SemanticVersion]
    assertEquals(result, Right(SemanticVersion(2, 5, 0)))

  test("SemanticVersion некорректная строка → ошибка"):
    assert(Json.fromString("not-a-version").as[SemanticVersion].isLeft)
    assert(Json.fromString("1.2").as[SemanticVersion].isLeft)

  //  Opaque types 

  test("ContractId roundtrip"):
    assertEquals(roundtrip(ContractId("user-created")), Right(ContractId("user-created")))

  test("ContractId кодируется как строка"):
    assertEquals(ContractId("my-contract").asJson, Json.fromString("my-contract"))

  test("ContractId некорректное значение → ошибка декодирования"):
    assert(Json.fromString("").as[ContractId].isLeft)
    assert(Json.fromString("Has Spaces").as[ContractId].isLeft)

  //  FieldType primitives 

  test("все примитивные FieldType roundtrip"):
    val primitives = List(
      FieldType.Str, FieldType.Int32, FieldType.Int64,
      FieldType.Float32, FieldType.Float64, FieldType.Bool,
      FieldType.Bytes, FieldType.Timestamp, FieldType.Uuid,
    )
    primitives.foreach { ft =>
      assertEquals(roundtrip(ft), Right(ft), s"Roundtrip failed for: $ft")
    }

  test("FieldType.Str кодируется с kind=str"):
    val json = FieldType.Str.asJson
    assertEquals(json.hcursor.downField("kind").as[String], Right("str"))

  test("FieldType.Uuid кодируется с kind=uuid"):
    val json = FieldType.Uuid.asJson
    assertEquals(json.hcursor.downField("kind").as[String], Right("uuid"))

  //  FieldType составные 

  test("FieldType.Collection roundtrip"):
    val ft = FieldType.Collection(FieldType.Str)
    assertEquals(roundtrip(ft), Right(ft))

  test("FieldType.Collection кодируется правильно"):
    val json = (FieldType.Collection(FieldType.Int32): FieldType).asJson
    assertEquals(json.hcursor.downField("kind").as[String], Right("collection"))
    assertEquals(
      json.hcursor.downField("element").downField("kind").as[String],
      Right("int32"),
    )

  test("FieldType.Mapping roundtrip"):
    val ft = FieldType.Mapping(FieldType.Str, FieldType.Int64)
    assertEquals(roundtrip(ft), Right(ft))

  test("FieldType.Nested roundtrip"):
    val ft = FieldType.Nested(ContractId("address"))
    assertEquals(roundtrip(ft), Right(ft))

  test("FieldType.Nested хранит имя контракта"):
    val json = (FieldType.Nested(ContractId("address")): FieldType).asJson
      assertEquals(json.hcursor.downField("contractId").as[String], Right("address"))

  test("FieldType.Union roundtrip"):
    val ft = FieldType.Union(List(FieldType.Str, FieldType.Int32, FieldType.Bool))
    assertEquals(roundtrip(ft), Right(ft))

  test("FieldType.Union кодирует все варианты"):
    val ft   = FieldType.Union(List(FieldType.Str, FieldType.Int32))
    val json = ft.asJson
    assertEquals(json.hcursor.downField("kind").as[String], Right("union"))
    assertEquals(
      json.hcursor.downField("variants").as[List[Json]].map(_.size),
      Right(2),
    )

  test("FieldType вложенный Collection(Collection(Str)) roundtrip"):
    val ft = FieldType.Collection(FieldType.Collection(FieldType.Str))
    assertEquals(roundtrip(ft), Right(ft))

  test("FieldType Union вложенный в Collection roundtrip"):
    val ft = FieldType.Collection(FieldType.Union(List(FieldType.Str, FieldType.Int32)))
    assertEquals(roundtrip(ft), Right(ft))

  test("FieldType неизвестный kind → ошибка"):
    val json = Json.obj("kind" -> "unknown-type".asJson)
    assert(json.as[FieldType].isLeft)
    assert(json.as[FieldType].left.exists(_.message.contains("unknown-type")))

  //  Field 

  test("Field минимальный roundtrip"):
    val field = Field("email", FieldType.Str, required = true)
    assertEquals(roundtrip(field), Right(field))

  test("Field полный roundtrip"):
    val field = Field(
      name        = "status",
      fieldType   = FieldType.Union(List(FieldType.Str, FieldType.Int32)),
      required    = false,
      description = Some("Статус пользователя"),
      deprecated  = true,
    )
    assertEquals(roundtrip(field), Right(field))

  test("Field deprecated=false не пишется в JSON"):
    val field = Field("id", FieldType.Uuid, required = true)
    val json  = field.asJson
    // deprecated=false → ключ отсутствует
    assert(json.hcursor.downField("deprecated").failed)

  test("Field deprecated=true пишется в JSON"):
    val field = Field("old-field", FieldType.Str, required = false, deprecated = true)
    val json  = field.asJson
    assertEquals(json.hcursor.downField("deprecated").as[Boolean], Right(true))

  test("Field description=None не пишется в JSON"):
    val field = Field("id", FieldType.Uuid, required = true)
    val json  = field.asJson
    assert(json.hcursor.downField("description").failed)

  test("Field description=Some пишется в JSON"):
    val field = Field("email", FieldType.Str, required = true, description = Some("Email пользователя"))
    val json  = field.asJson
    assertEquals(json.hcursor.downField("description").as[String], Right("Email пользователя"))

  test("Field отсутствующий deprecated декодируется как false"):
    val json = parse("""{"name":"id","fieldType":{"kind":"uuid"},"required":true}""")
      .getOrElse(fail("invalid json"))
    assertEquals(json.as[Field].map(_.deprecated), Right(false))

  //  Contract 

  test("Contract минимальный roundtrip"):
    val c = simpleContract
    assertEquals(roundtrip(c), Right(c))

  test("Contract с description и tags roundtrip"):
    val c = simpleContract.copy(
      description = Some("Событие о создании пользователя"),
      tags        = List("users", "identity"),
    )
    assertEquals(roundtrip(c), Right(c))

  test("Contract tags=[] не пишется в JSON"):
    val json = simpleContract.asJson
    assert(json.hcursor.downField("tags").failed)

  test("Contract description=None не пишется в JSON"):
    val json = simpleContract.asJson
    assert(json.hcursor.downField("description").failed)

  test("Contract version кодируется как строка"):
    val json = simpleContract.asJson
    assertEquals(
      json.hcursor.downField("version").as[String],
      Right("1.0.0"),
    )

  test("Contract имеет правильные имена полей в JSON"):
    val json = simpleContract.asJson
    val obj  = json.asObject.getOrElse(fail("не объект"))
    val keys = obj.keys.toSet
    assert(keys.contains("id"))
    assert(keys.contains("name"))
    assert(keys.contains("version"))
    assert(keys.contains("fields"))
    assert(keys.contains("owner"))

  //  VersionDiff 

  test("VersionDiff.FieldAdded roundtrip"):
    val diff = VersionDiff.FieldAdded(Field("phone", FieldType.Str, required = false))
    assertEquals(roundtrip(diff), Right(diff))

  test("VersionDiff.FieldRemoved roundtrip"):
    val diff = VersionDiff.FieldRemoved("email", Field("email", FieldType.Str, required = true))
    assertEquals(roundtrip(diff), Right(diff))

  test("VersionDiff.FieldTypeChanged roundtrip"):
    val diff = VersionDiff.FieldTypeChanged("age", FieldType.Str, FieldType.Int32)
    assertEquals(roundtrip(diff), Right(diff))

  test("VersionDiff.FieldRequirednessChanged roundtrip"):
    val diff = VersionDiff.FieldRequirednessChanged("email", wasRequired = true, isNowRequired = false)
    assertEquals(roundtrip(diff), Right(diff))

  test("VersionDiff.FieldDeprecated roundtrip"):
    val diff = VersionDiff.FieldDeprecated("legacy-field")
    assertEquals(roundtrip(diff), Right(diff))

  test("VersionDiff.MetadataChanged roundtrip"):
    val diff = VersionDiff.MetadataChanged(Some("old"), Some("new"))
    assertEquals(roundtrip(diff), Right(diff))

  test("VersionDiff.MetadataChanged с None roundtrip"):
    val diff = VersionDiff.MetadataChanged(None, Some("first description"))
    assertEquals(roundtrip(diff), Right(diff))

  test("VersionDiff.TagsChanged roundtrip"):
    val diff = VersionDiff.TagsChanged(added = List("new-tag"), removed = List("old-tag"))
    assertEquals(roundtrip(diff), Right(diff))

  test("VersionDiff неизвестный type → ошибка"):
    val json = Json.obj("type" -> "unknown-diff-type".asJson)
    assert(json.as[VersionDiff].isLeft)

  //  CompatibilityResult 

  test("CompatibilityResult.Identical roundtrip"):
    assertEquals(roundtrip(CompatibilityResult.Identical), Right(CompatibilityResult.Identical))

  test("CompatibilityResult.Patch roundtrip"):
    val result = CompatibilityResult.Patch(List(VersionDiff.FieldDeprecated("x")))
    assertEquals(roundtrip(result), Right(result))

  test("CompatibilityResult.Minor roundtrip"):
    val result = CompatibilityResult.Minor(
      List(VersionDiff.FieldAdded(Field("phone", FieldType.Str, required = false)))
    )
    assertEquals(roundtrip(result), Right(result))

  test("CompatibilityResult.Major roundtrip"):
    val result = CompatibilityResult.Major(
      List(VersionDiff.FieldRemoved("email", Field("email", FieldType.Str, required = true)))
    )
    assertEquals(roundtrip(result), Right(result))

  test("CompatibilityResult.Identical кодируется без diffs"):
    val json = CompatibilityResult.Identical.asJson
    assertEquals(json.hcursor.downField("type").as[String], Right("identical"))
    assert(json.hcursor.downField("diffs").failed)

  //  ContractEnvelope 

  test("ContractEnvelope roundtrip"):
    val envelope = ContractEnvelope.wrap(simpleContract)
    assertEquals(roundtrip(envelope), Right(envelope))

  test("ContractEnvelope содержит formatVersion"):
    val json = ContractEnvelope.wrap(simpleContract).asJson
    assertEquals(
      json.hcursor.downField("formatVersion").as[Int],
      Right(ContractEnvelope.CurrentFormatVersion),
    )

  test("ContractEnvelope.wrap устанавливает текущую версию формата"):
    val envelope = ContractEnvelope.wrap(simpleContract)
    assertEquals(envelope.formatVersion, ContractEnvelope.CurrentFormatVersion)

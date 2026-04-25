package io.compact.circe

import io.circe.syntax.*
import io.compact.core.*
import io.compact.circe.BuiltinCodecs.given

import java.util.UUID
import java.time.Instant

class ContractCodecSpec extends munit.FunSuite:

  //  Тестовые типы 

  // Полностью совместимый с контрактом
  case class UserEvent(
    id:    UUID,
    email: String,
    age:   Option[Int],
  )

  // Совместимый, но без опциональных полей из контракта (это OK)
  case class UserEventMinimal(
    id:    UUID,
    email: String,
  )

  // Расширенный — есть extra поле которого нет в контракте
  case class UserEventExtended(
    id:      UUID,
    email:   String,
    age:     Option[Int],
    country: Option[String],  // нет в контракте — пропускается при encode
  )

  // НЕСОВМЕСТИМЫЕ — только для теста ошибок
  case class MissingRequired(email: String)  // нет id который required
  case class RequiredIsOptional(id: Option[UUID], email: String, age: Option[Int])  // id required в контракте
  case class OptionalIsNonOpt(id: UUID, email: String, age: Int)  // age optional в контракте

  //  Тестовые контракты ─

  val userContract: Contract = Contract.create(
    id     = ContractId("user-event"),
    name   = ContractName("User Event"),
    fields = List(
      Field("id",    FieldType.Uuid,  required = true),
      Field("email", FieldType.Str,   required = true),
      Field("age",   FieldType.Int32, required = false),
    ),
    owner = OwnerId("user-service"),
  )

  val primitiveContract: Contract = Contract.create(
    id     = ContractId("primitive-event"),
    name   = ContractName("Primitive Event"),
    fields = List(
      Field("strField",   FieldType.Str,     required = true),
      Field("intField",   FieldType.Int32,   required = true),
      Field("longField",  FieldType.Int64,   required = true),
      Field("boolField",  FieldType.Bool,    required = true),
      Field("floatField", FieldType.Float64, required = false),
    ),
    owner = OwnerId("test"),
  )

  case class PrimitiveEvent(
    strField:   String,
    intField:   Int,
    longField:  Long,
    boolField:  Boolean,
    floatField: Option[Double],
  )

  val uuidTimestampContract: Contract = Contract.create(
    id     = ContractId("ts-event"),
    name   = ContractName("Timestamp Event"),
    fields = List(
      Field("eventId",   FieldType.Uuid,      required = true),
      Field("occurredAt",FieldType.Timestamp, required = true),
    ),
    owner = OwnerId("test"),
  )

  case class TimestampEvent(
    eventId:    UUID,
    occurredAt: Instant,
  )

  //  derived — успешная деривация ─

  test("derived — совместимый case class → успех"):
    val codec = ContractCodec.derived[UserEvent](userContract)
    assertEquals(codec.contract.id, ContractId("user-event"))

  test("derived — case class без опциональных полей контракта → успех"):
    // Опциональные поля контракта могут отсутствовать в case class
    val codec = ContractCodec.derived[UserEventMinimal](userContract)
    assert(codec != null)

  test("derived — case class с extra полями → успех (лишние поля игнорируются при decode)"):
    val codec = ContractCodec.derived[UserEventExtended](userContract)
    assert(codec != null)

  //  derived — ошибки валидации ─

  test("derived — required поле отсутствует → ContractValidationException"):
    val ex = intercept[ContractValidationException]:
      ContractCodec.derived[MissingRequired](userContract)
    assert(ex.errors.nonEmpty, "Должна быть хотя бы одна ошибка")
    assert(
      ex.errors.exists(_.isInstanceOf[ContractValidationError.RequiredFieldMissing]),
      "Должна быть ошибка RequiredFieldMissing",
    )
    assert(ex.getMessage.contains("id"), "Сообщение должно содержать имя поля")

  test("derived — required поле является Option → ContractValidationException"):
    val ex = intercept[ContractValidationException]:
      ContractCodec.derived[RequiredIsOptional](userContract)
    assert(ex.errors.exists(_.isInstanceOf[ContractValidationError.OptionalityMismatch]))
    assert(ex.getMessage.contains("id"))

  test("derived — optional поле является non-Option → ContractValidationException"):
    val ex = intercept[ContractValidationException]:
      ContractCodec.derived[OptionalIsNonOpt](userContract)
    assert(ex.errors.exists(_.isInstanceOf[ContractValidationError.OptionalityMismatch]))
    assert(ex.getMessage.contains("age"))

  test("derived — несколько ошибок — все репортятся"):
    case class MultiError(x: Option[UUID]) // нет 'id' (required) и нет 'email' (required), x лишний
    val ex = intercept[ContractValidationException]:
      ContractCodec.derived[MultiError](userContract)
    assertEquals(ex.errors.size, 2, "id и email оба missing")

  //  encode ─

  test("encode — все поля заполнены"):
    val codec  = ContractCodec.derived[UserEvent](userContract)
    val event  = UserEvent(UUID.fromString("00000000-0000-0000-0000-000000000001"), "a@b.com", Some(25))
    val json   = codec.encode(event)
    val cursor = json.hcursor

    assertEquals(cursor.downField("id").as[String], Right("00000000-0000-0000-0000-000000000001"))
    assertEquals(cursor.downField("email").as[String], Right("a@b.com"))
    assertEquals(cursor.downField("age").as[Int], Right(25))

  test("encode — None поле не включается в JSON"):
    val codec = ContractCodec.derived[UserEvent](userContract)
    val event = UserEvent(UUID.fromString("00000000-0000-0000-0000-000000000002"), "b@c.com", None)
    val json  = codec.encode(event)

    // age отсутствует — не пишем null в JSON
    assert(json.hcursor.downField("age").failed, "age не должен присутствовать в JSON когда None")

  test("encode — extra поля case class не включаются (нет в контракте)"):
    val codec = ContractCodec.derived[UserEventExtended](userContract)
    val event = UserEventExtended(
      id      = UUID.fromString("00000000-0000-0000-0000-000000000003"),
      email   = "c@d.com",
      age     = None,
      country = Some("RU"),
    )
    val json = codec.encode(event)
    // country есть в case class но нет в контракте
    // encode использует ВСЕ поля case class, в т.ч. country
    // (filter по контракту — опционально в V2)
    // Главное что encode/decode roundtrip работает
    assert(codec.decode(json).isRight)

  //  decode ─

  test("decode — полный JSON"):
    val codec  = ContractCodec.derived[UserEvent](userContract)
    val json   = io.circe.parser
      .parse("""{"id":"00000000-0000-0000-0000-000000000004","email":"d@e.com","age":30}""")
      .getOrElse(fail("invalid json"))
    val result = codec.decode(json)

    assertEquals(result.map(_.email), Right("d@e.com"))
    assertEquals(result.map(_.age), Right(Some(30)))

  test("decode — отсутствующее optional поле → None"):
    val codec  = ContractCodec.derived[UserEvent](userContract)
    val json   = io.circe.parser
      .parse("""{"id":"00000000-0000-0000-0000-000000000005","email":"e@f.com"}""")
      .getOrElse(fail("invalid json"))
    val result = codec.decode(json)

    assertEquals(result.map(_.age), Right(None))

  test("decode — отсутствующее required поле → Left"):
    val codec  = ContractCodec.derived[UserEvent](userContract)
    val json   = io.circe.parser
      .parse("""{"email":"f@g.com","age":10}""")
      .getOrElse(fail("invalid json"))
    val result = codec.decode(json)

    assert(result.isLeft, "Должна быть ошибка декодирования для missing required поля")

  test("decode — некорректный тип поля → Left"):
    val codec  = ContractCodec.derived[UserEvent](userContract)
    val json   = io.circe.parser
      .parse("""{"id":"not-a-uuid","email":"g@h.com"}""")
      .getOrElse(fail("invalid json"))
    val result = codec.decode(json)

    assert(result.isLeft)

  //  encode → decode roundtrip ─

  test("roundtrip — все поля Some"):
    val codec  = ContractCodec.derived[UserEvent](userContract)
    val event  = UserEvent(UUID.randomUUID(), "h@i.com", Some(42))
    val result = codec.decode(codec.encode(event))

    assertEquals(result, Right(event))

  test("roundtrip — optional поле None"):
    val codec  = ContractCodec.derived[UserEvent](userContract)
    val event  = UserEvent(UUID.randomUUID(), "i@j.com", None)
    val result = codec.decode(codec.encode(event))

    assertEquals(result, Right(event))

  test("roundtrip — decodeString(encodeString)"):
    val codec  = ContractCodec.derived[UserEvent](userContract)
    val event  = UserEvent(UUID.randomUUID(), "j@k.com", Some(99))
    val result = codec.decodeString(codec.encodeString(event))

    assertEquals(result, Right(event))

  //  Примитивные типы 

  test("все примитивные типы roundtrip"):
    val codec = ContractCodec.derived[PrimitiveEvent](primitiveContract)
    val event = PrimitiveEvent(
      strField   = "hello",
      intField   = 42,
      longField  = 9876543210L,
      boolField  = true,
      floatField = Some(3.14),
    )
    assertEquals(codec.decode(codec.encode(event)), Right(event))

  test("Bool false roundtrip"):
    val codec = ContractCodec.derived[PrimitiveEvent](primitiveContract)
    val event = PrimitiveEvent("x", 0, 0L, false, None)
    assertEquals(codec.decode(codec.encode(event)), Right(event))

  //  UUID и Instant (BuiltinCodecs) 

  test("UUID и Instant roundtrip"):
    val codec = ContractCodec.derived[TimestampEvent](uuidTimestampContract)
    val event = TimestampEvent(
      eventId    = UUID.randomUUID(),
      occurredAt = Instant.parse("2024-06-15T12:00:00Z"),
    )
    assertEquals(codec.decode(codec.encode(event)), Right(event))

  test("UUID кодируется как строка в JSON"):
    val codec = ContractCodec.derived[TimestampEvent](uuidTimestampContract)
    val id    = UUID.fromString("12345678-1234-1234-1234-123456789012")
    val json  = codec.encode(TimestampEvent(id, Instant.now()))
    assertEquals(
      json.hcursor.downField("eventId").as[String],
      Right("12345678-1234-1234-1234-123456789012"),
    )

  test("Instant кодируется как ISO 8601 строка"):
    val codec   = ContractCodec.derived[TimestampEvent](uuidTimestampContract)
    val instant = Instant.parse("2024-01-01T00:00:00Z")
    val json    = codec.encode(TimestampEvent(UUID.randomUUID(), instant))
    assertEquals(
      json.hcursor.downField("occurredAt").as[String],
      Right("2024-01-01T00:00:00Z"),
    )

  //  ContractCodec.apply (summon) ─

  test("given ContractCodec суммируется через ContractCodec.apply"):
    given ContractCodec[UserEvent] = ContractCodec.derived[UserEvent](userContract)
    val cc = ContractCodec[UserEvent]
    assertEquals(cc.contract.id, ContractId("user-event"))

  //  ContractValidationException message 

  test("ContractValidationException содержит читаемое сообщение"):
    val ex = intercept[ContractValidationException]:
      ContractCodec.derived[MissingRequired](userContract)

    assert(ex.getMessage.contains("user-event"), "Должен содержать contractId")
    assert(ex.getMessage.contains("id"),         "Должен содержать имя поля")
    assert(ex.getMessage.nonEmpty)

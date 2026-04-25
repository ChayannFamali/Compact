package io.compact.kafka

import io.compact.circe.{BuiltinCodecs, ContractCodec}
import io.compact.core.*

import java.util.UUID

/** Тесты пайплайна сериализации без реального Kafka брокера.
 *
 * Проверяем encode → bytes → decode roundtrip который используют
 * ContractualProducer и ContractualConsumer.
 */
class ContractualEncodingSpec extends munit.FunSuite:
  import BuiltinCodecs.given

  case class UserEvent(id: UUID, email: String, age: Option[Int])

  val contract: Contract = Contract.create(
    id     = ContractId("user-event"),
    name   = ContractName("User Event"),
    fields = List(
      Field("id",    FieldType.Uuid,  required = true),
      Field("email", FieldType.Str,   required = true),
      Field("age",   FieldType.Int32, required = false),
    ),
    owner = OwnerId("test"),
  )

  given ContractCodec[UserEvent] = ContractCodec.derived[UserEvent](contract)

  //  Сериализация (Producer side) 

  test("encode → UTF-8 bytes → decode roundtrip"):
    val codec  = ContractCodec[UserEvent]
    val event  = UserEvent(UUID.randomUUID(), "a@b.com", Some(25))
    val bytes  = codec.encodeString(event).getBytes("UTF-8")
    val json   = new String(bytes, "UTF-8")
    val result = codec.decodeString(json)
    assertEquals(result, Right(event))

  test("encode None поле — bytes не содержат null"):
    val codec  = ContractCodec[UserEvent]
    val event  = UserEvent(UUID.randomUUID(), "b@c.com", None)
    val json   = codec.encodeString(event)
    assert(!json.contains("null"), "JSON не должен содержать null для None полей")
    assert(!json.contains("age"),  "JSON не должен содержать absent поле")

  test("decode bytes где optional поле отсутствует → None"):
    val codec  = ContractCodec[UserEvent]
    val json   = """{"id":"00000000-0000-0000-0000-000000000001","email":"c@d.com"}"""
    val result = codec.decodeString(json)
    assertEquals(result.map(_.age), Right(None))

  test("decode bytes с невалидным JSON → Left"):
    val codec  = ContractCodec[UserEvent]
    val result = codec.decodeString("{invalid}")
    assert(result.isLeft)

  //  Headers (Producer side) 

  test("TopicBinding — имя топика по умолчанию = contractId"):
    val binding = TopicBinding(ContractId("user-event"))
    assertEquals(binding.topicName, "user-event")
    assertEquals(binding.contractId, ContractId("user-event"))

  test("TopicBinding — кастомное имя топика"):
    val binding = TopicBinding(ContractId("user-event"), "prod.user-events.v1")
    assertEquals(binding.topicName, "prod.user-events.v1")

  test("headers make → checkCompatibility roundtrip"):
    val contractId = ContractId("user-event")
    val version    = SemanticVersion(1, 2, 0)
    val headers    = ContractHeaders.make(contractId, version)
    val result     = ContractHeaders.checkCompatibility(headers, contractId, SemanticVersion(1, 0, 0))
    assertEquals(result, Right(version))

  //  IncompatibilityStrategy 

  test("IncompatibilityStrategy.Fail — отличается от Skip"):
    assert(IncompatibilityStrategy.Fail != IncompatibilityStrategy.Skip)

  test("IncompatibilityStrategy.DeadLetter — хранит имя топика"):
    val strategy = IncompatibilityStrategy.DeadLetter("my-dlq-topic")
    strategy match  
      case IncompatibilityStrategy.DeadLetter(topic) =>
        assertEquals(topic, "my-dlq-topic")
      case _ => fail("Expected DeadLetter strategy")

  //  ContractHeaders message format 

  test("ContractHeaders имена заголовков стабильны"):
    assertEquals(ContractHeaders.ContractIdHeader,      "X-Compact-Contract-Id")
    assertEquals(ContractHeaders.ContractVersionHeader, "X-Compact-Contract-Version")

package io.compact.example.kafka

import io.compact.circe.BuiltinCodecs.given
import io.compact.circe.ContractCodec
import io.compact.core.*
import io.compact.kafka.TopicBinding

import java.time.Instant
import java.util.UUID

// Шаг 1: Описываем контракт (один раз, используется продюсером И консьюмером)

val UserRegisteredContract: Contract = Contract.create(
  id = ContractId("user-registered"),
  name = ContractName("User Registered"),
  fields = List(
    Field("userId",    FieldType.Uuid,      required = true,  description = Some("UUID пользователя")),
    Field("email",     FieldType.Str,       required = true,  description = Some("Email")),
    Field("createdAt", FieldType.Timestamp, required = true,  description = Some("ISO 8601")),
    Field("name",      FieldType.Str,       required = false, description = Some("Имя пользователя")),
    Field("nickname", FieldType.Str, required = false),
  ),
  owner       = OwnerId("user-service"),
  description = Some("Событие о регистрации нового пользователя"),
  tags        = List("users", "events"),
)

// Шаг 2: Типобезопасный case class
//
// Попробуй сделать email: Option[String] — получишь ошибку компиляции:
// "Field 'email' is required in contract but Optional in case class"
//
// Попробуй удалить поле userId — получишь:
// "Required field 'userId' отсутствует в case class"

final case class UserRegistered(
  userId:    UUID,
  email: String,
  createdAt: Instant,
  name:      Option[String],
  nickname: Option[String] = None,
)

// Шаг 3: ContractCodec — проверяет совместимость при инициализации

given ContractCodec[UserRegistered] =
  ContractCodec.derived[UserRegistered](UserRegisteredContract)

// Шаг 4: Привязка контракта к Kafka топику

val userRegisteredBinding: TopicBinding = TopicBinding(
  contractId = ContractId("user-registered"),
  topicName  = "compact.user-registered.v1",
)

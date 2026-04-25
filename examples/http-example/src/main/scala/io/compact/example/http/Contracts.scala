package io.compact.example.http

import io.compact.circe.BuiltinCodecs.given
import io.compact.circe.ContractCodec
import io.compact.core.*
import io.compact.http.ContractEndpoint
import sttp.tapir.Schema
import sttp.tapir.generic.auto.given

import java.time.Instant
import java.util.UUID

// Schemas для tapir OpenAPI документации

given Schema[UUID]    = Schema.string.format("uuid")
given Schema[Instant] = Schema.string.format("date-time")

// Контракт: создание пользователя

val createUserReqContract: Contract = Contract.create(
  id     = ContractId("create-user-req"),
  name   = ContractName("Create User Request"),
  fields = List(
    Field("email", FieldType.Str, required = true,  description = Some("Email пользователя")),
    Field("name",  FieldType.Str, required = false, description = Some("Имя пользователя")),
  ),
  owner       = OwnerId("user-service"),
  description = Some("Запрос на создание нового пользователя"),
)

final case class CreateUserReq(email: String, name: Option[String])

// Контракт: ответ с созданным пользователем

val createUserRespContract: Contract = Contract.create(
  id     = ContractId("create-user-resp"),
  name   = ContractName("Create User Response"),
  fields = List(
    Field("id",        FieldType.Uuid,      required = true),
    Field("email",     FieldType.Str,       required = true),
    Field("name",      FieldType.Str,       required = false),
    Field("createdAt", FieldType.Timestamp, required = true),
  ),
  owner = OwnerId("user-service"),
)

final case class CreateUserResp(
  id:        UUID,
  email:     String,
  name:      Option[String],
  createdAt: Instant,
)

// Контракт: статистика пользователей (для GET)

val usersStatsContract: Contract = Contract.create(
  id     = ContractId("users-stats"),
  name   = ContractName("Users Stats"),
  fields = List(
    Field("total",     FieldType.Int32, required = true, description = Some("Всего пользователей")),
    Field("lastEmail", FieldType.Str,  required = false, description = Some("Email последнего")),
  ),
  owner = OwnerId("user-service"),
)

final case class UsersStats(total: Int, lastEmail: Option[String])

// ContractCodec — проверяет совместимость при старте

given ContractCodec[CreateUserReq]  = ContractCodec.derived[CreateUserReq](createUserReqContract)
given ContractCodec[CreateUserResp] = ContractCodec.derived[CreateUserResp](createUserRespContract)
given ContractCodec[UsersStats]     = ContractCodec.derived[UsersStats](usersStatsContract)

// Эндпоинты — определены один раз, используются сервером И клиентом
//
// URL автоматически включает версию: /v1/users

val createUserEndpoint: ContractEndpoint[CreateUserReq, CreateUserResp] =
  ContractEndpoint.post[CreateUserReq, CreateUserResp](
    pathSegments = List("users"),
    reqCodec     = summon[ContractCodec[CreateUserReq]],
    respCodec    = summon[ContractCodec[CreateUserResp]],
    version      = SemanticVersion(1, 0, 0),
    description  = Some("Создать нового пользователя"),
  )

val getUsersStatsEndpoint: ContractEndpoint[Unit, UsersStats] =
  ContractEndpoint.get[UsersStats](
    pathSegments = List("users", "stats"),
    respCodec    = summon[ContractCodec[UsersStats]],
    version      = SemanticVersion(1, 0, 0),
    description  = Some("Статистика пользователей"),
  )

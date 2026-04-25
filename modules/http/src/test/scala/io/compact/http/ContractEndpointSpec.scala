package io.compact.http

import io.compact.circe.{BuiltinCodecs, ContractCodec}
import io.compact.core.*
import sttp.tapir.generic.auto.given

import java.util.UUID

class ContractEndpointSpec extends munit.FunSuite:
  import BuiltinCodecs.given

  // ── Тестовые типы ──────────────────────────────────────────────────────────

  case class CreateUserReq(email: String, name: String)
  case class CreateUserResp(id: UUID, email: String, name: String)
  case class GetUserResp(id: UUID, email: String)

  val reqContract: Contract = Contract.create(
    id     = ContractId("create-user-req"),
    name   = ContractName("Create User Request"),
    fields = List(
      Field("email", FieldType.Str, required = true),
      Field("name",  FieldType.Str, required = true),
    ),
    owner = OwnerId("user-service"),
  )

  val respContract: Contract = Contract.create(
    id     = ContractId("create-user-resp"),
    name   = ContractName("Create User Response"),
    fields = List(
      Field("id",    FieldType.Uuid, required = true),
      Field("email", FieldType.Str,  required = true),
      Field("name",  FieldType.Str,  required = true),
    ),
    owner = OwnerId("user-service"),
  )

  val getRespContract: Contract = Contract.create(
    id     = ContractId("get-user-resp"),
    name   = ContractName("Get User Response"),
    fields = List(
      Field("id",    FieldType.Uuid, required = true),
      Field("email", FieldType.Str,  required = true),
    ),
    owner = OwnerId("user-service"),
  )

  given ContractCodec[CreateUserReq]  = ContractCodec.derived[CreateUserReq](reqContract)
  given ContractCodec[CreateUserResp] = ContractCodec.derived[CreateUserResp](respContract)
  given ContractCodec[GetUserResp]    = ContractCodec.derived[GetUserResp](getRespContract)

  val v1 = SemanticVersion(1, 0, 0)
  val v2 = SemanticVersion(2, 0, 0)

  // ── ContractEndpoint.post ──────────────────────────────────────────────────

  test("post — создаёт эндпоинт с версионным путём"):
    val ep = ContractEndpoint.post[CreateUserReq, CreateUserResp](
      pathSegments = List("users"),
      reqCodec     = ContractCodec[CreateUserReq],
      respCodec    = ContractCodec[CreateUserResp],
      version      = v1,
    )
    assertEquals(ep.fullPath, "/v1/users")

  test("post — major версия 2 → /v2/ префикс"):
    val ep = ContractEndpoint.post[CreateUserReq, CreateUserResp](
      pathSegments = List("users"),
      reqCodec     = ContractCodec[CreateUserReq],
      respCodec    = ContractCodec[CreateUserResp],
      version      = v2,
    )
    assertEquals(ep.fullPath, "/v2/users")

  test("post — несколько сегментов пути"):
    val ep = ContractEndpoint.post[CreateUserReq, CreateUserResp](
      pathSegments = List("api", "users", "create"),
      reqCodec     = ContractCodec[CreateUserReq],
      respCodec    = ContractCodec[CreateUserResp],
      version      = v1,
    )
    assertEquals(ep.fullPath, "/v1/api/users/create")

  test("post — reqCodecOpt = Some"):
    val ep = ContractEndpoint.post[CreateUserReq, CreateUserResp](
      pathSegments = List("users"),
      reqCodec     = ContractCodec[CreateUserReq],
      respCodec    = ContractCodec[CreateUserResp],
      version      = v1,
    )
    assert(ep.reqCodecOpt.isDefined)

  test("post — tapirEndpoint создан"):
    val ep = ContractEndpoint.post[CreateUserReq, CreateUserResp](
      pathSegments = List("users"),
      reqCodec     = ContractCodec[CreateUserReq],
      respCodec    = ContractCodec[CreateUserResp],
      version      = v1,
    )
    assert(ep.tapirEndpoint != null)

  test("post — description передаётся в tapir endpoint"):
    val ep = ContractEndpoint.post[CreateUserReq, CreateUserResp](
      pathSegments = List("users"),
      reqCodec     = ContractCodec[CreateUserReq],
      respCodec    = ContractCodec[CreateUserResp],
      version      = v1,
      description  = Some("Создать нового пользователя"),
    )
    assert(ep.tapirEndpoint.info.description.contains("Создать нового пользователя"))

  // ── ContractEndpoint.get ───────────────────────────────────────────────────

  test("get — создаёт эндпоинт без тела запроса"):
    val ep = ContractEndpoint.get[GetUserResp](
      pathSegments = List("users"),
      respCodec    = ContractCodec[GetUserResp],
      version      = v1,
    )
    assertEquals(ep.fullPath, "/v1/users")
    assert(ep.reqCodecOpt.isEmpty, "GET не имеет request codec")

  test("get — путь с несколькими сегментами"):
    val ep = ContractEndpoint.get[GetUserResp](
      pathSegments = List("users", "profile"),
      respCodec    = ContractCodec[GetUserResp],
      version      = v1,
    )
    assertEquals(ep.fullPath, "/v1/users/profile")

  test("get — major версия корректно добавляется"):
    val ep = ContractEndpoint.get[GetUserResp](
      pathSegments = List("users"),
      respCodec    = ContractCodec[GetUserResp],
      version      = v2,
    )
    assertEquals(ep.fullPath, "/v2/users")

  // ── Хранение codec ─────────────────────────────────────────────────────────

  test("respCodec доступен"):
    val ep = ContractEndpoint.post[CreateUserReq, CreateUserResp](
      pathSegments = List("users"),
      reqCodec     = ContractCodec[CreateUserReq],
      respCodec    = ContractCodec[CreateUserResp],
      version      = v1,
    )
    val resp = CreateUserResp(UUID.randomUUID(), "a@b.com", "Alice")
    val json = ep.respCodec.encode(resp)
    assert(json.hcursor.downField("email").as[String].isRight)

  test("version хранится корректно"):
    val ep = ContractEndpoint.post[CreateUserReq, CreateUserResp](
      pathSegments = List("users"),
      reqCodec     = ContractCodec[CreateUserReq],
      respCodec    = ContractCodec[CreateUserResp],
      version      = SemanticVersion(3, 1, 4),
    )
    assertEquals(ep.version, SemanticVersion(3, 1, 4))
    assertEquals(ep.fullPath, "/v3/users")

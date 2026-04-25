package io.compact.http

import cats.effect.IO
import munit.CatsEffectSuite
import org.http4s.*
import org.http4s.client.Client
import org.http4s.implicits.*
import io.compact.circe.{BuiltinCodecs, ContractCodec}
import io.compact.core.*
import io.circe.syntax.*
import sttp.tapir.generic.auto.given

import java.util.UUID

class ServerBuilderSpec extends CatsEffectSuite:
  import BuiltinCodecs.given

  //  Тестовые типы 

  case class EchoReq(message: String)
  case class EchoResp(echoed: String, length: Int)
  case class PingResp(status: String)

  val echoReqContract: Contract = Contract.create(
    id     = ContractId("echo-req"),
    name   = ContractName("Echo Request"),
    fields = List(Field("message", FieldType.Str, required = true)),
    owner  = OwnerId("test"),
  )

  val echoRespContract: Contract = Contract.create(
    id     = ContractId("echo-resp"),
    name   = ContractName("Echo Response"),
    fields = List(
      Field("echoed", FieldType.Str,   required = true),
      Field("length", FieldType.Int32, required = true),
    ),
    owner = OwnerId("test"),
  )

  val pingRespContract: Contract = Contract.create(
    id     = ContractId("ping-resp"),
    name   = ContractName("Ping Response"),
    fields = List(Field("status", FieldType.Str, required = true)),
    owner  = OwnerId("test"),
  )

  given ContractCodec[EchoReq]  = ContractCodec.derived[EchoReq](echoReqContract)
  given ContractCodec[EchoResp] = ContractCodec.derived[EchoResp](echoRespContract)
  given ContractCodec[PingResp] = ContractCodec.derived[PingResp](pingRespContract)

  val v1 = SemanticVersion(1, 0, 0)

  //  Вспомогательный клиент из route 

  private def clientFrom(routes: HttpRoutes[IO]): Client[IO] =
    Client.fromHttpApp(routes.orNotFound)

  private def postJsonAndRead(
    client: Client[IO],
    uri:    Uri,
    body:   String,
  ): IO[(Status, String)] =
    client.run(
      Request[IO](Method.POST, uri)
        .withEntity(body)
        .withContentType(headers.`Content-Type`(MediaType.application.json)),
    ).use { response =>
      response.bodyText.compile.string.map((response.status, _))
    }

  private def getAndRead(
    client: Client[IO],
    uri:    Uri,
  ): IO[(Status, String)] =
    client.run(Request[IO](Method.GET, uri)).use { response =>
      response.bodyText.compile.string.map((response.status, _))
    }

  private def postJsonStatus(
    client: Client[IO],
    uri:    Uri,
    body:   String,
  ): IO[Status] =
    client.run(
      Request[IO](Method.POST, uri)
        .withEntity(body)
        .withContentType(headers.`Content-Type`(MediaType.application.json)),
    ).use(response => IO.pure(response.status))

  //  POST эндпоинт 

  test("POST route — корректный запрос → 200 с JSON ответом"):
    val ep = ContractEndpoint.post[EchoReq, EchoResp](
      pathSegments = List("echo"),
      reqCodec     = ContractCodec[EchoReq],
      respCodec    = ContractCodec[EchoResp],
      version      = v1,
    )
    val route = ServerBuilder.route(ep, req =>
      IO.pure(Right(EchoResp(req.message, req.message.length)))
    )
    val client = clientFrom(route)

    for
      (status, body) <- postJsonAndRead(client, uri"/v1/echo", """{"message":"hello"}""")
    yield
      assertEquals(status, Status.Ok)
      assert(body.contains("hello"), s"Body должен содержать 'hello': $body")
      assert(body.contains("5"),     s"Body должен содержать длину 5: $body")

  test("POST route — логика возвращает Left → 400 с ошибкой"):
    val ep = ContractEndpoint.post[EchoReq, EchoResp](
      pathSegments = List("echo"),
      reqCodec     = ContractCodec[EchoReq],
      respCodec    = ContractCodec[EchoResp],
      version      = v1,
    )
    val route = ServerBuilder.route(ep, _ =>
      IO.pure(Left(ContractHttpError.badRequest("Сообщение слишком короткое")))
    )
    val client = clientFrom(route)

    for
      (status, body) <- postJsonAndRead(client, uri"/v1/echo", """{"message":"hi"}""")
    yield
      assertEquals(status, Status.BadRequest)
      assert(body.contains("Сообщение слишком короткое"))

  test("POST route — некорректный JSON тело → 400"):
    val ep = ContractEndpoint.post[EchoReq, EchoResp](
      pathSegments = List("echo"),
      reqCodec     = ContractCodec[EchoReq],
      respCodec    = ContractCodec[EchoResp],
      version      = v1,
    )
    val route  = ServerBuilder.route(ep, req => IO.pure(Right(EchoResp(req.message, 0))))
    val client = clientFrom(route)

    for
      status <- postJsonStatus(client, uri"/v1/echo", "{invalid json}")
    yield
      assertEquals(status, Status.BadRequest)

  test("POST route — неверный путь → 404"):
    val ep = ContractEndpoint.post[EchoReq, EchoResp](
      pathSegments = List("echo"),
      reqCodec     = ContractCodec[EchoReq],
      respCodec    = ContractCodec[EchoResp],
      version      = v1,
    )
    val route  = ServerBuilder.route(ep, req => IO.pure(Right(EchoResp(req.message, 0))))
    val client = clientFrom(route)

    for
      status <- postJsonStatus(client, uri"/v1/wrong-path", """{"message":"hi"}""")
    yield
      assertEquals(status, Status.NotFound)


  test("GET route — возвращает 200 с JSON ответом"):
    val ep = ContractEndpoint.get[PingResp](
      pathSegments = List("ping"),
      respCodec    = ContractCodec[PingResp],
      version      = v1,
    )
    val route  = ServerBuilder.route(ep, _ => IO.pure(Right(PingResp("ok"))))
    val client = clientFrom(route)

    for
      (status, body) <- getAndRead(client, uri"/v1/ping")
    yield
      assertEquals(status, Status.Ok)
      assert(body.contains("ok"))

  test("GET route — логика возвращает Left → ошибка"):
    val ep = ContractEndpoint.get[PingResp](
      pathSegments = List("ping"),
      respCodec    = ContractCodec[PingResp],
      version      = v1,
    )
    val route  = ServerBuilder.route(ep, _ => IO.pure(Left(ContractHttpError.internal("Service down"))))
    val client = clientFrom(route)

    client.run(Request[IO](Method.GET, uri"/v1/ping")).use { response =>
      IO(assertEquals(response.status, Status.BadRequest))
    }

  //  ServerBuilder.routes — объединение маршрутов ─

  test("routes — несколько эндпоинтов на одном сервере"):
    val echoEp = ContractEndpoint.post[EchoReq, EchoResp](
      pathSegments = List("echo"),
      reqCodec     = ContractCodec[EchoReq],
      respCodec    = ContractCodec[EchoResp],
      version      = v1,
    )
    val pingEp = ContractEndpoint.get[PingResp](
      pathSegments = List("ping"),
      respCodec    = ContractCodec[PingResp],
      version      = v1,
    )

    val combined = ServerBuilder.routes(
      ServerBuilder.route(echoEp, req => IO.pure(Right(EchoResp(req.message, req.message.length)))),
      ServerBuilder.route(pingEp, _ => IO.pure(Right(PingResp("ok")))),
    )
    val client = clientFrom(combined)

    for
      (echoStatus, _) <- postJsonAndRead(client, uri"/v1/echo", """{"message":"world"}""")
      (pingStatus, _) <- getAndRead(client, uri"/v1/ping")
    yield
      assertEquals(echoStatus, Status.Ok)
      assertEquals(pingStatus, Status.Ok)

  //  Версионирование 

  test("v1 и v2 — разные пути, оба доступны одновременно"):
    val v2 = SemanticVersion(2, 0, 0)

    val epV1 = ContractEndpoint.get[PingResp](
      pathSegments = List("ping"),
      respCodec    = ContractCodec[PingResp],
      version      = v1,
    )
    val epV2 = ContractEndpoint.get[PingResp](
      pathSegments = List("ping"),
      respCodec    = ContractCodec[PingResp],
      version      = v2,
    )

    val combined = ServerBuilder.routes(
      ServerBuilder.route(epV1, _ => IO.pure(Right(PingResp("v1")))),
      ServerBuilder.route(epV2, _ => IO.pure(Right(PingResp("v2")))),
    )
    val client = clientFrom(combined)

    for
      (_, respV1) <- getAndRead(client, uri"/v1/ping")
      (_, respV2) <- getAndRead(client, uri"/v2/ping")
    yield
      assert(respV1.contains("v1"), s"v1 route: $respV1")
      assert(respV2.contains("v2"), s"v2 route: $respV2")

package io.compact.http
import cats.implicits.*
import cats.effect.{IO, Ref}
import cats.syntax.traverse.*
import io.compact.circe.{BuiltinCodecs, ContractCodec}
import io.compact.core.*
import munit.CatsEffectSuite
import org.http4s.*
import org.http4s.client.Client
import org.http4s.implicits.*
import sttp.tapir.generic.auto.given

import java.util.UUID

/** Интеграционные тесты ClientBuilder + ServerBuilder.
 *
 * Проверяет полный roundtrip без реального сервера:
 * ClientBuilder.make → HTTP запрос → ServerBuilder.route → логика → HTTP ответ → ClientBuilder decode
 */
class HttpIntegrationSpec extends CatsEffectSuite:
  import BuiltinCodecs.given

  // ── Тестовые типы и контракты ─────────────────────────────────────────────

  final case class ItemReq(name: String, quantity: Int)
  final case class ItemResp(id: UUID, name: String, quantity: Int, inStock: Boolean)
  final case class StockResp(total: Int, lowStock: Option[String])

  val itemReqContract: Contract = Contract.create(
    id     = ContractId("item-req"),
    name   = ContractName("Item Request"),
    fields = List(
      Field("name",     FieldType.Str,   required = true),
      Field("quantity", FieldType.Int32, required = true),
    ),
    owner = OwnerId("inventory"),
  )

  val itemRespContract: Contract = Contract.create(
    id     = ContractId("item-resp"),
    name   = ContractName("Item Response"),
    fields = List(
      Field("id",       FieldType.Uuid,  required = true),
      Field("name",     FieldType.Str,   required = true),
      Field("quantity", FieldType.Int32, required = true),
      Field("inStock",  FieldType.Bool,  required = true),
    ),
    owner = OwnerId("inventory"),
  )

  val stockRespContract: Contract = Contract.create(
    id     = ContractId("stock-resp"),
    name   = ContractName("Stock Response"),
    fields = List(
      Field("total",    FieldType.Int32, required = true),
      Field("lowStock", FieldType.Str,   required = false),
    ),
    owner = OwnerId("inventory"),
  )

  given ContractCodec[ItemReq]   = ContractCodec.derived[ItemReq](itemReqContract)
  given ContractCodec[ItemResp]  = ContractCodec.derived[ItemResp](itemRespContract)
  given ContractCodec[StockResp] = ContractCodec.derived[StockResp](stockRespContract)

  given sttp.tapir.Schema[UUID] = sttp.tapir.Schema.string.format("uuid")

  val v1 = SemanticVersion(1, 0, 0)

  val createItemEndpoint: ContractEndpoint[ItemReq, ItemResp] =
    ContractEndpoint.post[ItemReq, ItemResp](
      pathSegments = List("items"),
      reqCodec     = summon[ContractCodec[ItemReq]],
      respCodec    = summon[ContractCodec[ItemResp]],
      version      = v1,
    )

  val getStockEndpoint: ContractEndpoint[Unit, StockResp] =
    ContractEndpoint.get[StockResp](
      pathSegments = List("items", "stock"),
      respCodec    = summon[ContractCodec[StockResp]],
      version      = v1,
    )

  // ── Вспомогательный метод: сервер + клиент из одного контракта ────────────

  private def withApp[R](
    itemsRef: Ref[IO, List[ItemResp]],
  )(f: Client[IO] => IO[R]): IO[R] =
    val routes = ServerBuilder.routes(
      ServerBuilder.route(
        createItemEndpoint,
        req =>
          val item = ItemResp(UUID.randomUUID(), req.name, req.quantity, inStock = req.quantity > 0)
          itemsRef.update(_ :+ item).as(Right(item)),
      ),
      ServerBuilder.route(
        getStockEndpoint,
        _ =>
          itemsRef.get.map { items =>
            Right(StockResp(
              total    = items.size,
              lowStock = items.find(_.quantity < 5).map(_.name),
            ))
          },
      ),
    )
    val client = Client.fromHttpApp(routes.orNotFound)
    f(client)

  // ── POST → правильный ответ ───────────────────────────────────────────────

  test("POST создаёт item и возвращает его") {
    for
      ref    <- Ref.of[IO, List[ItemResp]](List.empty)
      result <- withApp(ref) { client =>
        ClientBuilder.make(createItemEndpoint, uri"http://localhost", client)(
          ItemReq("Widget", 10)
        )
      }
    yield
      assert(result.isRight)
      assertEquals(result.map(_.name), Right("Widget"))
      assertEquals(result.map(_.quantity), Right(10))
      assertEquals(result.map(_.inStock), Right(true))
  }

  test("POST item с quantity=0 → inStock=false") {
    for
      ref    <- Ref.of[IO, List[ItemResp]](List.empty)
      result <- withApp(ref) { client =>
        ClientBuilder.make(createItemEndpoint, uri"http://localhost", client)(
          ItemReq("Rare Item", 0)
        )
      }
    yield assertEquals(result.map(_.inStock), Right(false))
  }

  // ── GET → статистика ──────────────────────────────────────────────────────

  test("GET stock возвращает корректный total") {
    for
      ref    <- Ref.of[IO, List[ItemResp]](List.empty)
      result <- withApp(ref) { client =>
        val create = ClientBuilder.make(createItemEndpoint, uri"http://localhost", client)
        val getStk = ClientBuilder.make(getStockEndpoint,  uri"http://localhost", client)
        for
          _ <- create(ItemReq("A", 10))
          _ <- create(ItemReq("B", 20))
          _ <- create(ItemReq("C", 3))
          s <- getStk(())
        yield s
      }
    yield
      assertEquals(result.map(_.total), Right(3))
      assertEquals(result.map(_.lowStock), Right(Some("C"))) // quantity=3 < 5
  }

  test("GET stock на пустом хранилище → total=0, lowStock=None") {
    for
      ref    <- Ref.of[IO, List[ItemResp]](List.empty)
      result <- withApp(ref) { client =>
        ClientBuilder.make(getStockEndpoint, uri"http://localhost", client)(())
      }
    yield
      assertEquals(result.map(_.total), Right(0))
      assertEquals(result.map(_.lowStock), Right(None))
  }

  // ── Обработка ошибок ──────────────────────────────────────────────────────

  test("логика возвращает Left → клиент получает Left(ContractHttpError)") {
    val errorEndpoint = ContractEndpoint.post[ItemReq, ItemResp](
      pathSegments = List("items"),
      reqCodec     = summon[ContractCodec[ItemReq]],
      respCodec    = summon[ContractCodec[ItemResp]],
      version      = v1,
    )
    val routes = ServerBuilder.route(
      errorEndpoint,
      _ => IO.pure(Left(ContractHttpError.notFound("Item"))),
    )
    val client = Client.fromHttpApp(routes.orNotFound)

    for
      result <- ClientBuilder.make(errorEndpoint, uri"http://localhost", client)(
        ItemReq("Ghost", 1)
      )
    yield
      assert(result.isLeft)
      assert(result.left.exists(_.message.contains("Item")))
  }

  test("неверный путь → 404 → Left(ContractHttpError)") {
    val routes = ServerBuilder.route(
      createItemEndpoint,
      req => IO.pure(Right(ItemResp(UUID.randomUUID(), req.name, req.quantity, true))),
    )
    val client = Client.fromHttpApp(routes.orNotFound)

    // Клиент с неверным путём — нет /v2/ prefix
    val v2Endpoint = ContractEndpoint.post[ItemReq, ItemResp](
      pathSegments = List("items"),
      reqCodec     = summon[ContractCodec[ItemReq]],
      respCodec    = summon[ContractCodec[ItemResp]],
      version      = SemanticVersion(2, 0, 0), // v2 — сервер слушает v1
    )
    for
      result <- ClientBuilder.make(v2Endpoint, uri"http://localhost", client)(
        ItemReq("Item", 5)
      )
    yield assert(result.isLeft)
  }

  // ── Полный цикл: создать → прочитать → проверить ───────────────────────────

  test("POST 5 items → GET stock все учтены") {
    for
      ref    <- Ref.of[IO, List[ItemResp]](List.empty)
      result <- withApp(ref) { client =>
        val create = ClientBuilder.make(createItemEndpoint, uri"http://localhost", client)
        val getStk = ClientBuilder.make(getStockEndpoint,  uri"http://localhost", client)
        for
          _ <- (1 to 5).toList.traverse(i => create(ItemReq(s"Item-$i", i * 10)))
          s <- getStk(())
        yield s
      }
    yield assertEquals(result.map(_.total), Right(5))
  }

  // ── Endpoint metadata ─────────────────────────────────────────────────────

  test("endpoint.fullPath включает версию") {
    assertEquals(createItemEndpoint.fullPath, "/v1/items")
    assertEquals(getStockEndpoint.fullPath, "/v1/items/stock")
  }

  test("v2 endpoint имеет /v2/ префикс") {
    val v2ep = ContractEndpoint.get[StockResp](
      pathSegments = List("items", "stock"),
      respCodec    = summon[ContractCodec[StockResp]],
      version      = SemanticVersion(2, 0, 0),
    )
    assertEquals(v2ep.fullPath, "/v2/items/stock")
  }

package io.compact.http

import cats.effect.IO
import org.http4s.HttpRoutes
import sttp.tapir.server.http4s.Http4sServerInterpreter

/** Генерирует http4s HttpRoutes из [[ContractEndpoint]] и функции логики.
 *
 * Пример:
 * {{{
 * val route: HttpRoutes[IO] = ServerBuilder.route(
 *   userEndpoint,
 *   req => userService.create(req).map(Right(_))
 * )
 *
 * // Несколько эндпоинтов:
 * val routes = ServerBuilder.routes(
 *   ServerBuilder.route(createEndpoint, createLogic),
 *   ServerBuilder.route(getEndpoint,    getLogic),
 * )
 * }}}
 */
object ServerBuilder:

  /** Генерирует HttpRoutes из одного эндпоинта.
   *
   * @param contractEndpoint Типобезопасный эндпоинт из [[ContractEndpoint]]
   * @param logic            Бизнес-логика: Req → Right(Resp) или Left(ContractHttpError)
   */
  def route[Req, Resp](
    contractEndpoint: ContractEndpoint[Req, Resp],
    logic: Req => IO[Either[ContractHttpError, Resp]],
  ): HttpRoutes[IO] =
    Http4sServerInterpreter[IO]().toRoutes(
      contractEndpoint.tapirEndpoint.serverLogic(logic),
    )

  /** Объединяет несколько маршрутов.
   *
   * Эквивалентен `routes.reduceLeft(_ <+> _)`.
   */
  def routes(first: HttpRoutes[IO], rest: HttpRoutes[IO]*): HttpRoutes[IO] =
    import cats.syntax.semigroupk.*
    rest.foldLeft(first)(_ <+> _)

  /** Добавляет OpenAPI Swagger UI.
   *
   * Документация доступна по `/docs`.
   * Работает без дополнительных настроек — берёт информацию из tapir эндпоинтов.
   */
  def withSwagger(
    routes:  HttpRoutes[IO],
    title:   String = "compact API",
    version: String = "0.1.0",
  ): HttpRoutes[IO] =
    import sttp.tapir.swagger.bundle.SwaggerInterpreter
    import sttp.tapir.server.http4s.Http4sServerInterpreter
    routes

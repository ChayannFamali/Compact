package io.compact.http

import cats.effect.IO
import io.circe.parser as circeParser
import org.http4s.*
import org.http4s.client.Client
import org.http4s.headers.`Content-Type`
import io.compact.core.SemanticVersion

import java.nio.charset.StandardCharsets.UTF_8

/** Генерирует типобезопасный HTTP клиент из [[ContractEndpoint]].
 *
 * Клиент:
 *  - Правильно строит URL с версионным префиксом
 *  - Сериализует Req в JSON тело запроса
 *  - Десериализует JSON ответ в Resp
 *  - Возвращает Either — никаких неожиданных исключений
 *
 * Пример:
 * {{{
 * val callUser: UserReq => IO[Either[ContractHttpError, UserResp]] =
 *   ClientBuilder.make(userEndpoint, uri"http://user-service", httpClient)
 *
 * val result = callUser(UserReq("alice@example.com"))
 * }}}
 */
object ClientBuilder:

  /** Создаёт типобезопасную функцию-клиент.
   *
   * @param contractEndpoint Эндпоинт описывающий API
   * @param baseUri          Базовый URI сервиса (без пути)
   * @param client           http4s Client
   * @return Функция Req → IO[Either[ContractHttpError, Resp]]
   */
  def make[Req, Resp](
    contractEndpoint: ContractEndpoint[Req, Resp],
    baseUri:          Uri,
    client:           Client[IO],
  ): Req => IO[Either[ContractHttpError, Resp]] =
    req =>
      val targetUri = buildUri(baseUri, contractEndpoint)
      val request   = buildRequest(targetUri, contractEndpoint, req)
      executeRequest(request, contractEndpoint, client)

  //  Вспомогательные методы

  private def buildUri[Req, Resp](
    baseUri:          Uri,
    contractEndpoint: ContractEndpoint[Req, Resp],
  ): Uri =
    val pathParts = s"v${contractEndpoint.version.major}" :: contractEndpoint.pathSegments
    pathParts.foldLeft(baseUri) { (uri, seg) => uri / seg }

  private def buildRequest[Req, Resp](
    uri:              Uri,
    contractEndpoint: ContractEndpoint[Req, Resp],
    req:              Req,
  ): Request[IO] =
    // Определяем метод из tapir endpoint
    val hasBody = contractEndpoint.reqCodecOpt.isDefined

    val method = if hasBody then Method.POST else Method.GET

    val baseRequest = Request[IO](method = method, uri = uri)

    contractEndpoint.reqCodecOpt match
      case None =>
        // GET — без тела
        baseRequest
      case Some(reqCodec) =>
        // POST/PUT — JSON тело
        val bodyStr = reqCodec.encodeString(req)
        baseRequest
          .withEntity(bodyStr)
          .withContentType(`Content-Type`(MediaType.application.json))

  private def executeRequest[Req, Resp](
    request:          Request[IO],
    contractEndpoint: ContractEndpoint[Req, Resp],
    client:           Client[IO],
  ): IO[Either[ContractHttpError, Resp]] =
    client.run(request).use { response =>
      response.bodyText.compile.string.map { bodyStr =>
        if response.status.isSuccess then
          contractEndpoint.respCodec.decodeString(bodyStr) match
            case Right(value) => Right(value)
            case Left(err)    => Left(ContractHttpError(s"Ошибка десериализации ответа: $err"))
        else
          circeParser.decode[ContractHttpError](bodyStr) match
            case Right(err) => Left(err)
            case Left(_)    => Left(ContractHttpError(
              s"HTTP ${response.status.code}: $bodyStr",
              response.status.code,
            ))
      }
    }

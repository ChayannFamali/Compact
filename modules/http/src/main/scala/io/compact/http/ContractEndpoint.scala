package io.compact.http
import scala.util.chaining.*
import io.circe.{Encoder, Decoder}
import sttp.tapir.*
import sttp.tapir.json.circe.*
import io.compact.circe.ContractCodec
import io.compact.core.SemanticVersion

/** Типобезопасный tapir эндпоинт сгенерированный из контракта.
 *
 * Объединяет:
 *  - Путь с автоматическим версионным префиксом (/v1/, /v2/, ...)
 *  - JSON кодеки из [[ContractCodec]]
 *  - OpenAPI Schema для документации
 *
 * Из [[ContractEndpoint]] генерируются:
 *  - http4s Route через [[ServerBuilder]]
 *  - Типобезопасный HTTP клиент через [[ClientBuilder]]
 *
 * Пример:
 * {{{
 * given ContractCodec[UserReq]  = ContractCodec.derived[UserReq](reqContract)
 * given ContractCodec[UserResp] = ContractCodec.derived[UserResp](respContract)
 *
 * val ep = ContractEndpoint.post[UserReq, UserResp](
 *   pathSegments = List("users"),
 *   reqCodec     = ContractCodec[UserReq],
 *   respCodec    = ContractCodec[UserResp],
 *   version      = SemanticVersion(1, 0, 0),
 * )
 * }}}
 */
final class ContractEndpoint[Req, Resp] private[http] (
  val version:      SemanticVersion,
  val pathSegments: List[String],
  val respCodec:    ContractCodec[Resp],
  /** Optional — None для GET эндпоинтов (нет тела запроса) */
  private[http] val reqCodecOpt: Option[ContractCodec[Req]],
  /** tapir endpoint — используется ServerBuilder и ClientBuilder */
  val tapirEndpoint: PublicEndpoint[Req, ContractHttpError, Resp, Any],
):
  /** Путь с версионным префиксом: "/v1/users" */
  def fullPath: String =
    (s"v${version.major}" :: pathSegments).mkString("/", "/", "")

object ContractEndpoint:

  /** POST эндпоинт с JSON телом запроса и ответа.
   *
   * URL: POST /v{major}/{pathSegments...}
   *
   * Требует `Schema[Req]` и `Schema[Resp]` для OpenAPI документации.
   * Получить через `import sttp.tapir.generic.auto.given`.
   */
  def post[Req, Resp](
    pathSegments: List[String],
    reqCodec:     ContractCodec[Req],
    respCodec:    ContractCodec[Resp],
    version:      SemanticVersion,
    description:  Option[String] = None,
  )(using Schema[Req], Schema[Resp]): ContractEndpoint[Req, Resp] =
    // Делаем Encoder/Decoder из ContractCodec доступными для jsonBody[_]
    given Encoder[Req]  = reqCodec.encoder
    given Decoder[Req]  = reqCodec.decoder
    given Encoder[Resp] = respCodec.encoder
    given Decoder[Resp] = respCodec.decoder

    val path = buildPath(version, pathSegments)

    val tapirEp = endpoint.post
      .in(path)
      .in(jsonBody[Req].description("Request body"))
      .errorOut(jsonBody[ContractHttpError])
      .out(jsonBody[Resp].description("Response body"))
      .pipe(ep => description.fold(ep)(ep.description))

    new ContractEndpoint[Req, Resp](
      version      = version,
      pathSegments = pathSegments,
      respCodec    = respCodec,
      reqCodecOpt  = Some(reqCodec),
      tapirEndpoint = tapirEp,
    )

  /** GET эндпоинт без тела запроса (Req = Unit).
   *
   * URL: GET /v{major}/{pathSegments...}
   */
  def get[Resp](
    pathSegments: List[String],
    respCodec:    ContractCodec[Resp],
    version:      SemanticVersion,
    description:  Option[String] = None,
  )(using Schema[Resp]): ContractEndpoint[Unit, Resp] =
    given Encoder[Resp] = respCodec.encoder
    given Decoder[Resp] = respCodec.decoder

    val path = buildPath(version, pathSegments)

    val tapirEp = endpoint.get
      .in(path)
      .errorOut(jsonBody[ContractHttpError])
      .out(jsonBody[Resp].description("Response body"))
      .pipe(ep => description.fold(ep)(ep.description))

    new ContractEndpoint[Unit, Resp](
      version      = version,
      pathSegments = pathSegments,
      respCodec    = respCodec,
      reqCodecOpt  = None,
      tapirEndpoint = tapirEp,
    )

  /** Строит путь с версионным префиксом из списка сегментов.
   *
   * `version=1.2.0, segments=["users","profile"]` → `/v1/users/profile`
   */
  private def buildPath(
    version:  SemanticVersion,
    segments: List[String],
  ): EndpointInput[Unit] =
    val all = s"v${version.major}" :: segments
    all.tail.foldLeft[EndpointInput[Unit]](all.head) { (acc, seg) =>
      acc / seg
    }

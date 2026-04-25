package io.compact.http

import io.circe.*
import io.circe.syntax.*
import sttp.tapir.Schema

/** HTTP ошибка для контрактных эндпоинтов.
 *
 * Кодируется как JSON тело ответа при ошибке:
 * {{{{"message": "Contract not found", "statusCode": 404}}}}
 */
final case class ContractHttpError(
  message:    String,
  statusCode: Int = 500,
)

object ContractHttpError:

  given Encoder[ContractHttpError] = Encoder.instance { e =>
    Json.obj(
      "message"    -> e.message.asJson,
      "statusCode" -> e.statusCode.asJson,
    )
  }

  given Decoder[ContractHttpError] = Decoder.instance { c =>
    for
      message    <- c.downField("message").as[String]
      statusCode <- c.downField("statusCode").as[Option[Int]].map(_.getOrElse(500))
    yield ContractHttpError(message, statusCode)
  }

  given Schema[ContractHttpError] = Schema.derived[ContractHttpError]

  def notFound(resource: String): ContractHttpError =
    ContractHttpError(s"$resource не найден", 404)

  def badRequest(reason: String): ContractHttpError =
    ContractHttpError(reason, 400)

  def internal(reason: String): ContractHttpError =
    ContractHttpError(reason, 500)

package io.compact.circe

import io.circe.*

import java.time.Instant
import java.util.UUID
import scala.util.Try

/** Встроенные кодеки для типов которые не покрывает базовый circe.
 *
 * Нужны потому что [[ContractCodec.derived]] использует `summonInline`
 * и требует Encoder/Decoder для всех типов полей case class.
 *
 * Использование:
 * {{{
 * import io.compact.circe.BuiltinCodecs.given
 * }}}
 */
object BuiltinCodecs:

  //  UUID → FieldType.Uuid

  given Encoder[UUID] =
    Encoder[String].contramap(_.toString)

  given Decoder[UUID] =
    Decoder[String].emap { s =>
      Try(UUID.fromString(s)).toEither.left.map(_.getMessage)
    }

  //  Instant → FieldType.Timestamp 
  // Хранится как ISO 8601 строка: "2024-01-15T10:30:00Z"

  given Encoder[Instant] =
    Encoder[String].contramap(_.toString)

  given Decoder[Instant] =
    Decoder[String].emap { s =>
      Try(Instant.parse(s)).toEither.left.map(_.getMessage)
    }

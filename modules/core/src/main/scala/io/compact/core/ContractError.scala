package io.compact.core

import scala.util.control.NoStackTrace

/** Иерархия ошибок библиотеки compact.
 *
 * Наследует NoStackTrace — domain ошибки не нуждаются в stacktrace,
 * это ускоряет создание и логирование.
 *
 * Использование с cats-effect:
 * {{{
 *   IO.raiseError(ContractError.ContractNotFound(id))
 *   // или
 *   EitherT.leftT[IO, Contract](ContractError.ContractNotFound(id))
 * }}}
 */
sealed abstract class ContractError(val message: String)
  extends Exception(message)
  with NoStackTrace

object ContractError:

  /** Контракт не найден в реестре */
  final case class ContractNotFound(id: ContractId)
    extends ContractError(s"Контракт не найден: ${id.show}")

  /** Версия сообщения несовместима с ожидаемой консьюмером */
  final case class IncompatibleVersion(
    contractId: ContractId,
    expected:   SemanticVersion,
    received:   SemanticVersion,
  ) extends ContractError(
    s"Несовместимая версия контракта ${contractId.show}: " +
      s"ожидалась >= ${expected.show} в рамках major ${expected.major}, " +
      s"получена ${received.show}",
  )

  /** Breaking change без явного подтверждения разработчиком */
  final case class BreakingChangeNotAcknowledged(
    contractId: ContractId,
    result:     CompatibilityResult,
  ) extends ContractError(
    s"Breaking change в контракте ${contractId.show} требует подтверждения. " +
      s"Изменений: ${result.diffs.size}. " +
      s"Запусти compactPublish с флагом --acknowledge-breaking для подтверждения.",
  )

  /** Некорректная структура контракта */
  final case class InvalidContract(
    contractId: ContractId,
    reason:     String,
  ) extends ContractError(s"Некорректный контракт ${contractId.show}: $reason")

  /** Ошибка при работе с файловым реестром */
  final case class RegistryError(
    details: String,
    cause:   Option[Throwable] = None,
  ) extends ContractError(s"Ошибка реестра: $details"):
    override def getCause: Throwable = cause.orNull

  /** Ошибка сериализации/десериализации данных по контракту */
  final case class SerializationError(
    contractId: ContractId,
    details:    String,
    cause:      Option[Throwable] = None,
  ) extends ContractError(s"Ошибка сериализации [${contractId.show}]: $details"):
    override def getCause: Throwable = cause.orNull

  /** Kafka топик не зарегистрирован для контракта */
  final case class TopicNotRegistered(contractId: ContractId)
    extends ContractError(s"Kafka топик не зарегистрирован для контракта: ${contractId.show}")

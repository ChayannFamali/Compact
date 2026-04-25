package io.compact.circe

import io.compact.core.*

/** Ошибка несовместимости case class с контрактом */
sealed trait ContractValidationError:
  def message: String

object ContractValidationError:

  /** Required поле контракта отсутствует в case class */
  final case class RequiredFieldMissing(
    fieldName:  String,
    contractId: ContractId,
  ) extends ContractValidationError:
    def message: String =
      s"Required field '$fieldName' " +
        s"(contract: ${contractId.show}) отсутствует в case class"

  /** Несовпадение обязательности поля между контрактом и case class */
  final case class OptionalityMismatch(
    fieldName:  String,
    contractId: ContractId,
    expected:   String,
    actual:     String,
  ) extends ContractValidationError:
    def message: String =
      s"Поле '$fieldName' (contract: ${contractId.show}): " +
        s"ожидалось $expected, но в case class — $actual"

/** Исключение при несовместимости case class с контрактом.
 *
 * Бросается при инициализации [[ContractCodec]] если case class
 * не соответствует контракту. Быстро провалит приложение при старте —
 * не в проде при обработке сообщений.
 */
final class ContractValidationException(
  val contract: Contract,
  val errors:   List[ContractValidationError],
) extends RuntimeException(
  s"Case class несовместим с контрактом '${contract.id.show}':\n" +
    errors.map(e => s"  • ${e.message}").mkString("\n"),
)

package io.compact.registry.codec

import io.compact.core.Contract

/** Обёртка для хранения контракта в файле реестра.
 *
 * Каждый файл контракта содержит эту обёртку.
 * [[formatVersion]] позволяет мигрировать формат без потери данных
 * при изменении структуры JSON в будущих версиях библиотеки.
 *
 * Пример JSON файла:
 * {{{
 * {
 *   "formatVersion" : 1,
 *   "contract" : {
 *     "id"      : "user-created",
 *     "name"    : "User Created",
 *     "version" : "1.0.0",
 *     "fields"  : [...],
 *     "owner"   : "user-service"
 *   }
 * }
 * }}}
 */
final case class ContractEnvelope(
  formatVersion: Int,
  contract:      Contract,
)

object ContractEnvelope:

  /** Текущая версия формата.
   * Увеличивается только при несовместимых изменениях структуры JSON.
   */
  val CurrentFormatVersion: Int = 1

  def wrap(contract: Contract): ContractEnvelope =
    ContractEnvelope(formatVersion = CurrentFormatVersion, contract = contract)

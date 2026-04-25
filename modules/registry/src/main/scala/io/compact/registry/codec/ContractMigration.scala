package io.compact.registry.codec

import io.circe.*
import io.circe.syntax.*
import io.compact.core.Contract

/** Миграции формата файлов реестра.
 *
 * При изменении структуры JSON (смена имён полей, реструктуризация)
 * [[ContractEnvelope.CurrentFormatVersion]] увеличивается и здесь
 * добавляется функция перехода из старой версии в новую.
 *
 * Цепочка миграций применяется последовательно:
 * v1 → v2 → v3 → ... → current
 *
 * Пример добавления миграции V1 → V2 в будущем:
 * {{{
 *   case 1 => migrateV1toV2(json).flatMap(migrateStep(_, fromVersion = 2))
 * }}}
 */
object ContractMigration:
  import ContractCodecs.given

  /** Читает JSON файла реестра с учётом миграций.
   *
   * Автоматически применяет все необходимые миграции от версии файла
   * до [[ContractEnvelope.CurrentFormatVersion]].
   *
   * @param rawJson JSON из файла реестра
   * @return Contract или описание ошибки
   */
  def decode(rawJson: String): Either[String, Contract] =
    io.circe.parser
      .parse(rawJson)
      .left.map(e => s"Некорректный JSON: ${e.message}")
      .flatMap(readFormatVersion)
      .flatMap { case (json, version) => migrate(json, version) }
      .flatMap(_.as[ContractEnvelope].left.map(e => s"Ошибка декодирования: ${e.message}"))
      .map(_.contract)

  /** Сериализует контракт в JSON строку для записи в файл */
  def encode(contract: Contract): String =
    ContractEnvelope.wrap(contract).asJson.spaces2

  //  Внутренняя логика 

  private def readFormatVersion(json: Json): Either[String, (Json, Int)] =
    json.hcursor
      .downField("formatVersion")
      .as[Int]
      .left.map(_ => "Отсутствует поле formatVersion")
      .map(version => (json, version))

  private[codec] def migrate(json: Json, fromVersion: Int): Either[String, Json] =
    if fromVersion == ContractEnvelope.CurrentFormatVersion then
      Right(json)
    else if fromVersion > ContractEnvelope.CurrentFormatVersion then
      Left(
        s"Версия формата $fromVersion новее текущей " +
          s"(${ContractEnvelope.CurrentFormatVersion}). " +
          s"Обнови библиотеку compact.",
      )
    else
      migrateStep(json, fromVersion)

  private def migrateStep(json: Json, fromVersion: Int): Either[String, Json] =
    fromVersion match
      // Здесь будут добавляться миграции при смене формата:
      // case 1 => migrateV1toV2(json).flatMap(migrate(_, 2))
      case other =>
        Left(s"Нет миграции из версии формата $other")

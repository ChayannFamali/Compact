package io.compact.kafka

import fs2.kafka.{Header, Headers}
import io.compact.core.*

import java.nio.charset.StandardCharsets.UTF_8
import scala.util.Try

/** Kafka заголовки для контрактных сообщений.
 *
 * Каждое сообщение несёт два заголовка:
 *  - [[ContractIdHeader]]      — идентификатор контракта
 *  - [[ContractVersionHeader]] — версия контракта в момент отправки
 *
 * Консьюмер читает эти заголовки и проверяет совместимость
 * с минимальной версией которую он поддерживает.
 */
object ContractHeaders:

  val ContractIdHeader      = "X-Compact-Contract-Id"
  val ContractVersionHeader = "X-Compact-Contract-Version"

  /** Создаёт заголовки из контракта */
  def make(contractId: ContractId, version: SemanticVersion): Headers =
    Headers(
      Header(ContractIdHeader,      contractId.value.getBytes(UTF_8)),
      Header(ContractVersionHeader, version.show.getBytes(UTF_8)),
    )

  /** Читает ContractId из заголовков. None если заголовок отсутствует или невалиден. */
  def readContractId(headers: Headers): Option[ContractId] =
    headers.toChain  
      .find(_.key == ContractIdHeader)
      .flatMap(h => Try(ContractId(new String(h.value, UTF_8))).toOption)

  /** Читает SemanticVersion из заголовков. None если заголовок отсутствует или невалиден. */
  def readVersion(headers: Headers): Option[SemanticVersion] =
    headers.toChain 
      .find(_.key == ContractVersionHeader)
      .flatMap(h => SemanticVersion.parse(new String(h.value, UTF_8)).toOption)

  /** Проверяет совместимость сообщения с ожидаемым контрактом.
   *
   * Проверки:
   *  1. Заголовок контракта присутствует
   *  2. contractId совпадает с ожидаемым
   *  3. Версия сообщения обратно совместима с minVersion консьюмера
   *
   * @param headers    Заголовки полученного сообщения
   * @param expectedId ContractId который ожидает консьюмер
   * @param minVersion Минимальная версия контракта которую принимает консьюмер
   * @return Right(receivedVersion) если совместимо, Left(ContractError) если нет
   */
  def checkCompatibility(
    headers:    Headers,
    expectedId: ContractId,
    minVersion: SemanticVersion,
  ): Either[ContractError, SemanticVersion] =
    for
      contractId <- readContractId(headers).toRight(
        ContractError.SerializationError(
          expectedId,
          s"Отсутствует заголовок '$ContractIdHeader'",
        ),
      )
      _ <- Either.cond(
        contractId == expectedId,
        (),
        ContractError.InvalidContract(
          expectedId,
          s"Ожидался контракт '${expectedId.show}', получен '${contractId.show}'",
        ),
      )
      version <- readVersion(headers).toRight(
        ContractError.SerializationError(
          expectedId,
          s"Отсутствует заголовок '$ContractVersionHeader'",
        ),
      )
      _ <- Either.cond(
        version.isBackwardCompatibleWith(minVersion),
        (),
        ContractError.IncompatibleVersion(expectedId, minVersion, version),
      )
    yield version

package io.compact.registry

import cats.effect.IO
import io.compact.core.*

/** Интерфейс реестра контрактов.
 *
 * V1 — файловая реализация [[LiveRegistry]].
 * V2 — станет отдельным сервисом, но этот интерфейс не изменится.
 *
 * Ключевой инвариант: разработчик никогда не управляет версиями вручную.
 * [[saveContract]] вычисляет версию через [[CompatibilityClassifier]] и
 * возвращает контракт с правильно выставленной версией.
 */
trait Registry:

  /** Найти контракт по ID. None если не существует. */
  def findContract(id: ContractId): IO[Option[Contract]]

  /** Список всех контрактов в реестре */
  def listContracts: IO[List[RegistryIndexEntry]]

  /** История всех сохранённых версий контракта */
  def contractHistory(id: ContractId): IO[Either[ContractError, List[Contract]]]

  /** Сохранить контракт (новый или обновлённый).
   *
   * Если контракт новый — устанавливает version 1.0.0 автоматически.
   * Если контракт существует — вычисляет следующую версию через [[CompatibilityClassifier]].
   *
   * @param contract      Контракт с полями/описанием/тегами. Версия будет переопределена.
   * @param allowBreaking Разрешить breaking change. Если false и изменение Major — вернуть Left.
   * @return Контракт с правильно выставленной версией, либо ошибка.
   */
  def saveContract(
    contract:      Contract,
    allowBreaking: Boolean = false,
  ): IO[Either[ContractError, Contract]]

  /** Зарегистрировать консьюмера контракта.
   *
   * Если консьюмер уже зарегистрирован — обновляет minimumVersion (upsert).
   *
   * @param minimumVersion Минимальная версия контракта которую принимает консьюмер.
   *                       Консьюмер будет получать только версии >= minimumVersion в том же major.
   */
  def registerConsumer(
    contractId:     ContractId,
    consumerId:     ConsumerId,
    minimumVersion: SemanticVersion,
  ): IO[Either[ContractError, Unit]]

  /** Список консьюмеров контракта */
  def getConsumers(contractId: ContractId): IO[Either[ContractError, List[ConsumerRegistration]]]

  /** Граф зависимостей между контрактами (через [[FieldType.Nested]]) */
  def getDependencyGraph: IO[DependencyGraph]

  /** Консьюмеры которые сломаются если контракт изменится с данным результатом.
   *
   * Если newResult.isBreaking = false — всегда пустой список.
   * Консьюмер сломается если новая версия НЕ обратно совместима с его minimumVersion.
   */
  def whoBreaksIf(
    contractId: ContractId,
    newResult:  CompatibilityResult,
  ): IO[List[ConsumerRegistration]]

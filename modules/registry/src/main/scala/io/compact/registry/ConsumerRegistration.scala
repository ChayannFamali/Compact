package io.compact.registry

import io.compact.core.*

/** Регистрация консьюмера контракта.
 *
 * @param consumerId      Идентификатор сервиса-консьюмера
 * @param contractId      Контракт на который подписан консьюмер
 * @param minimumVersion  Минимальная версия контракта которую консьюмер поддерживает.
 *                        Консьюмер готов принимать сообщения версии >= minimumVersion
 *                        в рамках того же major.
 * @param registeredAt    Время регистрации, ISO 8601
 */
final case class ConsumerRegistration(
  consumerId:     ConsumerId,
  contractId:     ContractId,
  minimumVersion: SemanticVersion,
  registeredAt:   String,
)

/** Файл consumers.json для одного контракта */
final case class ConsumersFile(
  contractId: ContractId,
  consumers:  List[ConsumerRegistration],
)

object ConsumersFile:
  def empty(contractId: ContractId): ConsumersFile =
    ConsumersFile(contractId, List.empty)

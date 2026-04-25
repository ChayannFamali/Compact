package io.compact.kafka

import io.compact.core.*

/** Привязка контракта к Kafka топику.
 *
 * Один контракт → один топик.
 * Один топик может быть привязан только к одному контракту.
 *
 * @param contractId  Идентификатор контракта
 * @param topicName   Имя Kafka топика
 */
final case class TopicBinding(
  contractId: ContractId,
  topicName:  String,
)

object TopicBinding:
  /** Создаёт привязку. По умолчанию имя топика = contractId */
  def apply(contractId: ContractId): TopicBinding =
    TopicBinding(contractId, contractId.value)

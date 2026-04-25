package io.compact.kafka

import fs2.kafka.CommittableOffset
import cats.effect.IO
import io.compact.core.*

/** Декодированное сообщение из Kafka с контрактными метаданными.
 *
 * @param key             Ключ сообщения
 * @param value           Декодированное значение — типобезопасный объект
 * @param contractVersion Версия контракта из заголовка сообщения
 * @param offset          Kafka offset для commit-а после обработки
 */
final case class ContractualRecord[K, A](
  key:             K,
  value:           A,
  contractVersion: SemanticVersion,
  offset:          CommittableOffset[IO],
):
  /** Подтвердить обработку сообщения */
  def commit: IO[Unit] = offset.commit

package io.compact.kafka

import cats.effect.IO
import fs2.{Pipe, Stream}
import fs2.kafka.*
import io.compact.circe.ContractCodec
import io.compact.core.*

import java.nio.charset.StandardCharsets.UTF_8

/** Типобезопасный Kafka консьюмер.
 *
 * Обёртка над fs2-kafka которая добавляет:
 *  - Проверку заголовка контракта и версии
 *  - Применение [[IncompatibilityStrategy]] при несовместимости
 *  - Десериализацию через [[ContractCodec]]
 *  - Типобезопасный [[ContractualRecord]]
 */
object ContractualConsumer:

  /** fs2 Pipe: CommittableConsumerRecord → ContractualRecord.
   *
   * Composable — используется в существующем consumer pipeline:
   * {{{
   * KafkaConsumer.stream(settings).flatMap { consumer =>
   *   Stream.eval(consumer.subscribeTo(binding.topicName)) >>
   *   consumer.stream
   *     .through(ContractualConsumer.pipe(codec, binding, minVersion, IncompatibilityStrategy.Fail))
   *     .evalMap { record =>
   *       processRecord(record.value) >> record.commit
   *     }
   * }
   * }}}
   */
  def pipe[K, A](
    codec:      ContractCodec[A],
    binding:    TopicBinding,
    minVersion: SemanticVersion,
    strategy:   IncompatibilityStrategy,
  ): Pipe[IO, CommittableConsumerRecord[IO, K, Array[Byte]], ContractualRecord[K, A]] =
    _.flatMap(processRecord(_, codec, binding, minVersion, strategy))

  /** Полный stream от ConsumerSettings до типобезопасных записей.
   *
   * Создаёт KafkaConsumer, подписывается на топик, декодирует сообщения.
   *
   * {{{
   * ContractualConsumer
   *   .stream(
   *     ConsumerSettings[IO, String, Array[Byte]]
   *       .withBootstrapServers("localhost:9092")
   *       .withGroupId("order-service"),
   *     userCodec,
   *     TopicBinding(ContractId("user-created")),
   *     minVersion    = SemanticVersion(1, 0, 0),
   *     strategy      = IncompatibilityStrategy.Skip,
   *   )
   *   .evalMap(record => process(record.value) >> record.commit)
   * }}}
   */
  def stream[K, A](
    settings:   ConsumerSettings[IO, K, Array[Byte]],
    codec:      ContractCodec[A],
    binding:    TopicBinding,
    minVersion: SemanticVersion,
    strategy:   IncompatibilityStrategy,
  ): Stream[IO, ContractualRecord[K, A]] =
    KafkaConsumer
      .stream(settings)
      .flatMap { consumer =>
        Stream.eval(consumer.subscribeTo(binding.topicName)) >>
          consumer.stream
            .through(pipe(codec, binding, minVersion, strategy))
      }

  // ── Внутренняя логика ─────────────────────────────────────────────────────

  private def processRecord[K, A](
    record:     CommittableConsumerRecord[IO, K, Array[Byte]],
    codec:      ContractCodec[A],
    binding:    TopicBinding,
    minVersion: SemanticVersion,
    strategy:   IncompatibilityStrategy,
  ): Stream[IO, ContractualRecord[K, A]] =
    ContractHeaders.checkCompatibility(record.record.headers, binding.contractId, minVersion) match
      case Left(error) =>
        handleIncompatible(error, record.record.value, strategy)

      case Right(version) =>
        codec.decodeString(new String(record.record.value, UTF_8)) match
          case Left(decodeErr) =>
            Stream.raiseError[IO](
              ContractError.SerializationError(binding.contractId, decodeErr),
            )
          case Right(value) =>
            Stream.emit(
              ContractualRecord(record.record.key, value, version, record.offset),
            )

  private def handleIncompatible[K, A](
    error:    ContractError,
    rawBytes: Array[Byte],
    strategy: IncompatibilityStrategy,
  ): Stream[IO, ContractualRecord[K, A]] =
    strategy match
      case IncompatibilityStrategy.Fail =>
        Stream.raiseError[IO](error)

      case IncompatibilityStrategy.Skip =>
        Stream.eval(
          IO.println(s"[compact-kafka] SKIP несовместимое сообщение: ${error.message}"),
        ) >> Stream.empty

      case IncompatibilityStrategy.DeadLetter(dlqTopic) =>
        // V1: логируем с указанием DLQ топика
        // V2: реальная отправка через KafkaProducer
        Stream.eval(
          IO.println(
            s"[compact-kafka] DEAD LETTER → '$dlqTopic': ${error.message} " +
              s"(${rawBytes.length} bytes)",
          ),
        ) >> Stream.empty

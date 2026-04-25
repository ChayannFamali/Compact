package io.compact.kafka

import cats.effect.{IO, Resource}
import fs2.kafka.*
import io.compact.circe.ContractCodec
import io.compact.core.*

import java.nio.charset.StandardCharsets.UTF_8

/** Типобезопасный Kafka продюсер.
 *
 * Принимает только тип A который соответствует контракту.
 * Автоматически:
 *  - Сериализует A → JSON через [[ContractCodec]]
 *  - Добавляет заголовки с contractId и версией
 *  - Отправляет в привязанный топик
 *
 * Создавать через [[ContractualProducer.resource]].
 */
final class ContractualProducer[K, A] private[kafka] (
  private val underlying: KafkaProducer[IO, K, Array[Byte]],
  val codec:              ContractCodec[A],
  val binding:            TopicBinding,
):

  /** Отправить одно сообщение. Ожидает подтверждения брокером. */
  def send(key: K, value: A): IO[Unit] =
    val record = buildRecord(key, value)
    underlying.produce(ProducerRecords.one(record)).flatten.void

  /** Отправить несколько сообщений одним батчем. */
  def sendMany(messages: List[(K, A)]): IO[Unit] =
    val records = ProducerRecords(messages.map { case (k, v) => buildRecord(k, v) })
    underlying.produce(records).flatten.void

  private def buildRecord(key: K, value: A): ProducerRecord[K, Array[Byte]] =
    val bytes   = codec.encodeString(value).getBytes(UTF_8)
    val headers = ContractHeaders.make(codec.contract.id, codec.contract.version)
    ProducerRecord(binding.topicName, key, bytes).withHeaders(headers)

object ContractualProducer:

  /** Создаёт ContractualProducer как cats-effect Resource.
   *
   * Пример:
   * {{{
   * ContractualProducer
   *   .resource(
   *     ProducerSettings[IO, String, Array[Byte]]
   *       .withBootstrapServers("localhost:9092"),
   *     userCodec,
   *     TopicBinding(ContractId("user-created")),
   *   )
   *   .use { producer =>
   *     producer.send("key-1", UserCreated(...))
   *   }
   * }}}
   */
  def resource[K, A](
    settings: ProducerSettings[IO, K, Array[Byte]],
    codec:    ContractCodec[A],
    binding:  TopicBinding,
  ): Resource[IO, ContractualProducer[K, A]] =
    KafkaProducer
      .resource(settings)
      .map(new ContractualProducer(_, codec, binding))

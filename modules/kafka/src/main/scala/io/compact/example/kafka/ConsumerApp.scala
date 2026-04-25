package io.compact.example.kafka

import cats.effect.{ExitCode, IO, IOApp}
import fs2.kafka.*
import io.compact.circe.ContractCodec
import io.compact.core.SemanticVersion
import io.compact.kafka.{ContractualConsumer, IncompatibilityStrategy}

/** Запуск: sbt "kafkaExample/runMain io.compact.example.kafka.ConsumerApp"
 *
 * Требует: docker-compose up -d (Kafka на localhost:9092)
 * Запускать ПЕРЕД ProducerApp чтобы получить все сообщения.
 */
object ConsumerApp extends IOApp:

  /** Минимальная версия контракта которую принимает консьюмер.
   *
   * Если продюсер отправит сообщение v2.x.x → стратегия Skip пропустит его.
   * Если продюсер отправит v1.5.0 (minor bump) → совместимо, обработаем.
   */
  val minVersion: SemanticVersion = SemanticVersion(1, 0, 0)

  def run(args: List[String]): IO[ExitCode] =
    val bootstrapServers = args.headOption.getOrElse("localhost:9092")

    val settings = ConsumerSettings[IO, String, Array[Byte]]
      .withBootstrapServers(bootstrapServers)
      .withGroupId("compact-example-consumer")
      .withAutoOffsetReset(AutoOffsetReset.Earliest)

    val banner =
      s"""
         |╔══════════════════════════════════════════════════════╗
         |║         compact — Kafka Consumer Example             ║
         |╚══════════════════════════════════════════════════════╝
         |  Kafka:              $bootstrapServers
         |  Топик:              ${userRegisteredBinding.topicName}
         |  Минимальная версия: ${minVersion.show}
         |  Стратегия:          Skip (несовместимые сообщения игнорируются)
         |
         |  Ожидаем сообщения... (Ctrl+C для остановки)
         |""".stripMargin

    IO.println(banner) *>
      ContractualConsumer
        .stream[String, UserRegistered](
          settings   = settings,
          codec      = summon[ContractCodec[UserRegistered]],
          binding    = userRegisteredBinding,
          minVersion = minVersion,
          strategy   = IncompatibilityStrategy.Skip,
        )
        .evalMap { record =>
          val event = record.value
          IO.println(s"─ Сообщение (contract v${record.contractVersion.show}) ─") *>
            IO.println(s"  userId:    ${event.userId}") *>
            IO.println(s"  email:     ${event.email}") *>
            IO.println(s"  createdAt: ${event.createdAt}") *>
            IO.println(s"  name:      ${event.name.getOrElse("<не указано>")}") *>
            IO.println("") *>
            record.commit
        }
        .compile
        .drain
        .handleErrorWith { err =>
          IO.println(s"\n✗ Ошибка подключения: ${err.getMessage}") *>
            IO.println("  Убедись что Kafka запущена: docker-compose up -d") *>
            IO.pure(())
        }
        .as(ExitCode.Success)

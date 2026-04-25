package io.compact.example.kafka

import cats.effect.{ExitCode, IO, IOApp}
import cats.syntax.traverse.*
import fs2.kafka.*
import io.compact.circe.ContractCodec
import io.compact.core.SemanticVersion
import io.compact.kafka.ContractualProducer
import cats.syntax.foldable.*
import java.time.Instant
import java.util.UUID

/** Запуск: sbt "kafkaExample/runMain io.compact.example.kafka.ProducerApp"
 *
 * Требует: docker-compose up -d (Kafka на localhost:9092)
 */
object ProducerApp extends IOApp:

  def run(args: List[String]): IO[ExitCode] =
    val bootstrapServers = args.headOption.getOrElse("localhost:9092")

    val settings = ProducerSettings[IO, String, Array[Byte]]
      .withBootstrapServers(bootstrapServers)

    val banner =
      s"""
         |╔══════════════════════════════════════════════════════╗
         |║         compact — Kafka Producer Example             ║
         |╚══════════════════════════════════════════════════════╝
         |  Kafka:    $bootstrapServers
         |  Топик:    ${userRegisteredBinding.topicName}
         |  Контракт: ${UserRegisteredContract.id.show} v${UserRegisteredContract.version.show}
         |  Поля:     ${UserRegisteredContract.fields.map(f => s"${f.name}(${if f.required then "req" else "opt"})").mkString(", ")}
         |""".stripMargin

    IO.println(banner) *>
      ContractualProducer
        .resource[String, UserRegistered](
          settings = settings,
          codec    = summon[ContractCodec[UserRegistered]],
          binding  = userRegisteredBinding,
        )
        .use { producer =>
          val events = List(
            UserRegistered(UUID.randomUUID(), "alice@example.com", Instant.now(), Some("Alice")),
            UserRegistered(UUID.randomUUID(), "bob@example.com",   Instant.now(), None),
            UserRegistered(UUID.randomUUID(), "carol@example.com", Instant.now(), Some("Carol")),
            UserRegistered(UUID.randomUUID(), "dave@example.com",  Instant.now(), None),
          )

          IO.println(s"Отправляем ${events.size} сообщений...\n") *>
            events.zipWithIndex.traverse_ { case (event, i) =>
              producer.send(event.userId.toString, event) *>
                IO.println(
                  s"  [${i + 1}/${events.size}] ✓ userId=${event.userId.toString.take(8)}... " +
                    s"email=${event.email} name=${event.name.getOrElse("-")}",
                )
            } *>
            IO.println("\n✓ Все сообщения отправлены.") *>
            IO.println(s"  Заголовки в каждом сообщении:") *>
            IO.println(s"    X-Compact-Contract-Id:      ${UserRegisteredContract.id.show}") *>
            IO.println(s"    X-Compact-Contract-Version: ${UserRegisteredContract.version.show}")
        }
        .handleErrorWith { err =>
          IO.println(s"\n✗ Ошибка: ${err.getMessage}") *>
            IO.println("  Убедись что Kafka запущена: docker-compose up -d") *>
            IO.pure(ExitCode.Error)
        }
        .as(ExitCode.Success)

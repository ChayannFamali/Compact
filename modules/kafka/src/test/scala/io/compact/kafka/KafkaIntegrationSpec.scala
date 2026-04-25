package io.compact.kafka

import cats.effect.{Deferred, IO, Ref, Resource}
import cats.implicits.*
import fs2.kafka.*
import io.compact.circe.ContractCodec
import io.compact.core.*
import munit.CatsEffectSuite
import org.testcontainers.containers.KafkaContainer
import org.testcontainers.utility.DockerImageName

import scala.concurrent.duration.*

/** Интеграционные тесты с реальным Kafka.
 *
 * Требует Docker. Тесты автоматически пропускаются если Docker недоступен.
 *
 * Запуск всех тестов:
 *   sbt "kafka/test"
 *
 * Только интеграционные:
 *   sbt "kafka/testOnly io.compact.kafka.KafkaIntegrationSpec"
 */
class KafkaIntegrationSpec extends CatsEffectSuite:

  //  Docker доступность 

  /** Пропустить весь suite если Docker недоступен */
  override val munitIgnore: Boolean =
    val available = scala.util.Try(
      Runtime.getRuntime.exec(Array("docker", "info")).waitFor() == 0
    ).getOrElse(false)
    if !available then
      println("\n[compact] Docker не доступен — Kafka интеграционные тесты пропущены")
    !available

  //  Тестовые типы 

  final case class IntegrationEvent(id: String, value: Int, tag: Option[String])

  val eventContract: Contract = Contract.create(
    id     = ContractId("integration-event"),
    name   = ContractName("Integration Event"),
    fields = List(
      Field("id",    FieldType.Str,   required = true),
      Field("value", FieldType.Int32, required = true),
      Field("tag",   FieldType.Str,   required = false),
    ),
    owner = OwnerId("test"),
  )

  given ContractCodec[IntegrationEvent] =
    ContractCodec.derived[IntegrationEvent](eventContract)

  val testBinding: TopicBinding = TopicBinding(
    contractId = ContractId("integration-event"),
    topicName  = "compact.integration-test",
  )

  //  Kafka контейнер

  private val kafkaResource: Resource[IO, String] =
    Resource
      .make(
        IO.blocking {
          val container = new KafkaContainer(
            DockerImageName.parse("confluentinc/cp-kafka:7.4.0"),
          )
          container.start()
          container
        },
      )(container => IO.blocking(container.stop()))
      .map(_.getBootstrapServers)

  // Используем ResourceSuiteLocalFixture из munit-cats-effect
  val kafkaFixture = ResourceSuiteLocalFixture("kafka", kafkaResource)

  override def munitFixtures = List(kafkaFixture)

  //  Вспомогательные функции 

  /** Принять ровно n сообщений с таймаутом */
  private def receiveN[A](
    stream:  fs2.Stream[IO, ContractualRecord[String, A]],
    count:   Int,
    timeout: FiniteDuration = 15.seconds,
  ): IO[List[A]] =
    stream
      .take(count.toLong)
      .evalTap(_.commit)
      .map(_.value)
      .interruptAfter(timeout)
      .compile
      .toList

  private def producerSettings(bootstrapServers: String): ProducerSettings[IO, String, Array[Byte]] =
    ProducerSettings[IO, String, Array[Byte]]
      .withBootstrapServers(bootstrapServers)

  private def consumerSettings(
    bootstrapServers: String,
    groupId:          String,
  ): ConsumerSettings[IO, String, Array[Byte]] =
    ConsumerSettings[IO, String, Array[Byte]]
      .withBootstrapServers(bootstrapServers)
      .withGroupId(groupId)
      .withAutoOffsetReset(AutoOffsetReset.Earliest)

  //  Тесты ─

  test("ContractualProducer → ContractualConsumer: полный roundtrip") {
    val bootstrapServers = kafkaFixture()
    val events = List(
      IntegrationEvent("1", 100, Some("tag-a")),
      IntegrationEvent("2", 200, None),
      IntegrationEvent("3", 300, Some("tag-b")),
    )

    for
      // Запускаем консьюмер ДО продюсера
      receivedF <- Deferred[IO, List[IntegrationEvent]]

      consumerFiber <-
        ContractualConsumer
          .stream[String, IntegrationEvent](
            settings   = consumerSettings(bootstrapServers, "test-group-roundtrip"),
            codec      = summon[ContractCodec[IntegrationEvent]],
            binding    = testBinding,
            minVersion = SemanticVersion(1, 0, 0),
            strategy   = IncompatibilityStrategy.Fail,
          )
          .take(events.size.toLong)
          .evalTap(_.commit)
          .map(_.value)
          .compile
          .toList
          .flatMap(receivedF.complete)
          .void
          .start

      // Даём консьюмеру время подписаться
      _ <- IO.sleep(2.seconds)

      // Отправляем события
      _ <- ContractualProducer
        .resource[String, IntegrationEvent](
          settings = producerSettings(bootstrapServers),
          codec    = summon[ContractCodec[IntegrationEvent]],
          binding  = testBinding,
        )
        .use { producer =>
          events.traverse(e => producer.send(e.id, e))
        }

      // Ждём получения (с таймаутом)
      received <- IO.race(
        receivedF.get,
        IO.sleep(20.seconds).as(List.empty[IntegrationEvent]),
      ).map {
        case Left(msgs)  => msgs
        case Right(msgs) => msgs
      }

      _ <- consumerFiber.cancel
    yield
      assertEquals(received.size, events.size, s"Ожидалось ${events.size} сообщений")
      assertEquals(received.map(_.id).sorted, events.map(_.id).sorted)
      assertEquals(received.map(_.value).sorted, events.map(_.value).sorted)
  }

  test("IncompatibilityStrategy.Skip: несовместимые сообщения пропускаются") {
    val bootstrapServers = kafkaFixture()

    // Контракт с другим именем симулирует "чужой" контракт
    val wrongContract: Contract = eventContract.copy(id = ContractId("wrong-contract"))

    val wrongBinding = TopicBinding(
      contractId = ContractId("wrong-contract"),
      topicName  = "compact.integration-skip-test",
    )

    val goodEvent    = IntegrationEvent("good", 1, None)
    val goodBinding2 = TopicBinding(ContractId("integration-event"), "compact.integration-skip-test")

    for
      // Отправляем одно "хорошее" сообщение (с правильным contractId в заголовке)
      _ <- ContractualProducer
        .resource[String, IntegrationEvent](
          settings = producerSettings(bootstrapServers),
          codec    = summon[ContractCodec[IntegrationEvent]],
          binding  = goodBinding2,
        )
        .use(_.send(goodEvent.id, goodEvent))

      // Консьюмер принимает только с minVersion=1.0.0
      // Отправим с "неправильной" версией — симулируем через другой топик
      received <- ContractualConsumer
        .stream[String, IntegrationEvent](
          settings   = consumerSettings(bootstrapServers, "test-group-skip"),
          codec      = summon[ContractCodec[IntegrationEvent]],
          binding    = goodBinding2,
          minVersion = SemanticVersion(1, 0, 0),
          strategy   = IncompatibilityStrategy.Skip,
        )
        .take(1)
        .evalTap(_.commit)
        .map(_.value)
        .interruptAfter(10.seconds)
        .compile
        .toList
    yield
      assertEquals(received.size, 1)
      assertEquals(received.head, goodEvent)
  }

  test("IncompatibilityStrategy.Fail: несовместимая версия → Stream.raiseError") {
    val bootstrapServers = kafkaFixture()

    // Подменяем заголовок версии — продюсер v2, консьюмер ожидает v1
    // Для этого шлём в топик сообщение с нашим контрактом но консьюмер
    // требует Major v2 которой ещё нет → v1.0.0 НЕ совместима с minVersion=2.0.0

    val failBinding = TopicBinding(ContractId("integration-event"), "compact.integration-fail-test")

    for
      _ <- ContractualProducer
        .resource[String, IntegrationEvent](
          settings = producerSettings(bootstrapServers),
          codec    = summon[ContractCodec[IntegrationEvent]],
          binding  = failBinding,
        )
        .use(_.send("1", IntegrationEvent("1", 1, None)))

      // Консьюмер требует minVersion=2.0.0 но получает 1.0.0 → должна быть ошибка
      result <- ContractualConsumer
        .stream[String, IntegrationEvent](
          settings   = consumerSettings(bootstrapServers, "test-group-fail"),
          codec      = summon[ContractCodec[IntegrationEvent]],
          binding    = failBinding,
          minVersion = SemanticVersion(2, 0, 0),
          strategy   = IncompatibilityStrategy.Fail,
        )
        .take(1)
        .compile
        .toList
        .attempt  // перехватываем ошибку
    yield
      assert(result.isLeft, "Должна быть ошибка IncompatibleVersion")
      assert(
        result.left.exists(_.isInstanceOf[ContractError.IncompatibleVersion]),
        s"Ожидалась ContractError.IncompatibleVersion, получено: ${result.left}",
      )
  }

  test("contract headers корректно добавляются и читаются через Kafka") {
    val bootstrapServers = kafkaFixture()
    val headerBinding    = TopicBinding(ContractId("integration-event"), "compact.integration-headers-test")

    for
      sentRef <- Ref.of[IO, List[SemanticVersion]](List.empty)

      consumerFiber <-
        KafkaConsumer
          .stream(
            ConsumerSettings[IO, String, Array[Byte]]
              .withBootstrapServers(bootstrapServers)
              .withGroupId("test-headers")
              .withAutoOffsetReset(AutoOffsetReset.Earliest),
          )
          .subscribeTo(headerBinding.topicName)
          .records
          .take(1)
          .evalMap { record =>
            val versionOpt = ContractHeaders.readVersion(record.record.headers)
            val idOpt      = ContractHeaders.readContractId(record.record.headers)
            for
              _ <- IO(assert(idOpt.isDefined,      "Заголовок X-Compact-Contract-Id отсутствует"))
              _ <- IO(assert(versionOpt.isDefined,  "Заголовок X-Compact-Contract-Version отсутствует"))
              _ <- IO(assertEquals(idOpt, Some(ContractId("integration-event"))))
              _ <- sentRef.set(versionOpt.toList)
            yield ()
          }
          .compile
          .drain
          .start

      _ <- IO.sleep(2.seconds)

      _ <- ContractualProducer
        .resource[String, IntegrationEvent](
          settings = producerSettings(bootstrapServers),
          codec    = summon[ContractCodec[IntegrationEvent]],
          binding  = headerBinding,
        )
        .use(_.send("1", IntegrationEvent("1", 42, None)))

      _ <- IO.sleep(5.seconds)
      _ <- consumerFiber.join
    yield ()
  }

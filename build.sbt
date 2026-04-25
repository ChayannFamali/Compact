import sbt._
import sbt.Keys._

// Версии зависимостей

val Scala3   = "3.4.2"
val Scala212 = "2.12.19"

val CatsEffectV      = "3.5.4"
val CatsCoreV        = "2.12.0"
val CirceV           = "0.14.9"
val Fs2V             = "3.10.2"
val Fs2KafkaV        = "3.5.1"
val Http4sV          = "0.23.27"
val EmberV           = Http4sV  // Добавлено для примеров
val TapirV           = "1.10.8"
val LogbackV         = "1.5.6"
val MunitV           = "1.0.0"
val MunitCatsEffectV = "2.0.0"
val ScalacheckV      = "1.17.1"

// Базовые настройки — общие для всех модулей

lazy val commonSettings = Seq(
  organization := "io.compact",
  version      := "0.1.0-SNAPSHOT",
  licenses     := List("Apache-2.0" -> url("https://www.apache.org/licenses/LICENSE-2.0")),
  homepage     := Some(url("https://github.com/yourusername/compact")),
  developers := List(
    Developer(
      id    = "yourusername",
      name  = "Your Name",
      email = "you@example.com",
      url   = url("https://github.com/yourusername"),
    )
  ),
)

// Настройки для Scala 3 модулей

lazy val scala3Settings = Seq(
  scalaVersion := Scala3,
  scalacOptions ++= Seq(
    "-deprecation",
    "-feature",
    "-unchecked",
    "-language:implicitConversions",
    "-language:higherKinds",
    "-Ykind-projector:underscores",
    // "-Werror", // включить когда base готов
  ),
  // Тестовые зависимости общие для всех Scala 3 модулей
  libraryDependencies ++= Seq(
    "org.scalameta" %% "munit"             % MunitV          % Test,
    "org.typelevel" %% "munit-cats-effect" % MunitCatsEffectV % Test,
    "org.scalacheck" %% "scalacheck"       % ScalacheckV     % Test,
  ),
  testFrameworks += new TestFramework("munit.Framework"),
  // Параллельное выполнение тестов
  Test / parallelExecution := false,
)

// Настройки для примеров

lazy val exampleSettings = Seq(
  scalaVersion   := Scala3,
  publish / skip := true,
  run / fork     := true,
  libraryDependencies += "ch.qos.logback" % "logback-classic" % LogbackV,
)

// Корневой проект — агрегирует все модули

lazy val root = project
  .in(file("."))
  .aggregate(
    core,
    registry,
    compactCirce,
    compactAvro,
    kafka,
    http,
    compactSbtPlugin,
    kafkaExample,
    httpExample,
  )
  .settings(commonSettings)
  .settings(
    name           := "compact",
    scalaVersion   := Scala3,
    publish / skip := true,
    // Запуск форматирования сразу во всех модулях
    commands += Command.command("formatAll") { state =>
      "scalafmtAll" :: "scalafmtSbt" :: state
    },
    // Проверка форматирования в CI
    commands += Command.command("checkFormatAll") { state =>
      "scalafmtCheckAll" :: "scalafmtSbtCheck" :: state
    },
  )

// compact-core — ядро, базовые абстракции
// Зависит только от cats-effect. Подключают все остальные модули.

lazy val core = project
  .in(file("modules/core"))
  .settings(commonSettings, scala3Settings)
  .settings(
    name := "compact-core",
    libraryDependencies ++= Seq(
      "org.typelevel" %% "cats-effect" % CatsEffectV,
      "org.typelevel" %% "cats-core"   % CatsCoreV,
    ),
  )

// compact-registry — файловый реестр контрактов
// Зависит от core. Читает и пишет контракты на диск через fs2-io.

lazy val registry = project
  .in(file("modules/registry"))
  .dependsOn(core)
  .settings(commonSettings, scala3Settings)
  .settings(
    name := "compact-registry",
    libraryDependencies ++= Seq(
      "org.typelevel" %% "cats-effect"   % CatsEffectV,
      "co.fs2"        %% "fs2-core"      % Fs2V,
      "co.fs2"        %% "fs2-io"        % Fs2V,
      "io.circe"      %% "circe-core"    % CirceV,
      "io.circe"      %% "circe-generic" % CirceV,
      "io.circe"      %% "circe-parser"  % CirceV,
    ),
  )

// compact-circe — автоматические кодеки из контракта

lazy val compactCirce = project
  .in(file("modules/circe"))
  .dependsOn(core)
  .settings(commonSettings, scala3Settings)
  .settings(
    name := "compact-circe",
    libraryDependencies ++= Seq(
      "io.circe" %% "circe-core"    % CirceV,
      "io.circe" %% "circe-generic" % CirceV,
      "io.circe" %% "circe-parser"  % CirceV,
    ),
  )

// compact-avro — Avro сериализация (опционально)

lazy val compactAvro = project
  .in(file("modules/avro"))
  .dependsOn(core)
  .settings(commonSettings, scala3Settings)
  .settings(
    name := "compact-avro",
    libraryDependencies ++= Seq(
      "org.apache.avro" % "avro" % "1.11.3",
    ),
  )

// compact-kafka — типобезопасный продюсер и консьюмер
// Зависит от core и circe.

lazy val kafka = project
  .in(file("modules/kafka"))
  .dependsOn(core, compactCirce)
  .settings(commonSettings, scala3Settings)
  .settings(
    name := "compact-kafka",
    libraryDependencies ++= Seq(
      "com.github.fd4s" %% "fs2-kafka" % Fs2KafkaV,
      "org.testcontainers" % "kafka"            % "1.20.3" % Test,
      "ch.qos.logback"     % "logback-classic"  % LogbackV % Test,
    ),
  )

// compact-http — tapir + http4s интеграция
// Зависит от core и circe.

lazy val http = project
  .in(file("modules/http"))
  .dependsOn(core, compactCirce)
  .settings(commonSettings, scala3Settings)
  .settings(
    name := "compact-http",
    libraryDependencies ++= Seq(
      "com.softwaremill.sttp.tapir" %% "tapir-core"              % TapirV,
      "com.softwaremill.sttp.tapir" %% "tapir-http4s-server"     % TapirV,
      "com.softwaremill.sttp.tapir" %% "tapir-sttp-client"       % TapirV,
      "com.softwaremill.sttp.tapir" %% "tapir-openapi-docs"      % TapirV,
      "com.softwaremill.sttp.tapir" %% "tapir-swagger-ui-bundle" % TapirV,
      "com.softwaremill.sttp.tapir" %% "tapir-json-circe"        % TapirV,
      "org.http4s"                  %% "http4s-ember-server"     % Http4sV,
      "org.http4s"                  %% "http4s-ember-client"     % Http4sV,
      "org.http4s"                  %% "http4s-circe"            % Http4sV,
      "org.http4s"                  %% "http4s-dsl"              % Http4sV,
      // Логирование для тестов
      "ch.qos.logback" % "logback-classic" % LogbackV % Test,
    ),
  )

// compact-sbt — SBT плагин (Scala 2.12)
// Отдельная scalaVersion — плагины работают на Scala 2.12
// НЕ наследует scala3Settings

lazy val compactSbtPlugin = project
  .in(file("modules/sbt-plugin"))
  .enablePlugins(ScriptedPlugin)
  .settings(commonSettings)
  .settings(
    name         := "compact-sbt",
    scalaVersion := Scala212,
    sbtPlugin    := true,
    libraryDependencies ++= Seq(
      "io.circe"      %% "circe-core"   % "0.14.9",
      "io.circe"      %% "circe-parser" % "0.14.9",
      "org.scalameta" %% "munit"        % "0.7.29" % Test,
    ),
    testFrameworks += new TestFramework("munit.Framework"),
    scriptedLaunchOpts       += s"-Dplugin.version=${version.value}",  
    scriptedBufferLog         := false,                                
  )

// Примеры использования

// Kafka пример
lazy val kafkaExample = project
  .in(file("examples/kafka-example"))
  .dependsOn(core, compactCirce, kafka)
  .settings(commonSettings, scala3Settings, exampleSettings)
  .settings(name := "kafka-example")

// HTTP пример
lazy val httpExample = project
  .in(file("examples/http-example"))
  .dependsOn(core, compactCirce, http)
  .settings(commonSettings, scala3Settings, exampleSettings)
  .settings(
    name := "http-example",
    libraryDependencies ++= Seq(
      "org.http4s" %% "http4s-ember-server" % Http4sV,
      "org.http4s" %% "http4s-ember-client" % Http4sV,
    ),
  )

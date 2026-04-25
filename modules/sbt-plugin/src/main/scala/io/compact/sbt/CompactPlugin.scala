package io.compact.sbt

import sbt._
import sbt.Keys._

/** compact SBT плагин — CI интеграция для контрактного реестра.
 *
 * Подключение:
 * {{{
 * // project/plugins.sbt
 * addSbtPlugin("io.compact" % "compact-sbt" % "0.1.0")
 *
 * // build.sbt
 * enablePlugins(CompactPlugin)
 * compactRegistryPath := baseDirectory.value / "contracts"
 * }}}
 *
 * Задачи:
 *  - [[autoImport.compactCheck]]   — проверить реестр (запускать в CI на каждый PR)
 *  - [[autoImport.compactPublish]] — обновить реестр (запускать при мерже в main)
 *  - [[autoImport.compactReport]]  — показать состояние реестра
 *
 * V2 roadmap:
 *  - Автоматическое обнаружение изменений Contract val в Scala 3 коде
 *  - Auto-bump версий при изменении контракта
 *  - Compile-time проверка через inline macros
 */
object CompactPlugin extends AutoPlugin {

  override def trigger: PluginTrigger = noTrigger

  object autoImport {

    val compactRegistryPath = settingKey[File](
      "Путь к директории реестра контрактов. Default: baseDirectory / contracts",
    )

    val compactFailOnBreaking = settingKey[Boolean](
      "Упасть при обнаружении blocking проблем. Default: true",
    )

    val compactCheck = taskKey[Unit](
      """Проверить реестр контрактов.
        |
        |Проверяет:
        |  1. Все контракты из индекса имеют валидный contract.json
        |  2. Все консьюмеры совместимы с текущими версиями контрактов
        |  3. Нет orphan consumers (ссылок на несуществующие контракты)
        |
        |Запускать в CI на каждый pull request.""".stripMargin,
    )

    val compactPublish = taskKey[Unit](
      """Зафиксировать контракты в реестре.
        |
        |V1: валидирует реестр и выводит текущее состояние.
        |V2: автоматически обнаруживает изменения Contract val в Scala 3 коде,
        |    bump-ает версии, обновляет registry.json.
        |
        |Запускать при мерже в main.""".stripMargin,
    )

    val compactReport = taskKey[Unit](
      "Показать полный отчёт о контрактах, версиях и консьюмерах.",
    )
  }

  import autoImport._

  override def projectSettings: Seq[Setting[_]] = Seq(

    compactRegistryPath   := baseDirectory.value / "contracts",
    compactFailOnBreaking := true,

    // ── compactCheck ────────────────────────────────────────────────────────

    compactCheck := {
      val log      = streams.value.log
      val registry = compactRegistryPath.value
      val failOn   = compactFailOnBreaking.value

      log.info(s"[compact] Проверяем реестр: ${registry.getAbsolutePath}")

      RegistryReader.readRegistryIndex(registry) match {
        case Left(err) =>
          log.error(s"[compact] Ошибка чтения registry.json: $err")
          if (failOn) sys.error(s"compact: ошибка реестра")

        case Right(index) if index.contracts.isEmpty =>
          log.info("[compact] Реестр пуст — нет контрактов для проверки.")
          log.info("[compact] Добавь контракты через LiveRegistry.saveContract")

        case Right(index) =>
          log.info(s"[compact] Контрактов в реестре: ${index.contracts.size}")

          // Читаем все контракты
          val contractResults = index.contracts.map { e =>
            e.id -> RegistryReader.readContract(registry, e.id)
          }
          val readErrors = contractResults.collect { case (id, Left(err)) => s"  $id: $err" }

          if (readErrors.nonEmpty) {
            log.error("[compact] Ошибки чтения контрактов:")
            readErrors.foreach(log.error(_))
            if (failOn) sys.error("compact: ошибки чтения контрактов")
          } else {
            val contracts = contractResults.collect { case (id, Right(c)) => id -> c }.toMap

            // Читаем всех консьюмеров
            val consumers = index.contracts.flatMap { e =>
              RegistryReader.readConsumers(registry, e.id).toOption.map(e.id -> _)
            }.toMap

            // Проверяем совместимость
            val issues = CompatChecker.checkRegistry(index, contracts, consumers)
            val report = ReportGenerator.issues(issues)

            if (issues.isEmpty) {
              log.success(s"[compact] ✓ Реестр валиден. Контрактов: ${index.contracts.size}")
            } else {
              val blockers = issues.count(_.isBlocker)
              log.info(s"[compact]\n$report")
              if (blockers > 0 && failOn)
                sys.error(s"compact: $blockers критических проблем в реестре")
              else if (blockers > 0)
                log.warn(s"[compact] $blockers критических проблем (compactFailOnBreaking = false)")
            }
          }
      }
    },

    // ── compactPublish ───────────────────────────────────────────────────────

    compactPublish := {
      val log      = streams.value.log
      val registry = compactRegistryPath.value

      log.info(s"[compact] Публикация контрактов...")
      log.info(s"[compact] Реестр: ${registry.getAbsolutePath}")

      RegistryReader.readRegistryIndex(registry) match {
        case Left(err) =>
          log.error(s"[compact] Ошибка чтения реестра: $err")

        case Right(index) =>
          val contractResults = index.contracts.map { e =>
            e.id -> RegistryReader.readContract(registry, e.id)
          }
          val valid  = contractResults.count(_._2.isRight)
          val errors = contractResults.count(_._2.isLeft)

          log.info(s"[compact] Контрактов: ${index.contracts.size} (валидных: $valid, ошибок: $errors)")

          if (errors > 0) {
            contractResults.collect { case (id, Left(err)) =>
              log.error(s"[compact]   ✗ $id: $err")
            }
          }

          log.info("[compact] ─────────────────────────────────────────────")
          log.info("[compact] V1: используй LiveRegistry.saveContract из compact-registry")
          log.info("[compact]     для регистрации и обновления контрактов.")
          log.info("[compact] V2: автоматическое обнаружение изменений Contract val")
          log.info("[compact]     в Scala 3 коде и auto-bump версий.")
          log.info("[compact] ─────────────────────────────────────────────")

          if (errors == 0)
            log.success(s"[compact] ✓ Реестр валиден. Готов к публикации.")
      }
    },

    // ── compactReport ────────────────────────────────────────────────────────

    compactReport := {
      val log      = streams.value.log
      val registry = compactRegistryPath.value

      RegistryReader.readRegistryIndex(registry) match {
        case Left(err) =>
          log.error(s"[compact] Ошибка чтения реестра: $err")

        case Right(index) =>
          val contracts = index.contracts.flatMap { e =>
            RegistryReader.readContract(registry, e.id).toOption.map(e.id -> _)
          }.toMap

          val consumers = index.contracts.flatMap { e =>
            RegistryReader.readConsumers(registry, e.id).toOption.map(e.id -> _)
          }.toMap

          log.info(ReportGenerator.registry(index, contracts, consumers))
      }
    },
  )
}

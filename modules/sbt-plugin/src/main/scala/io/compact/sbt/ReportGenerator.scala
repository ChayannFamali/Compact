package io.compact.sbt

/** Генерирует человекочитаемые отчёты о состоянии реестра */
object ReportGenerator {

  def registry(
    index:     RegistryIndex,
    contracts: Map[String, ContractMeta],
    consumers: Map[String, ConsumersFile],
  ): String = {
    val sb = new StringBuilder
    sb.append("\n╔══════════════════════════════════════════════════════╗\n")
    sb.append(  "║         compact — Registry Report                   ║\n")
    sb.append(  "╚══════════════════════════════════════════════════════╝\n\n")

    if (index.contracts.isEmpty) {
      sb.append("  Реестр пуст.\n")
      sb.append("  Используй LiveRegistry.saveContract для регистрации контрактов.\n")
    } else {
      sb.append(s"  Контрактов: ${index.contracts.size}\n\n")

      index.contracts.foreach { entry =>
        val contract     = contracts.get(entry.id)
        val consumerList = consumers.get(entry.id).map(_.consumers).getOrElse(Nil)

        sb.append(s"  ┌─ ${entry.id}\n")
        sb.append(s"  │  Версия:     ${entry.currentVersion.show}\n")
        sb.append(s"  │  Владелец:   ${entry.owner}\n")
        sb.append(s"  │  Консьюмеры: ${consumerList.size}\n")

        contract.foreach { c =>
          c.description.foreach(d => sb.append(s"  │  Описание:   $d\n"))
          if (c.tags.nonEmpty)
            sb.append(s"  │  Теги:       ${c.tags.mkString(", ")}\n")
          if (c.fields.nonEmpty) {
            sb.append(s"  │  Поля (${c.fields.size}):\n")
            c.fields.foreach { f =>
              val req = if (f.required) "required" else "optional"
              val dep = if (f.deprecated) " [DEPRECATED]" else ""
              sb.append(s"  │    • ${f.name}: ${f.fieldType} ($req)$dep\n")
            }
          }
        }

        if (consumerList.nonEmpty) {
          sb.append(s"  │  Консьюмеры:\n")
          consumerList.foreach { consumer =>
            val compat = contract.map { c =>
              if (c.version.isBackwardCompatibleWith(consumer.minimumVersion)) "✓"
              else "  НЕСОВМЕСТИМО"
            }.getOrElse("?")
            sb.append(
              s"  │    • ${consumer.consumerId} (>= ${consumer.minimumVersion.show}) $compat\n"
            )
          }
        }

        sb.append(s"  └─\n\n")
      }
    }

    sb.toString()
  }

  def issues(list: List[RegistryIssue]): String = {
    if (list.isEmpty) {
      "  ✓ Проблем не обнаружено"
    } else {
      val sb = new StringBuilder
      val blockers = list.count(_.isBlocker)
      sb.append(s"  Проблем: ${list.size} ($blockers критических)\n\n")
      list.foreach { issue =>
        val icon = if (issue.isBlocker) "  ✗" else "  ⚠"
        sb.append(s"$icon ${issue.message}\n")
      }
      sb.toString()
    }
  }

  def changes(contractId: String, level: ChangeLevel, fieldChanges: List[FieldChange]): String = {
    val sb = new StringBuilder
    sb.append(s"  Контракт: $contractId\n")
    sb.append(s"  Изменение: ${ChangeLevel.label(level)}\n")
    if (fieldChanges.nonEmpty) {
      sb.append(s"  Изменений полей: ${fieldChanges.size}\n")
      fieldChanges.foreach { fc =>
        val icon = fc.level match {
          case MajorChange => "  ✗"
          case MinorChange => "  +"
          case PatchChange => "  ·"
        }
        sb.append(s"$icon ${fc.description}\n")
      }
    }
    sb.toString()
  }
}

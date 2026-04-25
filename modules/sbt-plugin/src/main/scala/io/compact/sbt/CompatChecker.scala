package io.compact.sbt

/** Проверяет совместимость контрактов и консьюмеров.
 *
 * Scala 2.12 реализация логики из compact-core.CompatibilityClassifier.
 * Дублирование необходимо — sbt плагин не может зависеть от Scala 3 модулей.
 */
object CompatChecker {

  // ── Проверка реестра ───────────────────────────────────────────────────────

  /** Проверяет весь реестр на наличие проблем совместимости.
   *
   * Проверки:
   *  1. Все контракты из индекса имеют contract.json
   *  2. Все консьюмеры совместимы с текущими версиями контрактов
   *  3. Все consumers.json ссылаются на существующие контракты
   */
  def checkRegistry(
    index:     RegistryIndex,
    contracts: Map[String, ContractMeta],
    consumers: Map[String, ConsumersFile],
  ): List[RegistryIssue] = {

    // 1. Файлы контрактов существуют
    val missingFiles = index.contracts.collect {
      case entry if !contracts.contains(entry.id) => MissingContractFile(entry.id)
    }

    // 2. Консьюмеры совместимы с текущими версиями
    val consumerIssues = consumers.values.toList.flatMap { cf =>
      contracts.get(cf.contractId) match {
        case None =>
          List(OrphanConsumer(cf.contractId))
        case Some(contract) =>
          cf.consumers.collect {
            case consumer
              if !contract.version.isBackwardCompatibleWith(consumer.minimumVersion) =>
              ConsumerIncompatible(
                contractId     = cf.contractId,
                consumerId     = consumer.consumerId,
                minVersion     = consumer.minimumVersion.show,
                currentVersion = contract.version.show,
              )
          }
      }
    }

    missingFiles ++ consumerIssues
  }

  // ── Классификация изменений полей ─────────────────────────────────────────

  /** Классифицирует изменения между двумя версиями контракта.
   *
   * Возвращает: (общий уровень изменений, список конкретных изменений)
   */
  def classifyChange(
    before: ContractMeta,
    after:  ContractMeta,
  ): (ChangeLevel, List[FieldChange]) = {
    val fieldChanges = classifyFieldChanges(before.fields, after.fields)
    val level =
      if (fieldChanges.isEmpty) PatchChange
      else fieldChanges.map(_.level).maxBy(_.ordinal)
    (level, fieldChanges)
  }

  def classifyFieldChanges(
    before: List[FieldMeta],
    after:  List[FieldMeta],
  ): List[FieldChange] = {
    val beforeMap = before.map(f => f.name -> f).toMap
    val afterMap  = after.map(f => f.name  -> f).toMap

    val removed = before
      .filterNot(f => afterMap.contains(f.name))
      .map(f => FieldChange(f.name, MajorChange, s"Поле '${f.name}' удалено"))

    val added = after
      .filterNot(f => beforeMap.contains(f.name))
      .map { f =>
        if (f.required)
          FieldChange(f.name, MajorChange, s"Добавлено обязательное поле '${f.name}'")
        else
          FieldChange(f.name, MinorChange, s"Добавлено опциональное поле '${f.name}'")
      }

    val modified = before
      .filter(f => afterMap.contains(f.name))
      .flatMap { bf =>
        val af = afterMap(bf.name)
        List(
          if (bf.fieldType != af.fieldType)
            Some(FieldChange(
              bf.name, MajorChange,
              s"Тип поля '${bf.name}' изменён: ${bf.fieldType} → ${af.fieldType}",
            ))
          else None,

          if (bf.required && !af.required)
            Some(FieldChange(bf.name, MinorChange, s"Поле '${bf.name}': required → optional"))
          else if (!bf.required && af.required)
            Some(FieldChange(bf.name, MajorChange, s"Поле '${bf.name}': optional → required"))
          else None,

          if (!bf.deprecated && af.deprecated)
            Some(FieldChange(bf.name, PatchChange, s"Поле '${bf.name}' помечено deprecated"))
          else None,
        ).flatten
      }

    removed ++ added ++ modified
  }
}

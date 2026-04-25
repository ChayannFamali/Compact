package io.compact.core

/** Классификатор изменений между двумя версиями контракта.
 *
 * Принимает два [[Contract]] и возвращает [[CompatibilityResult]].
 * Рантайм реализация (V1). Compile-time через macros — V2.
 *
 * Таблица правил:
 * {{{
 *   Изменение                                Severity
 *   
 *   Метаданные (description)                 Patch
 *   Теги (tags)                              Patch
 *   Поле помечено deprecated                 Patch
 *   
 *   Добавлено необязательное поле            Minor
 *   required → optional                      Minor
 *   Union расширен (добавлен вариант)        Minor
 *   Тип обёрнут в Union с исходным типом     Minor
 *   
 *   Добавлено обязательное поле              Major 
 *   Поле удалено                             Major 
 *   Тип поля изменён (кроме расширения Union)Major 
 *   optional → required                      Major 
 *   Union сужен (убран вариант)              Major 
 * }}}
 */
object CompatibilityClassifier:

  // Внутренняя классификация отдельного изменения
  private enum Severity:
    case Patch, Minor, Major

  private def severityOrdinal(s: Severity): Int = s match
    case Severity.Patch => 0
    case Severity.Minor => 1
    case Severity.Major => 2

  //  Публичный API ─

  /** Классифицирует изменения между двумя версиями контракта.
   *
   * @param before Предыдущая версия контракта (из реестра)
   * @param after  Новая версия контракта (текущий код)
   * @return Результат классификации
   */
  def classify(before: Contract, after: Contract): CompatibilityResult =
    val diffs = computeDiffs(before, after)
    if diffs.isEmpty then
      CompatibilityResult.Identical
    else
      val worstSeverity = diffs.map(diffSeverity).maxBy(severityOrdinal)
      worstSeverity match
        case Severity.Patch => CompatibilityResult.Patch(diffs)
        case Severity.Minor => CompatibilityResult.Minor(diffs)
        case Severity.Major => CompatibilityResult.Major(diffs)

  //  Вычисление списка изменений ─

  private def computeDiffs(before: Contract, after: Contract): List[VersionDiff] =
    fieldDiffs(before.fields, after.fields) ++ metadataDiffs(before, after)

  private def fieldDiffs(before: List[Field], after: List[Field]): List[VersionDiff] =
    val beforeMap = before.map(f => f.name -> f).toMap
    val afterMap  = after.map(f => f.name  -> f).toMap

    val removed: List[VersionDiff] =
      before
        .filterNot(f => afterMap.contains(f.name))
        .map(f => VersionDiff.FieldRemoved(f.name, f))

    val added: List[VersionDiff] =
      after
        .filterNot(f => beforeMap.contains(f.name))
        .map(f => VersionDiff.FieldAdded(f))

    val changed: List[VersionDiff] =
      before
        .filter(f => afterMap.contains(f.name))
        .flatMap(bf => detectFieldChanges(bf, afterMap(bf.name)))

    removed ++ added ++ changed

  private def detectFieldChanges(before: Field, after: Field): List[VersionDiff] =
    val typeChange: List[VersionDiff] =
      if before.fieldType != after.fieldType then
        List(VersionDiff.FieldTypeChanged(before.name, before.fieldType, after.fieldType))
      else List.empty

    val requirednessChange: List[VersionDiff] =
      if before.required != after.required then
        List(VersionDiff.FieldRequirednessChanged(before.name, before.required, after.required))
      else List.empty

    val deprecationChange: List[VersionDiff] =
      if !before.deprecated && after.deprecated then
        List(VersionDiff.FieldDeprecated(before.name))
      else List.empty

    typeChange ++ requirednessChange ++ deprecationChange

  private def metadataDiffs(before: Contract, after: Contract): List[VersionDiff] =
    val descriptionDiff: List[VersionDiff] =
      if before.description != after.description then
        List(VersionDiff.MetadataChanged(before.description, after.description))
      else List.empty

    val tagsDiff: List[VersionDiff] =
      val added   = after.tags.filterNot(before.tags.contains)
      val removed = before.tags.filterNot(after.tags.contains)
      if added.nonEmpty || removed.nonEmpty then
        List(VersionDiff.TagsChanged(added, removed))
      else List.empty

    descriptionDiff ++ tagsDiff

  //  Классификация отдельного изменения 

  private def diffSeverity(diff: VersionDiff): Severity = diff match
    case VersionDiff.FieldAdded(field) =>
      if field.required then Severity.Major else Severity.Minor

    case VersionDiff.FieldRemoved(_, _) =>
      Severity.Major

    case VersionDiff.FieldTypeChanged(_, from, to) =>
      classifyTypeChange(from, to)

    case VersionDiff.FieldRequirednessChanged(_, wasRequired, _) =>
      // required → optional: безопасно, консьюмер просто получит отсутствующее поле
      // optional → required: breaking, продюсер теперь требует поле которого не было
      if wasRequired then Severity.Minor else Severity.Major

    case VersionDiff.FieldDeprecated(_)        => Severity.Patch
    case VersionDiff.MetadataChanged(_, _)     => Severity.Patch
    case VersionDiff.TagsChanged(_, _)         => Severity.Patch

  private def classifyTypeChange(from: FieldType, to: FieldType): Severity =
    (from, to) match
      // Union расширен — Minor (существующие варианты остались)
      case (FieldType.Union(beforeVariants), FieldType.Union(afterVariants)) =>
        val removed = beforeVariants.filterNot(afterVariants.contains)
        if removed.nonEmpty then Severity.Major else Severity.Minor

      // Тип обёрнут в Union содержащий исходный тип — Minor
      // Например: Str → Union(Str, Int32)
      case (t, FieldType.Union(variants)) if variants.contains(t) =>
        Severity.Minor

      // Любое другое изменение типа — Major
      case _ =>
        Severity.Major

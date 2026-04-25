package io.compact.core

/** Результат классификации изменений между двумя версиями контракта.
 *
 * Используется для:
 *  - Автоматического bump-а версии ([[nextVersion]])
 *  - Решения останавливать ли сборку (только [[Major]])
 *  - Отчёта в CI о том что именно изменилось
 */
enum CompatibilityResult:

  /** Контракты идентичны — никаких изменений нет. */
  case Identical

  /** Patch: изменились только метаданные, документация, теги.
   * Автоматический bump: X.Y.Z → X.Y.(Z+1)
   */
  case Patch(diffs: List[VersionDiff])

  /** Minor: добавлены обратно совместимые изменения.
   * Все существующие консьюмеры продолжают работать без изменений.
   * Автоматический bump: X.Y.Z → X.(Y+1).0
   */
  case Minor(diffs: List[VersionDiff])

  /** Major: breaking changes.
   * Один или несколько консьюмеров сломаются при обновлении продюсера.
   * Сборка останавливается до явного подтверждения разработчиком.
   * Автоматический bump: X.Y.Z → (X+1).0.0
   */
  case Major(diffs: List[VersionDiff])

object CompatibilityResult:

  extension (result: CompatibilityResult)

    def diffs: List[VersionDiff] = result match
      case Identical    => List.empty
      case Patch(diffs) => diffs
      case Minor(diffs) => diffs
      case Major(diffs) => diffs

    def isBreaking: Boolean = result match
      case Major(_) => true
      case _        => false

    def isSafe: Boolean = !result.isBreaking

    /** Применяет результат классификации к версии — возвращает следующую версию */
    def nextVersion(current: SemanticVersion): SemanticVersion = result match
      case Identical => current
      case Patch(_)  => current.bumpPatch
      case Minor(_)  => current.bumpMinor
      case Major(_)  => current.bumpMajor

    def show: String = result match
      case Identical    => "Identical — изменений нет"
      case Patch(diffs) => s"Patch — ${diffs.size} изменений (только метаданные)"
      case Minor(diffs) => s"Minor — ${diffs.size} изменений (обратно совместимые)"
      case Major(diffs) => s"Major   — ${diffs.size} breaking изменений"

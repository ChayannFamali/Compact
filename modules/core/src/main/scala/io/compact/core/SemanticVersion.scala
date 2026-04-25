package io.compact.core
import scala.math.Ordering.Implicits.infixOrderingOps
/** Семантическая версия контракта.
 *
 * Разработчик никогда не выставляет версию вручную —
 * это делает [[CompatibilityClassifier]] на основе анализа изменений.
 *
 * Правило bump-а:
 *  - Patch: изменились только метаданные / документация
 *  - Minor: добавлены обратно совместимые изменения
 *  - Major: breaking changes
 */
final case class SemanticVersion(major: Int, minor: Int, patch: Int):
  require(major >= 0, s"major должен быть >= 0, получено: $major")
  require(minor >= 0, s"minor должен быть >= 0, получено: $minor")
  require(patch >= 0, s"patch должен быть >= 0, получено: $patch")

  def bumpMajor: SemanticVersion = SemanticVersion(major + 1, 0, 0)
  def bumpMinor: SemanticVersion = SemanticVersion(major, minor + 1, 0)
  def bumpPatch: SemanticVersion = SemanticVersion(major, minor, patch + 1)

  /** Строковое представление: "1.2.3" */
  def show: String = s"$major.$minor.$patch"

  /** Версия A совместима с версией B если одинаковый major и A >= B.
   *
   * Консьюмер работающий на v1.2.0 может читать сообщения от продюсера на v1.3.0,
   * но не от v2.0.0.
   */
  def isBackwardCompatibleWith(other: SemanticVersion): Boolean =
    major == other.major && this >= other

object SemanticVersion:

  /** Начальная версия нового контракта */
  val Initial: SemanticVersion = SemanticVersion(1, 0, 0)

  given Ordering[SemanticVersion] with
    def compare(a: SemanticVersion, b: SemanticVersion): Int =
      val ma = a.major.compareTo(b.major)
      if ma != 0 then ma
      else
        val mi = a.minor.compareTo(b.minor)
        if mi != 0 then mi
        else a.patch.compareTo(b.patch)

  /** Разбирает строку "1.2.3" в SemanticVersion */
  def parse(s: String): Either[String, SemanticVersion] =
    s.split("\\.").toList match
      case List(major, minor, patch) =>
        for
          ma <- major.toIntOption.toRight(s"Некорректный major: '$major'")
          mi <- minor.toIntOption.toRight(s"Некорректный minor: '$minor'")
          pa <- patch.toIntOption.toRight(s"Некорректный patch: '$patch'")
          _  <- Either.cond(ma >= 0 && mi >= 0 && pa >= 0, (), "Компоненты версии не могут быть отрицательными")
        yield SemanticVersion(ma, mi, pa)
      case _ =>
        Left(s"Некорректный формат версии: '$s' (ожидается major.minor.patch)")

  def parseUnsafe(s: String): SemanticVersion =
    parse(s).fold(err => throw new IllegalArgumentException(err), identity)

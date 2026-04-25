package io.compact.sbt

// Модели данных (Scala 2.12, без зависимости от compact-core)
// Зеркалят типы из compact-core для использования в sbt плагине

/** Семантическая версия — Scala 2.12 совместимая реализация */
final case class SemVer(major: Int, minor: Int, patch: Int) extends Ordered[SemVer] {

  def compare(that: SemVer): Int = {
    val mc = major.compareTo(that.major)
    if (mc != 0) mc
    else {
      val nc = minor.compareTo(that.minor)
      if (nc != 0) nc else patch.compareTo(that.patch)
    }
  }

  def show: String = s"$major.$minor.$patch"

  /** Продюсер версии A совместим с консьюмером ожидающим версию B если
   *  A.major == B.major && A >= B
   */
  def isBackwardCompatibleWith(other: SemVer): Boolean =
    major == other.major && this >= other
}

object SemVer {
  val Initial: SemVer = SemVer(1, 0, 0)

  def parse(s: String): Either[String, SemVer] =
    s.split("\\.").toList match {
      case List(ma, mi, pa) =>
        try Right(SemVer(ma.toInt, mi.toInt, pa.toInt))
        catch { case _: NumberFormatException => Left(s"Некорректная версия: '$s'") }
      case _ =>
        Left(s"Некорректный формат: '$s' (ожидается major.minor.patch)")
    }
}

//  Поля контракта 

/** Метаданные поля контракта (читаются из JSON) */
final case class FieldMeta(
  name:       String,
  fieldType:  String,   // "str", "int32", "uuid", "collection", ...
  required:   Boolean,
  deprecated: Boolean = false,
)

//  Контракт 

/** Метаданные контракта (читаются из contract.json) */
final case class ContractMeta(
  id:          String,
  name:        String,
  version:     SemVer,
  owner:       String,
  fields:      List[FieldMeta],
  description: Option[String] = None,
  tags:        List[String]   = Nil,
)

//  Консьюмеры 

/** Регистрация одного консьюмера (из consumers.json) */
final case class ConsumerMeta(
  consumerId:     String,
  contractId:     String,
  minimumVersion: SemVer,
  registeredAt:   String,
)

/** Файл consumers.json для одного контракта */
final case class ConsumersFile(
  contractId: String,
  consumers:  List[ConsumerMeta],
)

//  Реестр 

/** Одна запись в registry.json */
final case class RegistryIndexEntry(
  id:             String,
  name:           String,
  currentVersion: SemVer,
  owner:          String,
)

/** Файл registry.json — индекс всех контрактов */
final case class RegistryIndex(contracts: List[RegistryIndexEntry])

object RegistryIndex {
  val empty: RegistryIndex = RegistryIndex(Nil)
}

//  Классификация изменений ─

/** Тяжесть изменения поля */
sealed trait ChangeLevel { def ordinal: Int }
case object PatchChange extends ChangeLevel { val ordinal = 0 }
case object MinorChange extends ChangeLevel { val ordinal = 1 }
case object MajorChange extends ChangeLevel { val ordinal = 2 }

object ChangeLevel {
  def max(a: ChangeLevel, b: ChangeLevel): ChangeLevel =
    if (a.ordinal >= b.ordinal) a else b

  def label(level: ChangeLevel): String = level match {
    case PatchChange => "Patch"
    case MinorChange => "Minor"
    case MajorChange => "Major "
  }
}

/** Конкретное изменение поля */
final case class FieldChange(
  fieldName:   String,
  level:       ChangeLevel,
  description: String,
)

//  Проблемы в реестре 

/** Проблема обнаруженная при проверке реестра */
sealed trait RegistryIssue {
  def message:   String
  def isBlocker: Boolean
}

final case class ConsumerIncompatible(
  contractId:     String,
  consumerId:     String,
  minVersion:     String,
  currentVersion: String,
) extends RegistryIssue {
  val message: String =
    s"Консьюмер '$consumerId' → контракт '$contractId': " +
      s"минимальная версия $minVersion несовместима с текущей $currentVersion"
  val isBlocker = true
}

final case class MissingContractFile(contractId: String) extends RegistryIssue {
  val message  = s"Файл contract.json для '$contractId' не найден"
  val isBlocker = true
}

final case class InvalidRegistryJson(path: String, error: String) extends RegistryIssue {
  val message  = s"Некорректный JSON в '$path': $error"
  val isBlocker = true
}

final case class OrphanConsumer(contractId: String) extends RegistryIssue {
  val message  = s"consumers.json ссылается на несуществующий контракт '$contractId'"
  val isBlocker = true
}

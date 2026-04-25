package io.compact.core

/** Описание конкретного изменения между двумя версиями контракта.
 *
 * [[CompatibilityClassifier]] разбивает сравнение на список VersionDiff,
 * затем классифицирует каждый и берёт наихудший результат.
 */
enum VersionDiff:

  /** Добавлено новое поле.
   * Если required = true  → Major (продюсер ожидает поле которого раньше не было)
   * Если required = false → Minor (существующие консьюмеры просто игнорируют поле)
   */
  case FieldAdded(field: Field)

  /** Поле удалено.
   * Всегда Major — консьюмеры которые читали это поле сломаются.
   */
  case FieldRemoved(fieldName: String, removedField: Field)

  /** Изменился тип поля.
   * Почти всегда Major.
   * Исключение: расширение Union (добавлен вариант) → Minor.
   * Исключение: тип обёрнут в Union включающий исходный тип → Minor.
   */
  case FieldTypeChanged(fieldName: String, from: FieldType, to: FieldType)

  /** Изменилась обязательность поля.
   * required → optional  (wasRequired = true)  → Minor
   * optional → required  (wasRequired = false) → Major
   */
  case FieldRequirednessChanged(
    fieldName:    String,
    wasRequired:  Boolean,
    isNowRequired: Boolean,
  )

  /** Поле помечено как deprecated (deprecated стал true).
   * Всегда Patch — поле ещё работает, просто объявлено устаревшим.
   */
  case FieldDeprecated(fieldName: String)

  /** Изменились метаданные контракта (description).
   * Всегда Patch.
   */
  case MetadataChanged(oldDescription: Option[String], newDescription: Option[String])

  /** Изменились теги контракта.
   * Всегда Patch.
   */
  case TagsChanged(added: List[String], removed: List[String])

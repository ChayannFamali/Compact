package io.compact.core

/** Поле контракта.
 *
 * @param name        Имя поля. Стабильный идентификатор —
 *                    переименование это Major изменение.
 * @param fieldType   Тип поля, см. [[FieldType]]
 * @param required    Обязательность. false = поле может отсутствовать в сообщении.
 *                    Изменение required → не required: Minor.
 *                    Изменение не required → required: Major.
 * @param description Документация поля. Изменение — Patch.
 * @param deprecated  Поле устаревшее но ещё поддерживается.
 *                    Выставить deprecated = true: Patch.
 *                    Удалить deprecated поле: Major.
 */
final case class Field(
  name:        String,
  fieldType:   FieldType,
  required:    Boolean,
  description: Option[String] = None,
  deprecated:  Boolean        = false,
)

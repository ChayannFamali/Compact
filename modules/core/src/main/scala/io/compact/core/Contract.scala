package io.compact.core

/** Контракт между сервисами.
 *
 * Контракт описывает данные которые путешествуют между сервисами.
 * Он не знает ничего о транспорте (Kafka или HTTP) — это принципиально.
 * Один контракт может быть использован и в Kafka и в HTTP одновременно.
 *
 * @param id          Уникальный стабильный идентификатор, например "user-created".
 *                    Используется как первичный ключ в реестре.
 * @param name        Человекочитаемое название
 * @param version     Текущая версия. Выставляется автоматически, не вручную.
 * @param fields      Список полей контракта
 * @param owner       Сервис или команда которые владеют контрактом.
 *                    Владелец отвечает за совместимость при изменениях.
 * @param description Описание назначения контракта
 * @param tags        Теги для организации (например "billing", "user-domain")
 */
final case class Contract(
  id:          ContractId,
  name:        ContractName,
  version:     SemanticVersion,
  fields:      List[Field],
  owner:       OwnerId,
  description: Option[String] = None,
  tags:        List[String]   = List.empty,
)

object Contract:

  /** Создаёт новый контракт с начальной версией [[SemanticVersion.Initial]] (1.0.0) */
  def create(
    id:          ContractId,
    name:        ContractName,
    fields:      List[Field],
    owner:       OwnerId,
    description: Option[String] = None,
    tags:        List[String]   = List.empty,
  ): Contract =
    Contract(
      id          = id,
      name        = name,
      version     = SemanticVersion.Initial,
      fields      = fields,
      owner       = owner,
      description = description,
      tags        = tags,
    )

package io.compact.registry

import io.compact.core.*

/** Индекс всех контрактов в реестре.
 * Хранится в registry.json в корне директории реестра.
 */
final case class RegistryIndex(contracts: List[RegistryIndexEntry])

object RegistryIndex:
  val empty: RegistryIndex = RegistryIndex(List.empty)

/** Запись об одном контракте в индексе */
final case class RegistryIndexEntry(
  id:             ContractId,
  name:           ContractName,
  currentVersion: SemanticVersion,
  owner:          OwnerId,
  description:    Option[String] = None,
  tags:           List[String]   = List.empty,
)

object RegistryIndexEntry:
  def fromContract(c: Contract): RegistryIndexEntry =
    RegistryIndexEntry(
      id             = c.id,
      name           = c.name,
      currentVersion = c.version,
      owner          = c.owner,
      description    = c.description,
      tags           = c.tags,
    )

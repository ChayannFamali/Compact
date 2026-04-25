package io.compact.registry.codec

import io.circe.*
import io.circe.syntax.*
import io.compact.core.*
import io.compact.registry.*
import io.compact.registry.codec.ContractCodecs.given

/** Circe кодеки для registry-специфичных типов.
 *
 * Требует [[ContractCodecs]] в scope для опережающих кодеков
 * ContractId, ContractName, OwnerId, ConsumerId, SemanticVersion.
 */
object RegistryCodecs:

  //  ConsumerRegistration 

  given Encoder[ConsumerRegistration] = Encoder.instance { cr =>
    Json.obj(
      "consumerId"     -> cr.consumerId.asJson,
      "contractId"     -> cr.contractId.asJson,
      "minimumVersion" -> cr.minimumVersion.asJson,
      "registeredAt"   -> cr.registeredAt.asJson,
    )
  }

  given Decoder[ConsumerRegistration] = Decoder.instance { c =>
    for
      consumerId     <- c.downField("consumerId").as[ConsumerId]
      contractId     <- c.downField("contractId").as[ContractId]
      minimumVersion <- c.downField("minimumVersion").as[SemanticVersion]
      registeredAt   <- c.downField("registeredAt").as[String]
    yield ConsumerRegistration(consumerId, contractId, minimumVersion, registeredAt)
  }

  //  ConsumersFile

  given Encoder[ConsumersFile] = Encoder.instance { f =>
    Json.obj(
      "contractId" -> f.contractId.asJson,
      "consumers"  -> f.consumers.asJson,
    )
  }

  given Decoder[ConsumersFile] = Decoder.instance { c =>
    for
      contractId <- c.downField("contractId").as[ContractId]
      consumers  <- c.downField("consumers").as[List[ConsumerRegistration]]
    yield ConsumersFile(contractId, consumers)
  }

  //  RegistryIndexEntry 

  given Encoder[RegistryIndexEntry] = Encoder.instance { e =>
    val required: List[(String, Json)] = List(
      "id"             -> e.id.asJson,
      "name"           -> e.name.asJson,
      "currentVersion" -> e.currentVersion.asJson,
      "owner"          -> e.owner.asJson,
    )
    val optional: List[(String, Json)] = List(
      e.description.map("description" -> _.asJson),
      if e.tags.nonEmpty then Some("tags" -> e.tags.asJson) else None,
    ).flatten
    Json.obj((required ++ optional)*)
  }

  given Decoder[RegistryIndexEntry] = Decoder.instance { c =>
    for
      id             <- c.downField("id").as[ContractId]
      name           <- c.downField("name").as[ContractName]
      currentVersion <- c.downField("currentVersion").as[SemanticVersion]
      owner          <- c.downField("owner").as[OwnerId]
      description    <- c.downField("description").as[Option[String]]
      tags           <- c.downField("tags").as[Option[List[String]]].map(_.getOrElse(List.empty))
    yield RegistryIndexEntry(id, name, currentVersion, owner, description, tags)
  }

  //  RegistryIndex

  given Encoder[RegistryIndex] = Encoder.instance { idx =>
    Json.obj("contracts" -> idx.contracts.asJson)
  }

  given Decoder[RegistryIndex] = Decoder.instance { c =>
    c.downField("contracts").as[List[RegistryIndexEntry]].map(RegistryIndex(_))
  }

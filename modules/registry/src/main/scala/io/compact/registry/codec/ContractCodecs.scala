package io.compact.registry.codec

import io.circe.*
import io.circe.syntax.*
import io.compact.core.*
import cats.syntax.either._
/** Circe кодеки для всех типов compact-core и ContractEnvelope.
 *
 * Использование:
 * {{{
 * import io.compact.registry.codec.ContractCodecs.given
 *
 * val json   : Json   = contract.asJson
 * val pretty : String = contract.asJson.spaces2
 * val result : Either[DecodingFailure, Contract] = json.as[Contract]
 * }}}
 *
 * Принципы формата:
 *  - [[SemanticVersion]] хранится как строка "1.2.3" — читаемый git diff
 *  - [[FieldType]] кодируется через поле "kind" — дискриминированный union
 *  - Поля с дефолтными значениями (deprecated=false, пустые tags) опускаются
 *  - Null значения не пишутся — только явные Some
 */
object ContractCodecs:

  //  Opaque types 
  // Прозрачная сериализация — в JSON просто строки

  given Encoder[ContractId]   = Encoder[String].contramap(_.value)
  given Decoder[ContractId]   = Decoder[String].emap(s =>
    Either.catchNonFatal(ContractId(s)).left.map(_.getMessage)
  )

  given Encoder[ContractName] = Encoder[String].contramap(_.value)
  given Decoder[ContractName] = Decoder[String].emap(s =>
    Either.catchNonFatal(ContractName(s)).left.map(_.getMessage)
  )

  given Encoder[OwnerId]      = Encoder[String].contramap(_.value)
  given Decoder[OwnerId]      = Decoder[String].emap(s =>
    Either.catchNonFatal(OwnerId(s)).left.map(_.getMessage)
  )

  given Encoder[ConsumerId]   = Encoder[String].contramap(_.value)
  given Decoder[ConsumerId]   = Decoder[String].emap(s =>
    Either.catchNonFatal(ConsumerId(s)).left.map(_.getMessage)
  )

  //  SemanticVersion 
  // "1.2.3" — человекочитаемый формат, нормальный git diff

  given Encoder[SemanticVersion] =
    Encoder[String].contramap(_.show)

  given Decoder[SemanticVersion] =
    Decoder[String].emap(SemanticVersion.parse)

  //  FieldType 
  // Рекурсивный enum. Используем `this` для рекурсии — без lazy val.
  //
  // Формат: {"kind": "str"}, {"kind": "collection", "element": {...}}, ...

  given fieldTypeEncoder: Encoder[FieldType] = new Encoder[FieldType]:
    def apply(ft: FieldType): Json = ft match
      // Примитивы — только kind
      case FieldType.Str       => Json.obj("kind" -> "str".asJson)
      case FieldType.Int32     => Json.obj("kind" -> "int32".asJson)
      case FieldType.Int64     => Json.obj("kind" -> "int64".asJson)
      case FieldType.Float32   => Json.obj("kind" -> "float32".asJson)
      case FieldType.Float64   => Json.obj("kind" -> "float64".asJson)
      case FieldType.Bool      => Json.obj("kind" -> "bool".asJson)
      case FieldType.Bytes     => Json.obj("kind" -> "bytes".asJson)
      case FieldType.Timestamp => Json.obj("kind" -> "timestamp".asJson)
      case FieldType.Uuid      => Json.obj("kind" -> "uuid".asJson)

      // Составные — рекурсивно через this
      case FieldType.Collection(element) =>
        Json.obj(
          "kind"    -> "collection".asJson,
          "element" -> this(element),
        )

      case FieldType.Mapping(key, value) =>
        Json.obj(
          "kind"  -> "mapping".asJson,
          "key"   -> this(key),
          "value" -> this(value),
        )

      case FieldType.Nested(contractId) =>
        Json.obj(
          "kind"       -> "nested".asJson,
          "contractId" -> contractId.asJson,
        )

      case FieldType.Union(variants) =>
        Json.obj(
          "kind"     -> "union".asJson,
          "variants" -> Json.arr(variants.map(this(_))*),
        )

  given fieldTypeDecoder: Decoder[FieldType] = new Decoder[FieldType]:
    def apply(c: HCursor): Decoder.Result[FieldType] =
      c.downField("kind").as[String].flatMap {
        // Примитивы
        case "str"       => Right(FieldType.Str)
        case "int32"     => Right(FieldType.Int32)
        case "int64"     => Right(FieldType.Int64)
        case "float32"   => Right(FieldType.Float32)
        case "float64"   => Right(FieldType.Float64)
        case "bool"      => Right(FieldType.Bool)
        case "bytes"     => Right(FieldType.Bytes)
        case "timestamp" => Right(FieldType.Timestamp)
        case "uuid"      => Right(FieldType.Uuid)

        // Составные — рекурсивно через this
        case "collection" =>
          c.downField("element").as[FieldType](this).map(FieldType.Collection(_))

        case "mapping" =>
          for
            key   <- c.downField("key").as[FieldType](this)
            value <- c.downField("value").as[FieldType](this)
          yield FieldType.Mapping(key, value)

        case "nested" =>
          c.downField("contractId").as[ContractId].map(FieldType.Nested(_))

        case "union" =>
          c.downField("variants")
            .as[List[Json]]
            .flatMap { jsons =>
              jsons.foldLeft(Right(List.empty[FieldType]): Decoder.Result[List[FieldType]]) {
                (acc, json) =>
                  acc.flatMap(list => this(json.hcursor).map(ft => list :+ ft))
              }
            }
            .map(FieldType.Union(_))

        case other =>
          Left(DecodingFailure(s"Неизвестный тип поля: '$other'", c.history))
      }

  //  Field 
  // deprecated=false и description=None не пишутся — чище в git diff

  given Encoder[Field] = Encoder.instance { f =>
    val required: List[(String, Json)] = List(
      "name"      -> f.name.asJson,
      "fieldType" -> f.fieldType.asJson,
      "required"  -> f.required.asJson,
    )
    val optional: List[(String, Json)] = List(
      f.description.map("description" -> _.asJson),
      if f.deprecated then Some("deprecated" -> true.asJson) else None,
    ).flatten

    Json.obj((required ++ optional)*)
  }

  given Decoder[Field] = Decoder.instance { c =>
    for
      name        <- c.downField("name").as[String]
      fieldType   <- c.downField("fieldType").as[FieldType]
      required    <- c.downField("required").as[Boolean]
      description <- c.downField("description").as[Option[String]]
      deprecated  <- c.downField("deprecated").as[Option[Boolean]].map(_.getOrElse(false))
    yield Field(name, fieldType, required, description, deprecated)
  }

  //  Contract 
  // description=None и tags=[] не пишутся

  given Encoder[Contract] = Encoder.instance { c =>
    val required: List[(String, Json)] = List(
      "id"      -> c.id.asJson,
      "name"    -> c.name.asJson,
      "version" -> c.version.asJson,
      "fields"  -> c.fields.asJson,
      "owner"   -> c.owner.asJson,
    )
    val optional: List[(String, Json)] = List(
      c.description.map("description" -> _.asJson),
      if c.tags.nonEmpty then Some("tags" -> c.tags.asJson) else None,
    ).flatten

    Json.obj((required ++ optional)*)
  }

  given Decoder[Contract] = Decoder.instance { c =>
    for
      id          <- c.downField("id").as[ContractId]
      name        <- c.downField("name").as[ContractName]
      version     <- c.downField("version").as[SemanticVersion]
      fields      <- c.downField("fields").as[List[Field]]
      owner       <- c.downField("owner").as[OwnerId]
      description <- c.downField("description").as[Option[String]]
      tags        <- c.downField("tags").as[Option[List[String]]].map(_.getOrElse(List.empty))
    yield Contract(id, name, version, fields, owner, description, tags)
  }

  //  VersionDiff 

  given Encoder[VersionDiff] = Encoder.instance {
    case VersionDiff.FieldAdded(field) =>
      Json.obj(
        "type"  -> "field-added".asJson,
        "field" -> field.asJson,
      )

    case VersionDiff.FieldRemoved(fieldName, removedField) =>
      Json.obj(
        "type"         -> "field-removed".asJson,
        "fieldName"    -> fieldName.asJson,
        "removedField" -> removedField.asJson,
      )

    case VersionDiff.FieldTypeChanged(fieldName, from, to) =>
      Json.obj(
        "type"      -> "field-type-changed".asJson,
        "fieldName" -> fieldName.asJson,
        "from"      -> from.asJson,
        "to"        -> to.asJson,
      )

    case VersionDiff.FieldRequirednessChanged(fieldName, wasRequired, isNowRequired) =>
      Json.obj(
        "type"          -> "field-requiredness-changed".asJson,
        "fieldName"     -> fieldName.asJson,
        "wasRequired"   -> wasRequired.asJson,
        "isNowRequired" -> isNowRequired.asJson,
      )

    case VersionDiff.FieldDeprecated(fieldName) =>
      Json.obj(
        "type"      -> "field-deprecated".asJson,
        "fieldName" -> fieldName.asJson,
      )

    case VersionDiff.MetadataChanged(oldDesc, newDesc) =>
      Json.obj(
        "type"           -> "metadata-changed".asJson,
        "oldDescription" -> oldDesc.asJson,
        "newDescription" -> newDesc.asJson,
      )

    case VersionDiff.TagsChanged(added, removed) =>
      Json.obj(
        "type"    -> "tags-changed".asJson,
        "added"   -> added.asJson,
        "removed" -> removed.asJson,
      )
  }

  given Decoder[VersionDiff] = Decoder.instance { c =>
    c.downField("type").as[String].flatMap {
      case "field-added" =>
        c.downField("field").as[Field].map(VersionDiff.FieldAdded(_))

      case "field-removed" =>
        for
          name  <- c.downField("fieldName").as[String]
          field <- c.downField("removedField").as[Field]
        yield VersionDiff.FieldRemoved(name, field)

      case "field-type-changed" =>
        for
          name <- c.downField("fieldName").as[String]
          from <- c.downField("from").as[FieldType]
          to   <- c.downField("to").as[FieldType]
        yield VersionDiff.FieldTypeChanged(name, from, to)

      case "field-requiredness-changed" =>
        for
          name          <- c.downField("fieldName").as[String]
          wasRequired   <- c.downField("wasRequired").as[Boolean]
          isNowRequired <- c.downField("isNowRequired").as[Boolean]
        yield VersionDiff.FieldRequirednessChanged(name, wasRequired, isNowRequired)

      case "field-deprecated" =>
        c.downField("fieldName").as[String].map(VersionDiff.FieldDeprecated(_))

      case "metadata-changed" =>
        for
          oldDesc <- c.downField("oldDescription").as[Option[String]]
          newDesc <- c.downField("newDescription").as[Option[String]]
        yield VersionDiff.MetadataChanged(oldDesc, newDesc)

      case "tags-changed" =>
        for
          added   <- c.downField("added").as[List[String]]
          removed <- c.downField("removed").as[List[String]]
        yield VersionDiff.TagsChanged(added, removed)

      case other =>
        Left(DecodingFailure(s"Неизвестный тип VersionDiff: '$other'", c.history))
    }
  }

  //  CompatibilityResult 

  given Encoder[CompatibilityResult] = Encoder.instance {
    case CompatibilityResult.Identical    => Json.obj("type" -> "identical".asJson)
    case CompatibilityResult.Patch(diffs) => Json.obj("type" -> "patch".asJson, "diffs" -> diffs.asJson)
    case CompatibilityResult.Minor(diffs) => Json.obj("type" -> "minor".asJson, "diffs" -> diffs.asJson)
    case CompatibilityResult.Major(diffs) => Json.obj("type" -> "major".asJson, "diffs" -> diffs.asJson)
  }

  given Decoder[CompatibilityResult] = Decoder.instance { c =>
    c.downField("type").as[String].flatMap {
      case "identical" => Right(CompatibilityResult.Identical)
      case "patch"     => c.downField("diffs").as[List[VersionDiff]].map(CompatibilityResult.Patch(_))
      case "minor"     => c.downField("diffs").as[List[VersionDiff]].map(CompatibilityResult.Minor(_))
      case "major"     => c.downField("diffs").as[List[VersionDiff]].map(CompatibilityResult.Major(_))
      case other       => Left(DecodingFailure(s"Неизвестный CompatibilityResult: '$other'", c.history))
    }
  }

  //  ContractEnvelope 

  given Encoder[ContractEnvelope] = Encoder.instance { e =>
    Json.obj(
      "formatVersion" -> e.formatVersion.asJson,
      "contract"      -> e.contract.asJson,
    )
  }

  given Decoder[ContractEnvelope] = Decoder.instance { c =>
    for
      formatVersion <- c.downField("formatVersion").as[Int]
      contract      <- c.downField("contract").as[Contract]
    yield ContractEnvelope(formatVersion, contract)
  }

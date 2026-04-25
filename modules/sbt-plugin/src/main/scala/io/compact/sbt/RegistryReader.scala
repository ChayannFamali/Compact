package io.compact.sbt

import io.circe.{Json, HCursor}
import io.circe.parser.{parse => parseJson}

import java.io.File
import scala.io.Source
import scala.util.{Try, Using}

/** Читает JSON файлы реестра с диска.
 *
 * Работает напрямую с файловой системой — не использует cats-effect.
 * Это нормально для sbt плагина который работает синхронно.
 */
object RegistryReader {

  //  Публичный API 

  def readRegistryIndex(registryPath: File): Either[String, RegistryIndex] = {
    val indexFile = new File(registryPath, "registry.json")
    if (!indexFile.exists()) Right(RegistryIndex.empty)
    else readJson(indexFile).flatMap(decodeIndex)
  }

  def readContract(registryPath: File, contractId: String): Either[String, ContractMeta] = {
    val file = contractFile(registryPath, contractId)
    if (!file.exists()) Left(s"Файл не найден: ${file.getPath}")
    else readJson(file).flatMap(decodeContractEnvelope)
  }

  def readConsumers(registryPath: File, contractId: String): Either[String, ConsumersFile] = {
    val file = new File(new File(registryPath, contractId), "consumers.json")
    if (!file.exists()) Right(ConsumersFile(contractId, Nil))
    else readJson(file).flatMap(decodeConsumersFile)
  }

  def contractFile(registryPath: File, contractId: String): File =
    new File(new File(registryPath, contractId), "contract.json")

  //  Чтение файла 

  private def readJson(file: File): Either[String, Json] =
    Try {
      val content = Using(Source.fromFile(file))(_.mkString).get
      parseJson(content).left.map(e => s"JSON parse error в ${file.getName}: ${e.message}")
    }.toEither
      .left.map(e => s"Ошибка чтения ${file.getPath}: ${e.getMessage}")
      .flatMap(identity)

  //  Декодеры 

  private def decodeIndex(json: Json): Either[String, RegistryIndex] =
    for {
      entries <- json.hcursor.downField("contracts").as[List[Json]].left.map(_.message)
      decoded <- traverseEither(entries)(decodeIndexEntry)
    } yield RegistryIndex(decoded)

  private def decodeIndexEntry(json: Json): Either[String, RegistryIndexEntry] = {
    val c = json.hcursor
    for {
      id      <- c.downField("id").as[String].left.map(_.message)
      name    <- c.downField("name").as[String].left.map(_.message)
      vStr    <- c.downField("currentVersion").as[String].left.map(_.message)
      version <- SemVer.parse(vStr)
      owner   <- c.downField("owner").as[String].left.map(_.message)
    } yield RegistryIndexEntry(id, name, version, owner)
  }

  /** Декодирует контракт из ContractEnvelope (formatVersion + contract) */
  def decodeContractEnvelope(json: Json): Either[String, ContractMeta] = {
    val contractJson =
      if (json.hcursor.downField("formatVersion").succeeded)
        json.hcursor.downField("contract").focus.getOrElse(json)
      else
        json
    decodeContractBody(contractJson)
  }

  private def decodeContractBody(json: Json): Either[String, ContractMeta] = {
    val c = json.hcursor
    for {
      id          <- c.downField("id").as[String].left.map(_.message)
      name        <- c.downField("name").as[String].left.map(_.message)
      vStr        <- c.downField("version").as[String].left.map(_.message)
      version     <- SemVer.parse(vStr)
      owner       <- c.downField("owner").as[String].left.map(_.message)
      fieldsList  <- c.downField("fields").as[List[Json]].left.map(_.message)
      fields      <- traverseEither(fieldsList)(decodeField)
      description  = c.downField("description").as[String].toOption
      tags         = c.downField("tags").as[List[String]].toOption.getOrElse(Nil)
    } yield ContractMeta(id, name, version, owner, fields, description, tags)
  }

  def decodeField(json: Json): Either[String, FieldMeta] = {
    val c = json.hcursor
    for {
      name       <- c.downField("name").as[String].left.map(_.message)
      ftJson     <- c.downField("fieldType").focus.toRight("Отсутствует поле 'fieldType'")
      fieldType  <- ftJson.hcursor.downField("kind").as[String].left.map(_.message)
      required   <- c.downField("required").as[Boolean].left.map(_.message)
      deprecated  = c.downField("deprecated").as[Boolean].toOption.getOrElse(false)
    } yield FieldMeta(name, fieldType, required, deprecated)
  }

  private def decodeConsumersFile(json: Json): Either[String, ConsumersFile] = {
    val c = json.hcursor
    for {
      contractId    <- c.downField("contractId").as[String].left.map(_.message)
      consumersJson <- c.downField("consumers").as[List[Json]].left.map(_.message)
      consumers     <- traverseEither(consumersJson)(decodeConsumer)
    } yield ConsumersFile(contractId, consumers)
  }

  private def decodeConsumer(json: Json): Either[String, ConsumerMeta] = {
    val c = json.hcursor
    for {
      consumerId     <- c.downField("consumerId").as[String].left.map(_.message)
      contractId     <- c.downField("contractId").as[String].left.map(_.message)
      vStr           <- c.downField("minimumVersion").as[String].left.map(_.message)
      minimumVersion <- SemVer.parse(vStr)
      registeredAt   <- c.downField("registeredAt").as[String].left.map(_.message)
    } yield ConsumerMeta(consumerId, contractId, minimumVersion, registeredAt)
  }

  //  Helper 

  def traverseEither[A, B](
    list: List[A],
  )(f: A => Either[String, B]): Either[String, List[B]] =
    list.foldRight(Right(Nil): Either[String, List[B]]) { (a, acc) =>
      for { b <- f(a); rest <- acc } yield b :: rest
    }
}

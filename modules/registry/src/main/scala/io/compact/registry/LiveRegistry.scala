package io.compact.registry

import cats.effect.IO
import cats.syntax.traverse.*
import fs2.io.file.{Files, Path}
import io.circe.parser as circeParser
import io.circe.syntax.*
import io.compact.core.*
import io.compact.registry.codec.{ContractCodecs, ContractMigration, RegistryCodecs}
import io.compact.registry.codec.ContractCodecs.given
import io.compact.registry.codec.RegistryCodecs.given

import java.time.Instant

/** Файловая реализация реестра контрактов.
 *
 * Структура на диске:
 * {{{
 * registryPath/
 * ├── registry.json          
 * ├── user-created/
 * │   ├── contract.json       
 * │   ├── v1.0.0.json         
 * │   ├── v1.1.0.json
 * │   └── consumers.json       
 * └── order-placed/
 *     ├── contract.json
 *     └── consumers.json
 * }}}
 *
 * Создавать через [[LiveRegistry.make]].
 */
final class LiveRegistry private (val registryPath: Path) extends Registry:

  // Пути к файлам 

  private def contractDir(id: ContractId): Path =
    registryPath / id.value

  private def contractFilePath(id: ContractId): Path =
    contractDir(id) / "contract.json"

  private def consumersFilePath(id: ContractId): Path =
    contractDir(id) / "consumers.json"

  private def historyFilePath(id: ContractId, version: SemanticVersion): Path =
    contractDir(id) / s"v${version.show}.json"

  private val registryIndexPath: Path =
    registryPath / "registry.json"

  // Публичный API 

  override def findContract(id: ContractId): IO[Option[Contract]] =
    val path = contractFilePath(id)
    Files[IO].exists(path).flatMap {
      case false => IO.pure(None)
      case true  =>
        readFile(path)
          .flatMap(json => IO.fromEither(ContractMigration.decode(json).left.map(new RuntimeException(_))))
          .map(Some(_))
    }

  override def listContracts: IO[List[RegistryIndexEntry]] =
    readRegistryIndex.map(_.contracts)

  override def contractHistory(id: ContractId): IO[Either[ContractError, List[Contract]]] =
    val dir = contractDir(id)
    Files[IO].exists(dir).flatMap {
      case false => IO.pure(Left(ContractError.ContractNotFound(id)))
      case true =>
        Files[IO]
          .list(dir)
          .filter(p => p.fileName.toString.startsWith("v") && p.fileName.toString.endsWith(".json"))
          .evalMap { path =>
            readFile(path)
              .flatMap(json => IO.fromEither(ContractMigration.decode(json).left.map(new RuntimeException(_))))
          }
          .compile
          .toList
          .map(contracts => Right(contracts.sortBy(_.version)))
    }

  override def saveContract(
    contract:      Contract,
    allowBreaking: Boolean = false,
  ): IO[Either[ContractError, Contract]] =
    findContract(contract.id).flatMap {
      case None =>
        // Первое сохранение — начальная версия 1.0.0
        doSaveNew(contract.copy(version = SemanticVersion.Initial)).map(Right(_))

      case Some(existing) =>
        val compatibility = CompatibilityClassifier.classify(existing, contract)
        if compatibility.isBreaking && !allowBreaking then
          IO.pure(Left(ContractError.BreakingChangeNotAcknowledged(contract.id, compatibility)))
        else
          val nextVersion = compatibility.nextVersion(existing.version)
          doSaveUpdate(contract.copy(version = nextVersion), existing).map(Right(_))
    }

  override def registerConsumer(
    contractId:     ContractId,
    consumerId:     ConsumerId,
    minimumVersion: SemanticVersion,
  ): IO[Either[ContractError, Unit]] =
    findContract(contractId).flatMap {
      case None =>
        IO.pure(Left(ContractError.ContractNotFound(contractId)))

      case Some(_) =>
        for
          existing   <- readConsumersFile(contractId)
          now         = Instant.now().toString
          newEntry    = ConsumerRegistration(consumerId, contractId, minimumVersion, now)
          updated     = existing.copy(
            consumers = existing.consumers.filterNot(_.consumerId == consumerId) :+ newEntry
          )
          _          <- writeFile(consumersFilePath(contractId), updated.asJson.spaces2)
        yield Right(())
    }

  override def getConsumers(contractId: ContractId): IO[Either[ContractError, List[ConsumerRegistration]]] =
    findContract(contractId).flatMap {
      case None    => IO.pure(Left(ContractError.ContractNotFound(contractId)))
      case Some(_) => readConsumersFile(contractId).map(f => Right(f.consumers))
    }

  override def getDependencyGraph: IO[DependencyGraph] =
    for
      entries   <- listContracts
      contracts <- entries.traverse(e => findContract(e.id))
    yield DependencyGraph.build(contracts.flatten)

  override def whoBreaksIf(
    contractId: ContractId,
    newResult:  CompatibilityResult,
  ): IO[List[ConsumerRegistration]] =
    if !newResult.isBreaking then IO.pure(List.empty)
    else
      findContract(contractId).flatMap {
        case None => IO.pure(List.empty)
        case Some(current) =>
          val nextVersion = newResult.nextVersion(current.version)
          readConsumersFile(contractId)
            .map(_.consumers.filterNot(cr => nextVersion.isBackwardCompatibleWith(cr.minimumVersion)))
      }

  // Приватные вспомогательные методы

  private def doSaveNew(contract: Contract): IO[Contract] =
    val dir  = contractDir(contract.id)
    val json = ContractMigration.encode(contract)
    for
      _ <- Files[IO].createDirectories(dir)
      _ <- writeFile(contractFilePath(contract.id), json)
      _ <- writeFile(historyFilePath(contract.id, contract.version), json)
      _ <- updateRegistryIndex(contract)
    yield contract

  private def doSaveUpdate(newContract: Contract, previous: Contract): IO[Contract] =
    val newJson = ContractMigration.encode(newContract)
    for
      _ <- writeFile(historyFilePath(newContract.id, previous.version), ContractMigration.encode(previous))
      _ <- writeFile(contractFilePath(newContract.id), newJson)
      _ <- updateRegistryIndex(newContract)
    yield newContract

  private def updateRegistryIndex(contract: Contract): IO[Unit] =
    for
      existing <- readRegistryIndex
      entry     = RegistryIndexEntry.fromContract(contract)
      updated   = existing.copy(
        contracts = existing.contracts.filterNot(_.id == contract.id) :+ entry,
      )
      _        <- writeFile(registryIndexPath, updated.asJson.spaces2)
    yield ()

  private def readRegistryIndex: IO[RegistryIndex] =
    Files[IO].exists(registryIndexPath).flatMap {
      case false => IO.pure(RegistryIndex.empty)
      case true  =>
        readFile(registryIndexPath)
          .map(json =>
            circeParser
              .decode[RegistryIndex](json)
              .getOrElse(RegistryIndex.empty)
          )
    }

  private def readConsumersFile(contractId: ContractId): IO[ConsumersFile] =
    val path = consumersFilePath(contractId)
    Files[IO].exists(path).flatMap {
      case false => IO.pure(ConsumersFile.empty(contractId))
      case true  =>
        readFile(path)
          .map(json =>
            circeParser
              .decode[ConsumersFile](json)
              .getOrElse(ConsumersFile.empty(contractId))
          )
    }

  private def readFile(path: Path): IO[String] =
    Files[IO]
      .readAll(path)
      .through(fs2.text.utf8.decode)
      .compile
      .string

  private def writeFile(path: Path, content: String): IO[Unit] =
    fs2.Stream(content)
      .through(fs2.text.utf8.encode)
      .through(Files[IO].writeAll(path))
      .compile
      .drain

object LiveRegistry:

  /** Создаёт реестр и инициализирует директорию если не существует */
  def make(registryPath: Path): IO[LiveRegistry] =
    Files[IO]
      .createDirectories(registryPath)
      .as(new LiveRegistry(registryPath))

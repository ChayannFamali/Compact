package io.compact.registry

import cats.effect.IO
import fs2.io.file.{Files, Path}
import munit.CatsEffectSuite
import io.compact.core.*

class LiveRegistrySpec extends CatsEffectSuite:

  // Создаёт временную директорию, удаляет после теста
  val registryDir = ResourceFixture(Files[IO].tempDirectory)

  //  Вспомогательные методы 

  private def makeRegistry(dir: Path): IO[LiveRegistry] =
    LiveRegistry.make(dir / "registry")

  private def baseContract(id: String = "user-created"): Contract =
    Contract.create(
      id     = ContractId(id),
      name   = ContractName(id),
      fields = List(
        Field("id",    FieldType.Uuid, required = true),
        Field("email", FieldType.Str,  required = true),
      ),
      owner = OwnerId("test-service"),
    )

  //  findContract 

  registryDir.test("findContract — не найден → None") { dir =>
    for
      registry <- makeRegistry(dir)
      result   <- registry.findContract(ContractId("nonexistent"))
    yield assertEquals(result, None)
  }

  registryDir.test("findContract — после saveContract → Some") { dir =>
    for
      registry <- makeRegistry(dir)
      _        <- registry.saveContract(baseContract())
      result   <- registry.findContract(ContractId("user-created"))
    yield assert(result.isDefined)
  }

  //  saveContract — новый контракт ─

  registryDir.test("saveContract новый → версия 1.0.0") { dir =>
    for
      registry <- makeRegistry(dir)
      result   <- registry.saveContract(baseContract())
    yield
      assert(result.isRight)
      assertEquals(result.map(_.version), Right(SemanticVersion(1, 0, 0)))
  }

  registryDir.test("saveContract новый → создаёт файлы на диске") { dir =>
    val registryPath = dir / "registry"
    for
      registry <- LiveRegistry.make(registryPath)
      _        <- registry.saveContract(baseContract())
      contractFileExists <- Files[IO].exists(registryPath / "user-created" / "contract.json")
      historyFileExists  <- Files[IO].exists(registryPath / "user-created" / "v1.0.0.json")
      indexFileExists    <- Files[IO].exists(registryPath / "registry.json")
    yield
      assert(contractFileExists, "contract.json должен существовать")
      assert(historyFileExists,  "v1.0.0.json должен существовать")
      assert(indexFileExists,    "registry.json должен существовать")
  }

  //  saveContract — Patch изменение 

  registryDir.test("saveContract Patch изменение → bump patch версии") { dir =>
    for
      registry <- makeRegistry(dir)
      _        <- registry.saveContract(baseContract())
      patched   = baseContract().copy(description = Some("Updated description"))
      result   <- registry.saveContract(patched)
    yield assertEquals(result.map(_.version), Right(SemanticVersion(1, 0, 1)))
  }

  //  saveContract — Minor изменение 

  registryDir.test("saveContract Minor изменение → bump minor версии") { dir =>
    for
      registry <- makeRegistry(dir)
      _        <- registry.saveContract(baseContract())
      withNewField = baseContract().copy(fields = baseContract().fields :+
        Field("phone", FieldType.Str, required = false))
      result   <- registry.saveContract(withNewField)
    yield assertEquals(result.map(_.version), Right(SemanticVersion(1, 1, 0)))
  }

  //  saveContract — Major изменение 

  registryDir.test("saveContract Major изменение без флага → Left(BreakingChange)") { dir =>
    for
      registry  <- makeRegistry(dir)
      _         <- registry.saveContract(baseContract())
      // Удаляем обязательное поле — breaking change
      withoutEmail = baseContract().copy(
        fields = baseContract().fields.filterNot(_.name == "email")
      )
      result   <- registry.saveContract(withoutEmail, allowBreaking = false)
    yield assert(result.isLeft, "Должна быть ошибка BreakingChangeNotAcknowledged")
  }

  registryDir.test("saveContract Major изменение с allowBreaking=true → bump major") { dir =>
    for
      registry  <- makeRegistry(dir)
      _         <- registry.saveContract(baseContract())
      withoutEmail = baseContract().copy(
        fields = baseContract().fields.filterNot(_.name == "email")
      )
      result   <- registry.saveContract(withoutEmail, allowBreaking = true)
    yield assertEquals(result.map(_.version), Right(SemanticVersion(2, 0, 0)))
  }

  //  saveContract — история версий

  registryDir.test("contractHistory содержит все сохранённые версии") { dir =>
    for
        registry <- makeRegistry(dir)
        _        <- registry.saveContract(baseContract())
        withPatch1 = baseContract().copy(description = Some("v2"))
        _        <- registry.saveContract(withPatch1)
        withPatch2 = baseContract().copy(description = Some("v3")) 
        _        <- registry.saveContract(withPatch2)              
        history  <- registry.contractHistory(ContractId("user-created"))
    yield
        assert(history.isRight)
        assertEquals(history.map(_.size), Right(2))
        assert(history.exists(_.exists(_.version == SemanticVersion(1, 0, 0))))
        assert(history.exists(_.exists(_.version == SemanticVersion(1, 0, 1))))
        assert(!history.exists(_.exists(_.version == SemanticVersion(1, 0, 2))))
    }

  registryDir.test("contractHistory несуществующего контракта → Left") { dir =>
    for
      registry <- makeRegistry(dir)
      result   <- registry.contractHistory(ContractId("nonexistent"))
    yield assert(result.isLeft)
  }

  //  listContracts ─

  registryDir.test("listContracts пустой реестр → пустой список") { dir =>
    for
      registry <- makeRegistry(dir)
      list     <- registry.listContracts
    yield assertEquals(list, List.empty)
  }

  registryDir.test("listContracts содержит сохранённые контракты") { dir =>
    for
      registry <- makeRegistry(dir)
      _        <- registry.saveContract(baseContract("contract-a"))
      _        <- registry.saveContract(baseContract("contract-b"))
      list     <- registry.listContracts
    yield
      assertEquals(list.size, 2)
      assert(list.exists(_.id == ContractId("contract-a")))
      assert(list.exists(_.id == ContractId("contract-b")))
  }

  //  registerConsumer / getConsumers ─

  registryDir.test("registerConsumer для несуществующего контракта → Left") { dir =>
    for
      registry <- makeRegistry(dir)
      result   <- registry.registerConsumer(
        ContractId("nonexistent"),
        ConsumerId("svc"),
        SemanticVersion(1, 0, 0),
      )
    yield assert(result.isLeft)
  }

  registryDir.test("registerConsumer добавляет консьюмера") { dir =>
    for
      registry <- makeRegistry(dir)
      _        <- registry.saveContract(baseContract())
      _        <- registry.registerConsumer(ContractId("user-created"), ConsumerId("order-svc"), SemanticVersion(1, 0, 0))
      consumers <- registry.getConsumers(ContractId("user-created"))
    yield
      assert(consumers.isRight)
      assert(consumers.exists(_.exists(_.consumerId == ConsumerId("order-svc"))))
  }

  registryDir.test("registerConsumer upsert — обновляет minimumVersion") { dir =>
    for
      registry  <- makeRegistry(dir)
      _         <- registry.saveContract(baseContract())
      _         <- registry.registerConsumer(ContractId("user-created"), ConsumerId("svc"), SemanticVersion(1, 0, 0))
      _         <- registry.registerConsumer(ContractId("user-created"), ConsumerId("svc"), SemanticVersion(1, 1, 0))
      consumers <- registry.getConsumers(ContractId("user-created"))
    yield
      assertEquals(consumers.map(_.size), Right(1), "Должна быть одна запись после upsert")
      assertEquals(
        consumers.map(_.head.minimumVersion),
        Right(SemanticVersion(1, 1, 0)),
        "Версия должна обновиться",
      )
  }

  //  whoBreaksIf ─

  registryDir.test("whoBreaksIf — safe change → пустой список") { dir =>
    for
      registry <- makeRegistry(dir)
      _        <- registry.saveContract(baseContract())
      _        <- registry.registerConsumer(ContractId("user-created"), ConsumerId("svc"), SemanticVersion(1, 0, 0))
      breaking  = CompatibilityResult.Minor(List.empty) // не breaking
      result   <- registry.whoBreaksIf(ContractId("user-created"), breaking)
    yield assertEquals(result, List.empty)
  }

  registryDir.test("whoBreaksIf — major change → консьюмеры старого major сломаются") { dir =>
    for
      registry  <- makeRegistry(dir)
      _         <- registry.saveContract(baseContract())
      // Консьюмер зарегистрировался с v1.0.0
      _         <- registry.registerConsumer(ContractId("user-created"), ConsumerId("old-svc"), SemanticVersion(1, 0, 0))
      majorChange = CompatibilityResult.Major(List.empty)
      result    <- registry.whoBreaksIf(ContractId("user-created"), majorChange)
    yield
      assertEquals(result.size, 1)
      assertEquals(result.head.consumerId, ConsumerId("old-svc"))
  }

  registryDir.test("whoBreaksIf — несуществующий контракт → пустой список") { dir =>
    for
      registry <- makeRegistry(dir)
      result   <- registry.whoBreaksIf(ContractId("nonexistent"), CompatibilityResult.Major(List.empty))
    yield assertEquals(result, List.empty)
  }

  //  getDependencyGraph 

  registryDir.test("getDependencyGraph строится из сохранённых контрактов") { dir =>
    val addressContract = Contract.create(
      id     = ContractId("address"),
      name   = ContractName("Address"),
      fields = List(Field("city", FieldType.Str, required = true)),
      owner  = OwnerId("svc"),
    )
    val userContract = Contract.create(
      id     = ContractId("user-profile"),
      name   = ContractName("User Profile"),
      fields = List(
        Field("id",      FieldType.Uuid,                           required = true),
        Field("address", FieldType.Nested(ContractId("address")), required = false),
      ),
      owner = OwnerId("svc"),
    )
    for
      registry <- makeRegistry(dir)
      _        <- registry.saveContract(addressContract)
      _        <- registry.saveContract(userContract)
      graph    <- registry.getDependencyGraph
    yield
      assertEquals(
        graph.directDependents(ContractId("address")),
        Set(ContractId("user-profile")),
      )
  }

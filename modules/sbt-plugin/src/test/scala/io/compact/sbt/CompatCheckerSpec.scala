package io.compact.sbt

class CompatCheckerSpec extends munit.FunSuite {

  // ── Helpers ────────────────────────────────────────────────────────────────

  def field(name: String, ft: String = "str", required: Boolean = true): FieldMeta =
    FieldMeta(name, ft, required)

  def contract(id: String, fields: FieldMeta*): ContractMeta =
    ContractMeta(id, id, SemVer(1, 0, 0), "test", fields.toList)

  def consumer(consumerId: String, contractId: String, minVersion: String): ConsumerMeta =
    ConsumerMeta(consumerId, contractId, SemVer.parse(minVersion).getOrElse(SemVer(1,0,0)), "2024-01-01")

  // ── classifyFieldChanges ───────────────────────────────────────────────────

  test("без изменений → нет FieldChange") {
    val fields = List(field("id"), field("email"))
    assert(CompatChecker.classifyFieldChanges(fields, fields).isEmpty)
  }

  test("поле удалено → MajorChange") {
    val before = List(field("id"), field("email"))
    val after  = List(field("id"))
    val changes = CompatChecker.classifyFieldChanges(before, after)
    assert(changes.exists(c => c.fieldName == "email" && c.level == MajorChange))
  }

  test("добавлено обязательное поле → MajorChange") {
    val before = List(field("id"))
    val after  = List(field("id"), field("email", required = true))
    val changes = CompatChecker.classifyFieldChanges(before, after)
    assert(changes.exists(c => c.fieldName == "email" && c.level == MajorChange))
  }

  test("добавлено опциональное поле → MinorChange") {
    val before = List(field("id"))
    val after  = List(field("id"), field("age", "int32", required = false))
    val changes = CompatChecker.classifyFieldChanges(before, after)
    assert(changes.exists(c => c.fieldName == "age" && c.level == MinorChange))
  }

  test("тип поля изменён → MajorChange") {
    val before = List(field("id", "str"))
    val after  = List(field("id", "int32"))
    val changes = CompatChecker.classifyFieldChanges(before, after)
    assert(changes.exists(c => c.fieldName == "id" && c.level == MajorChange))
  }

  test("required → optional → MinorChange") {
    val before = List(field("email", "str", required = true))
    val after  = List(field("email", "str", required = false))
    val changes = CompatChecker.classifyFieldChanges(before, after)
    assert(changes.exists(c => c.fieldName == "email" && c.level == MinorChange))
  }

  test("optional → required → MajorChange") {
    val before = List(field("email", "str", required = false))
    val after  = List(field("email", "str", required = true))
    val changes = CompatChecker.classifyFieldChanges(before, after)
    assert(changes.exists(c => c.fieldName == "email" && c.level == MajorChange))
  }

  test("поле deprecated → PatchChange") {
    val before = List(FieldMeta("old-field", "str", required = false, deprecated = false))
    val after  = List(FieldMeta("old-field", "str", required = false, deprecated = true))
    val changes = CompatChecker.classifyFieldChanges(before, after)
    assert(changes.exists(c => c.fieldName == "old-field" && c.level == PatchChange))
  }

  test("несколько изменений — classifyChange возвращает наихудший уровень") {
    val before = List(field("id"), field("email"))
    val after  = List(field("id"), field("age", "int32", required = false)) // email удалён (Major), age добавлен (Minor)
    val (level, _) = CompatChecker.classifyChange(contract("test", before: _*), contract("test", after: _*))
    assertEquals(level, MajorChange)
  }

  // ── checkRegistry ─────────────────────────────────────────────────────────

  test("checkRegistry — всё в порядке → нет проблем") {
    val c = contract("user-created", field("id"), field("email"))
    val index = RegistryIndex(List(RegistryIndexEntry("user-created", "User Created", SemVer(1, 2, 0), "svc")))
    val contracts = Map("user-created" -> c.copy(version = SemVer(1, 2, 0)))
    val consumers = Map("user-created" -> ConsumersFile("user-created",
      List(consumer("order-svc", "user-created", "1.0.0"))
    ))
    val issues = CompatChecker.checkRegistry(index, contracts, consumers)
    assert(issues.isEmpty, s"Не ожидалось проблем: $issues")
  }

  test("checkRegistry — консьюмер с несовместимой версией → ConsumerIncompatible") {
    val c = contract("user-created").copy(version = SemVer(2, 0, 0))
    val index = RegistryIndex(List(RegistryIndexEntry("user-created", "User Created", SemVer(2, 0, 0), "svc")))
    val contracts = Map("user-created" -> c)
    val consumers = Map("user-created" -> ConsumersFile("user-created",
      List(consumer("order-svc", "user-created", "1.0.0")) // major несовместимость
    ))
    val issues = CompatChecker.checkRegistry(index, contracts, consumers)
    assert(issues.exists(_.isInstanceOf[ConsumerIncompatible]))
  }

  test("checkRegistry — контракт в индексе но нет файла → MissingContractFile") {
    val index = RegistryIndex(List(RegistryIndexEntry("missing-contract", "Missing", SemVer(1, 0, 0), "svc")))
    val contracts = Map.empty[String, ContractMeta]
    val consumers = Map.empty[String, ConsumersFile]
    val issues = CompatChecker.checkRegistry(index, contracts, consumers)
    assert(issues.exists(_.isInstanceOf[MissingContractFile]))
  }

  test("checkRegistry — orphan consumer → OrphanConsumer") {
    val index = RegistryIndex(Nil) // пустой индекс
    val contracts = Map.empty[String, ContractMeta]
    val consumers = Map("nonexistent" -> ConsumersFile("nonexistent",
      List(consumer("some-svc", "nonexistent", "1.0.0"))
    ))
    val issues = CompatChecker.checkRegistry(index, contracts, consumers)
    assert(issues.exists(_.isInstanceOf[OrphanConsumer]))
  }

  // ── ChangeLevel ───────────────────────────────────────────────────────────

  test("ChangeLevel.max") {
    assertEquals(ChangeLevel.max(PatchChange, MinorChange), MinorChange)
    assertEquals(ChangeLevel.max(MinorChange, MajorChange), MajorChange)
    assertEquals(ChangeLevel.max(MajorChange, PatchChange), MajorChange)
  }

  test("ChangeLevel.label") {
    assertEquals(ChangeLevel.label(PatchChange), "Patch")
    assertEquals(ChangeLevel.label(MinorChange), "Minor")
    assert(ChangeLevel.label(MajorChange).contains("Major"))
  }
}

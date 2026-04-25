package io.compact.core

class CompatibilityClassifierSpec extends munit.FunSuite:

  //  Вспомогательные методы ─

  private val owner = OwnerId("test-service")
  private val v1    = SemanticVersion(1, 0, 0)

  private def contract(fields: Field*): Contract =
    Contract(
      id      = ContractId("test-contract"),
      name    = ContractName("Test Contract"),
      version = v1,
      fields  = fields.toList,
      owner   = owner,
    )

  private def str(name: String, required: Boolean = true): Field =
    Field(name, FieldType.Str, required)

  private def int(name: String, required: Boolean = true): Field =
    Field(name, FieldType.Int32, required)

  private def classify(before: Contract, after: Contract) =
    CompatibilityClassifier.classify(before, after)

  //  Identical 

  test("одинаковые контракты → Identical"):
    val c = contract(str("id"), str("email"))
    assertEquals(classify(c, c), CompatibilityResult.Identical)

  test("Identical если изменилась только версия"):
    val before = contract(str("id"))
    val after  = before.copy(version = v1.bumpMinor)
    assertEquals(classify(before, after), CompatibilityResult.Identical)

  //  Patch 

  test("description изменился → Patch"):
    val before = contract(str("id")).copy(description = Some("Before"))
    val after  = contract(str("id")).copy(description = Some("After"))
    assert(classify(before, after).isInstanceOf[CompatibilityResult.Patch])

  test("description появился → Patch"):
    val before = contract(str("id")).copy(description = None)
    val after  = contract(str("id")).copy(description = Some("Added"))
    assert(classify(before, after).isInstanceOf[CompatibilityResult.Patch])

  test("тег добавлен → Patch"):
    val before = contract(str("id")).copy(tags = List("v1"))
    val after  = contract(str("id")).copy(tags = List("v1", "stable"))
    assert(classify(before, after).isInstanceOf[CompatibilityResult.Patch])

  test("тег убран → Patch"):
    val before = contract(str("id")).copy(tags = List("v1", "beta"))
    val after  = contract(str("id")).copy(tags = List("v1"))
    assert(classify(before, after).isInstanceOf[CompatibilityResult.Patch])

  test("поле помечено deprecated → Patch"):
    val before = contract(str("id"), str("legacy"))
    val after  = contract(str("id"), str("legacy").copy(deprecated = true))
    assert(classify(before, after).isInstanceOf[CompatibilityResult.Patch])

  //  Minor 

  test("добавлено необязательное поле → Minor"):
    val before = contract(str("id"))
    val after  = contract(str("id"), str("nickname", required = false))
    assert(classify(before, after).isInstanceOf[CompatibilityResult.Minor])

  test("required → optional → Minor"):
    val before = contract(str("id"), str("middle-name", required = true))
    val after  = contract(str("id"), str("middle-name", required = false))
    assert(classify(before, after).isInstanceOf[CompatibilityResult.Minor])

  test("Union расширен (добавлен вариант) → Minor"):
    val before = contract(Field("status", FieldType.Union(List(FieldType.Str)),                   required = true))
    val after  = contract(Field("status", FieldType.Union(List(FieldType.Str, FieldType.Int32)), required = true))
    assert(classify(before, after).isInstanceOf[CompatibilityResult.Minor])

  test("тип обёрнут в Union с исходным типом → Minor"):
    val before = contract(Field("value", FieldType.Str, required = true))
    val after  = contract(Field("value", FieldType.Union(List(FieldType.Str, FieldType.Int32)), required = true))
    assert(classify(before, after).isInstanceOf[CompatibilityResult.Minor])

  //  Major 

  test("добавлено обязательное поле → Major"):
    val before = contract(str("id"))
    val after  = contract(str("id"), str("email", required = true))
    assert(classify(before, after).isInstanceOf[CompatibilityResult.Major])

  test("поле удалено → Major"):
    val before = contract(str("id"), str("email"))
    val after  = contract(str("id"))
    assert(classify(before, after).isInstanceOf[CompatibilityResult.Major])

  test("тип поля изменён → Major"):
    val before = contract(str("id"), str("age"))
    val after  = contract(str("id"), int("age"))
    assert(classify(before, after).isInstanceOf[CompatibilityResult.Major])

  test("optional → required → Major"):
    val before = contract(str("id"), str("email", required = false))
    val after  = contract(str("id"), str("email", required = true))
    assert(classify(before, after).isInstanceOf[CompatibilityResult.Major])

  test("Union сужен (убран вариант) → Major"):
    val before = contract(Field("status", FieldType.Union(List(FieldType.Str, FieldType.Int32)), required = true))
    val after  = contract(Field("status", FieldType.Union(List(FieldType.Str)),                   required = true))
    assert(classify(before, after).isInstanceOf[CompatibilityResult.Major])

  //  Наихудший severity побеждает ─

  test("Minor + Major → Major"):
    val before = contract(str("id"), str("email"))
    // email удалён (Major) + phone необязательный добавлен (Minor)
    val after  = contract(str("id"), str("phone", required = false))
    assert(classify(before, after).isInstanceOf[CompatibilityResult.Major])

  test("Patch + Minor → Minor"):
    val before = contract(str("id")).copy(description = Some("old"))
    // phone необязательный добавлен (Minor) + description изменён (Patch)
    val after  = contract(str("id"), str("phone", required = false)).copy(description = Some("new"))
    assert(classify(before, after).isInstanceOf[CompatibilityResult.Minor])

  test("Patch + Patch → Patch"):
    val before = contract(str("id"), str("name").copy(deprecated = false)).copy(description = Some("old"))
    val after  = contract(str("id"), str("name").copy(deprecated = true)).copy(description = Some("new"))
    assert(classify(before, after).isInstanceOf[CompatibilityResult.Patch])

  //  Дифф содержит правильные данные ─

  test("дифф содержит удалённое поле"):
    val emailField = str("email")
    val before     = contract(str("id"), emailField)
    val after      = contract(str("id"))
    val result     = classify(before, after)
    val diffs      = result.diffs

    assert(diffs.exists {
      case VersionDiff.FieldRemoved("email", _) => true
      case _                                    => false
    })

  test("дифф содержит добавленное поле"):
    val phoneField = str("phone", required = false)
    val before     = contract(str("id"))
    val after      = contract(str("id"), phoneField)
    val result     = classify(before, after)

    assert(result.diffs.exists {
      case VersionDiff.FieldAdded(f) => f.name == "phone"
      case _                         => false
    })

  //  nextVersion 

  test("nextVersion: Identical не меняет версию"):
    val v = SemanticVersion(1, 2, 3)
    assertEquals(CompatibilityResult.Identical.nextVersion(v), v)

  test("nextVersion: Patch bumps patch"):
    val before = contract(str("id")).copy(description = Some("old"))
    val after  = contract(str("id")).copy(description = Some("new"))
    assertEquals(classify(before, after).nextVersion(SemanticVersion(1, 2, 3)), SemanticVersion(1, 2, 4))

  test("nextVersion: Minor bumps minor и сбрасывает patch"):
    val before = contract(str("id"))
    val after  = contract(str("id"), str("phone", required = false))
    assertEquals(classify(before, after).nextVersion(SemanticVersion(1, 2, 3)), SemanticVersion(1, 3, 0))

  test("nextVersion: Major bumps major и сбрасывает minor и patch"):
    val before = contract(str("id"), str("email"))
    val after  = contract(str("id"))
    assertEquals(classify(before, after).nextVersion(SemanticVersion(1, 2, 3)), SemanticVersion(2, 0, 0))

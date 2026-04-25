package io.compact.core

/** Граничные случаи классификатора совместимости.
 *
 * Фокус — сложные сценарии которые легко пропустить.
 */
class CompatibilityClassifierEdgeCasesSpec extends munit.FunSuite:

  private val owner = OwnerId("test")

  private def contract(fields: Field*): Contract =
    Contract.create(
      id     = ContractId("test"),
      name   = ContractName("Test"),
      fields = fields.toList,
      owner  = owner,
    )

  private def str(name: String, required: Boolean = true): Field =
    Field(name, FieldType.Str, required)

  private def classify(before: Contract, after: Contract): CompatibilityResult =
    CompatibilityClassifier.classify(before, after)

  //  Пустые контракты 

  test("оба контракта без полей → Identical"):
    val c = contract()
    assertEquals(classify(c, c), CompatibilityResult.Identical)

  test("был без полей, добавили обязательное → Major"):
    val before = contract()
    val after  = contract(str("id"))
    assert(classify(before, after).isInstanceOf[CompatibilityResult.Major])

  test("был без полей, добавили опциональное → Minor"):
    val before = contract()
    val after  = contract(str("id", required = false))
    assert(classify(before, after).isInstanceOf[CompatibilityResult.Minor])

  //  Несколько полей одновременно 

  test("добавлены 3 опциональных поля → Minor (а не три Minor)"):
    val before = contract(str("id"))
    val after  = contract(
      str("id"),
      str("a", required = false),
      str("b", required = false),
      str("c", required = false),
    )
    val result = classify(before, after)
    assert(result.isInstanceOf[CompatibilityResult.Minor])
    assertEquals(result.diffs.size, 3)

  test("3 опциональных + 1 обязательное → Major (наихудший побеждает)"):
    val before = contract(str("id"))
    val after  = contract(
      str("id"),
      str("required-new"),           // Major
      str("opt-a", required = false), // Minor
      str("opt-b", required = false), // Minor
      str("opt-c", required = false), // Minor
    )
    assert(classify(before, after).isInstanceOf[CompatibilityResult.Major])

  test("удалено 3 поля → Major, все три в diffs"):
    val before = contract(str("id"), str("a"), str("b"), str("c"))
    val after  = contract(str("id"))
    val result = classify(before, after)
    assert(result.isInstanceOf[CompatibilityResult.Major])
    assertEquals(result.diffs.count(_.isInstanceOf[VersionDiff.FieldRemoved]), 3)

  //  Переименование поля 

  test("переименование = удаление + добавление → Major (удаление ломает)"):
    // compact не отслеживает переименование явно — это breaking change
    val before = contract(str("id"), str("user_email"))
    val after  = contract(str("id"), str("userEmail"))  // переименовали
    val result = classify(before, after)
    assert(result.isInstanceOf[CompatibilityResult.Major])
    // В diffs: удалён user_email + добавлен userEmail (как required = Major)
    assert(result.diffs.exists {
      case VersionDiff.FieldRemoved("user_email", _) => true
      case _                                         => false
    })

  //  Collection тип ─

  test("Collection(Str) → Collection(Int32) → Major"):
    val before = contract(Field("tags", FieldType.Collection(FieldType.Str), required = true))
    val after  = contract(Field("tags", FieldType.Collection(FieldType.Int32), required = true))
    assert(classify(before, after).isInstanceOf[CompatibilityResult.Major])

  test("Str → Collection(Str) → Major (разная структура)"):
    val before = contract(Field("tag",  FieldType.Str, required = true))
    val after  = contract(Field("tag",  FieldType.Collection(FieldType.Str), required = true))
    assert(classify(before, after).isInstanceOf[CompatibilityResult.Major])

  test("добавлено поле Collection опциональное → Minor"):
    val before = contract(str("id"))
    val after  = contract(
      str("id"),
      Field("tags", FieldType.Collection(FieldType.Str), required = false),
    )
    assert(classify(before, after).isInstanceOf[CompatibilityResult.Minor])

  //  Mapping тип 

  test("Mapping(Str,Str) → Mapping(Str,Int32) → Major"):
    val before = contract(Field("meta", FieldType.Mapping(FieldType.Str, FieldType.Str), required = true))
    val after  = contract(Field("meta", FieldType.Mapping(FieldType.Str, FieldType.Int32), required = true))
    assert(classify(before, after).isInstanceOf[CompatibilityResult.Major])

  //  Nested тип 

  test("Nested(contract-a) → Nested(contract-b) → Major"):
    val before = contract(Field("addr", FieldType.Nested(ContractId("address-v1")), required = true))
    val after  = contract(Field("addr", FieldType.Nested(ContractId("address-v2")), required = true))
    assert(classify(before, after).isInstanceOf[CompatibilityResult.Major])

  test("добавлено опциональное Nested поле → Minor"):
    val before = contract(str("id"))
    val after  = contract(
      str("id"),
      Field("address", FieldType.Nested(ContractId("address")), required = false),
    )
    assert(classify(before, after).isInstanceOf[CompatibilityResult.Minor])

  //  Union граничные случаи ─

  test("Union с одним вариантом расширяется → Minor"):
    val before = contract(Field("v", FieldType.Union(List(FieldType.Str)), required = true))
    val after  = contract(Field("v", FieldType.Union(List(FieldType.Str, FieldType.Int32)), required = true))
    assert(classify(before, after).isInstanceOf[CompatibilityResult.Minor])

  test("Union полностью заменён другим Union → Major"):
    val before = contract(Field("v", FieldType.Union(List(FieldType.Str)), required = true))
    val after  = contract(Field("v", FieldType.Union(List(FieldType.Int32)), required = true))
    assert(classify(before, after).isInstanceOf[CompatibilityResult.Major])

  test("Union → не-Union тип → Major"):
    val before = contract(Field("v", FieldType.Union(List(FieldType.Str, FieldType.Int32)), required = true))
    val after  = contract(Field("v", FieldType.Str, required = true))
    assert(classify(before, after).isInstanceOf[CompatibilityResult.Major])

  //  deprecated взаимодействие 

  test("deprecated + добавлено опциональное поле → Minor (deprecated = Patch, добавление = Minor)"):
    val before = contract(str("id"), str("legacy"))
    val after  = contract(
      str("id"),
      str("legacy").copy(deprecated = true),
      str("new-field", required = false),
    )
    assert(classify(before, after).isInstanceOf[CompatibilityResult.Minor])

  test("deprecated + удаление другого поля → Major"):
    val before = contract(str("id"), str("legacy"), str("other"))
    val after  = contract(
      str("id"),
      str("legacy").copy(deprecated = true),
      // other удалено → Major
    )
    assert(classify(before, after).isInstanceOf[CompatibilityResult.Major])

  test("повторное deprecated поля (уже deprecated) → нет лишних FieldDeprecated diffs"):
    val alreadyDeprecated = str("legacy").copy(deprecated = true)
    val before = contract(str("id"), alreadyDeprecated)
    val after  = contract(str("id"), alreadyDeprecated)  // deprecated не изменилось
    assertEquals(classify(before, after), CompatibilityResult.Identical)

  //  Tags + поля в одном изменении ─

  test("tags изменились + опциональное поле добавлено → Minor"):
    val before = contract(str("id")).copy(tags = List("v1"))
    val after  = contract(str("id"), str("age", required = false)).copy(tags = List("v1", "v2"))
    assert(classify(before, after).isInstanceOf[CompatibilityResult.Minor])

  test("tags изменились + поле удалено → Major"):
    val before = contract(str("id"), str("email")).copy(tags = List("v1"))
    val after  = contract(str("id")).copy(tags = List("v1", "v2"))
    assert(classify(before, after).isInstanceOf[CompatibilityResult.Major])

  //  CompatibilityResult методы 

  test("isBreaking: только Major = true"):
    assert(CompatibilityResult.Major(List.empty).isBreaking)
    assert(!CompatibilityResult.Minor(List.empty).isBreaking)
    assert(!CompatibilityResult.Patch(List.empty).isBreaking)
    assert(!CompatibilityResult.Identical.isBreaking)

  test("isSafe: всё кроме Major = true"):
    assert(CompatibilityResult.Identical.isSafe)
    assert(CompatibilityResult.Patch(List.empty).isSafe)
    assert(CompatibilityResult.Minor(List.empty).isSafe)
    assert(!CompatibilityResult.Major(List.empty).isSafe)

  test("show: читаемые строки"):
    assert(CompatibilityResult.Identical.show.contains("Identical"))
    assert(CompatibilityResult.Patch(List.empty).show.contains("Patch"))
    assert(CompatibilityResult.Minor(List.empty).show.contains("Minor"))
    assert(CompatibilityResult.Major(List.empty).show.contains("Major"))

  test("diffs: Identical → пустой список"):
    assertEquals(CompatibilityResult.Identical.diffs, List.empty)

  //  nextVersion цепочка 

  test("nextVersion: Major после Minor — bumps major, сбрасывает всё"):
    val v = SemanticVersion(1, 5, 3)
    assertEquals(
      CompatibilityResult.Major(List.empty).nextVersion(v),
      SemanticVersion(2, 0, 0),
    )

  test("nextVersion: несколько Patch не накапливаются — каждый bump отдельно"):
    val v = SemanticVersion(1, 0, 0)
    val v1 = CompatibilityResult.Patch(List.empty).nextVersion(v)  // 1.0.1
    val v2 = CompatibilityResult.Patch(List.empty).nextVersion(v1) // 1.0.2
    assertEquals(v2, SemanticVersion(1, 0, 2))

  //  Все типы полей — базовый smoke test ─

  test("все примитивные типы — изменение на другой тип = Major"):
    val primitives = List(
      FieldType.Str, FieldType.Int32, FieldType.Int64,
      FieldType.Float32, FieldType.Float64, FieldType.Bool,
      FieldType.Bytes, FieldType.Timestamp, FieldType.Uuid,
    )
    for
      from <- primitives
      to   <- primitives
      if from != to
    do
      val before = contract(Field("f", from, required = true))
      val after  = contract(Field("f", to,   required = true))
      assert(
        classify(before, after).isInstanceOf[CompatibilityResult.Major],
        s"$from → $to должно быть Major",
      )

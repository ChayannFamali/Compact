package io.compact.registry

import io.compact.core.*

class DependencyGraphSpec extends munit.FunSuite:

  private def contract(id: String, fields: Field*): Contract =
    Contract.create(
      id     = ContractId(id),
      name   = ContractName(id),
      fields = fields.toList,
      owner  = OwnerId("test"),
    )

  private def nested(id: String): Field =
    Field(s"ref-$id", FieldType.Nested(ContractId(id)), required = false)

  //  build 

  test("empty contracts → empty graph"):
    val graph = DependencyGraph.build(List.empty)
    assert(graph.isEmpty)

  test("contracts без Nested → пустые зависимости"):
    val c = contract("user-created", Field("id", FieldType.Uuid, required = true))
    val graph = DependencyGraph.build(List(c))
    assert(graph.directDependents(ContractId("user-created")).isEmpty)
    assert(graph.directDependencies(ContractId("user-created")).isEmpty)

  test("A → Nested(B): B.dependents содержит A"):
    val b = contract("address", Field("street", FieldType.Str, required = true))
    val a = contract("user", nested("address"))
    val graph = DependencyGraph.build(List(a, b))

    assertEquals(graph.directDependents(ContractId("address")), Set(ContractId("user")))
    assertEquals(graph.directDependencies(ContractId("user")), Set(ContractId("address")))

  test("несколько контрактов зависят от одного"):
    val shared = contract("common-id")
    val a      = contract("service-a", nested("common-id"))
    val b      = contract("service-b", nested("common-id"))
    val graph  = DependencyGraph.build(List(shared, a, b))

    assertEquals(
      graph.directDependents(ContractId("common-id")),
      Set(ContractId("service-a"), ContractId("service-b")),
    )

  test("один контракт зависит от нескольких"):
    val x     = contract("x")
    val y     = contract("y")
    val a     = contract("a", nested("x"), nested("y"))
    val graph = DependencyGraph.build(List(x, y, a))

    assertEquals(
      graph.directDependencies(ContractId("a")),
      Set(ContractId("x"), ContractId("y")),
    )

  //  Вложенные FieldType 

  test("Nested внутри Collection"):
    val b     = contract("item")
    val a     = contract("order", Field("items", FieldType.Collection(FieldType.Nested(ContractId("item"))), required = true))
    val graph = DependencyGraph.build(List(a, b))

    assertEquals(graph.directDependents(ContractId("item")), Set(ContractId("order")))

  test("Nested внутри Union"):
    val b     = contract("type-b")
    val a     = contract("a", Field("payload", FieldType.Union(List(FieldType.Str, FieldType.Nested(ContractId("type-b")))), required = true))
    val graph = DependencyGraph.build(List(a, b))

    assertEquals(graph.directDependents(ContractId("type-b")), Set(ContractId("a")))

  test("Nested внутри Mapping value"):
    val b     = contract("value-type")
    val a     = contract("a", Field("map", FieldType.Mapping(FieldType.Str, FieldType.Nested(ContractId("value-type"))), required = false))
    val graph = DependencyGraph.build(List(a, b))

    assertEquals(graph.directDependents(ContractId("value-type")), Set(ContractId("a")))

  //  transitiveDependents 

  test("transitiveDependents — цепочка A → B → C"):
    val c     = contract("c")
    val b     = contract("b", nested("c"))
    val a     = contract("a", nested("b"))
    val graph = DependencyGraph.build(List(a, b, c))

    // C изменился → B использует C, A использует B
    assertEquals(
      graph.transitiveDependents(ContractId("c")),
      Set(ContractId("b"), ContractId("a")),
    )

  test("transitiveDependents — нет зависимостей"):
    val a     = contract("standalone")
    val graph = DependencyGraph.build(List(a))
    assert(graph.transitiveDependents(ContractId("standalone")).isEmpty)

  test("transitiveDependents — не включает сам контракт"):
    val b     = contract("b")
    val a     = contract("a", nested("b"))
    val graph = DependencyGraph.build(List(a, b))

    val result = graph.transitiveDependents(ContractId("b"))
    assert(!result.contains(ContractId("b")))

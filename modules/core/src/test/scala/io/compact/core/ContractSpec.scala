package io.compact.core

class ContractSpec extends munit.FunSuite:

  test("Contract.create устанавливает версию 1.0.0"):
    val contract = Contract.create(
      id     = ContractId("user-created"),
      name   = ContractName("User Created"),
      fields = List(Field("id", FieldType.Uuid, required = true)),
      owner  = OwnerId("user-service"),
    )
    assertEquals(contract.version, SemanticVersion.Initial)
    assertEquals(contract.version.show, "1.0.0")

  test("ContractId валидация"):
    // корректные
    assertEquals(ContractId("user-created").value, "user-created")
    assertEquals(ContractId("order-placed-v2").value, "order-placed-v2")
    assertEquals(ContractId("a").value, "a")

    // некорректные
    intercept[IllegalArgumentException](ContractId(""))
    intercept[IllegalArgumentException](ContractId("User Created"))
    intercept[IllegalArgumentException](ContractId("-starts-with-dash"))
    intercept[IllegalArgumentException](ContractId("ends-with-dash-"))
    intercept[IllegalArgumentException](ContractId("has_underscore"))

  test("контракт с составными типами компилируется и создаётся"):
    val contract = Contract.create(
      id     = ContractId("order-placed"),
      name   = ContractName("Order Placed"),
      fields = List(
        Field("id",       FieldType.Uuid,                                    required = true),
        Field("items",    FieldType.Collection(FieldType.Nested(ContractId("order-item"))), required = true),
        Field("status",   FieldType.Union(List(FieldType.Str, FieldType.Int32)), required = true),
        Field("metadata", FieldType.Mapping(FieldType.Str, FieldType.Str),   required = false),
        Field("placedAt", FieldType.Timestamp,                                required = true),
        Field("comment",  FieldType.Str, required = false,
          description = Some("Комментарий к заказу"),
          deprecated  = false,
        ),
      ),
      owner       = OwnerId("order-service"),
      description = Some("Событие о размещении нового заказа"),
      tags        = List("orders", "billing"),
    )

    assertEquals(contract.fields.size, 6)
    assertEquals(contract.tags, List("orders", "billing"))
    assert(contract.description.isDefined)


  test("ContractError имеет читаемое сообщение"):
    val id  = ContractId("user-created")
    val err = ContractError.ContractNotFound(id)
    assert(err.message.contains("user-created"))
    assertEquals(err.getMessage, err.message)

  test("IncompatibleVersion формирует понятное сообщение"):
    val err = ContractError.IncompatibleVersion(
      contractId = ContractId("user-created"),
      expected   = SemanticVersion(1, 2, 0),
      received   = SemanticVersion(2, 0, 0),
    )
    assert(err.message.contains("1.2.0"))
    assert(err.message.contains("2.0.0"))
    assert(err.message.contains("user-created"))

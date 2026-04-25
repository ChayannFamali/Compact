package io.compact.kafka

import fs2.kafka.{Header, Headers}
import io.compact.core.*

class ContractHeadersSpec extends munit.FunSuite:

  private val contractId = ContractId("user-created")
  private val version    = SemanticVersion(1, 2, 0)
  private val minVersion = SemanticVersion(1, 0, 0)

  //  make 

  test("make создаёт заголовок с contractId"):
    val headers = ContractHeaders.make(contractId, version)
    val found   = headers.toChain.find(_.key == ContractHeaders.ContractIdHeader) 
    assert(found.isDefined)
    assertEquals(new String(found.get.value), "user-created")

  test("make создаёт заголовок с версией"):
    val headers = ContractHeaders.make(contractId, version)
    val found   = headers.toChain.find(_.key == ContractHeaders.ContractVersionHeader) 
    assert(found.isDefined)
    assertEquals(new String(found.get.value), "1.2.0")

  test("make создаёт ровно два заголовка"):
    val headers = ContractHeaders.make(contractId, version)
    assertEquals(headers.toChain.size, 2L) 

  //  readContractId 

  test("readContractId — корректный заголовок"):
    val headers = ContractHeaders.make(contractId, version)
    assertEquals(ContractHeaders.readContractId(headers), Some(contractId))

  test("readContractId — отсутствующий заголовок → None"):
    val headers = Headers.empty
    assertEquals(ContractHeaders.readContractId(headers), None)

  test("readContractId — невалидное значение (нарушает ContractId правила) → None"):
    val headers = Headers(Header(ContractHeaders.ContractIdHeader, "Has Spaces".getBytes))
    assertEquals(ContractHeaders.readContractId(headers), None)

  //  readVersion ─

  test("readVersion — корректный заголовок"):
    val headers = ContractHeaders.make(contractId, version)
    assertEquals(ContractHeaders.readVersion(headers), Some(version))

  test("readVersion — отсутствующий заголовок → None"):
    val headers = Headers.empty
    assertEquals(ContractHeaders.readVersion(headers), None)

  test("readVersion — невалидный формат версии → None"):
    val headers = Headers(Header(ContractHeaders.ContractVersionHeader, "not-a-version".getBytes))
    assertEquals(ContractHeaders.readVersion(headers), None)

  //  checkCompatibility 

  test("checkCompatibility — совместимая версия → Right(version)"):
    // Producer sends 1.2.0, consumer expects >= 1.0.0
    val headers = ContractHeaders.make(contractId, SemanticVersion(1, 2, 0))
    val result  = ContractHeaders.checkCompatibility(headers, contractId, minVersion)
    assertEquals(result, Right(SemanticVersion(1, 2, 0)))

  test("checkCompatibility — та же версия что минимальная → Right"):
    val headers = ContractHeaders.make(contractId, SemanticVersion(1, 0, 0))
    val result  = ContractHeaders.checkCompatibility(headers, contractId, SemanticVersion(1, 0, 0))
    assert(result.isRight)

  test("checkCompatibility — Major несовместимость → Left(IncompatibleVersion)"):
    // Producer sends 2.0.0, consumer expects >= 1.0.0 (different major)
    val headers = ContractHeaders.make(contractId, SemanticVersion(2, 0, 0))
    val result  = ContractHeaders.checkCompatibility(headers, contractId, minVersion)
    assert(result.isLeft)
    assert(result.left.exists(_.isInstanceOf[ContractError.IncompatibleVersion]))

  test("checkCompatibility — версия старше минимальной → Left"):
    // Producer sends 1.0.0, consumer expects >= 1.2.0
    val headers = ContractHeaders.make(contractId, SemanticVersion(1, 0, 0))
    val result  = ContractHeaders.checkCompatibility(headers, contractId, SemanticVersion(1, 2, 0))
    assert(result.isLeft)

  test("checkCompatibility — неверный contractId → Left(InvalidContract)"):
    val headers    = ContractHeaders.make(ContractId("other-contract"), version)
    val result     = ContractHeaders.checkCompatibility(headers, contractId, minVersion)
    assert(result.isLeft)
    assert(result.left.exists(_.isInstanceOf[ContractError.InvalidContract]))

  test("checkCompatibility — нет заголовка contractId → Left(SerializationError)"):
    val headers = Headers(
      Header(ContractHeaders.ContractVersionHeader, "1.0.0".getBytes),
    )
    val result = ContractHeaders.checkCompatibility(headers, contractId, minVersion)
    assert(result.isLeft)
    assert(result.left.exists(_.isInstanceOf[ContractError.SerializationError]))

  test("checkCompatibility — нет заголовка version → Left(SerializationError)"):
    val headers = Headers(
      Header(ContractHeaders.ContractIdHeader, "user-created".getBytes),
    )
    val result = ContractHeaders.checkCompatibility(headers, contractId, minVersion)
    assert(result.isLeft)
    assert(result.left.exists(_.isInstanceOf[ContractError.SerializationError]))

  test("checkCompatibility — пустые заголовки → Left"):
    val result = ContractHeaders.checkCompatibility(Headers.empty, contractId, minVersion)
    assert(result.isLeft)

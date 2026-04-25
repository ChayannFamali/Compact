package io.compact.core

/** Идентификаторы библиотеки — opaque types.
 *
 * Все идентификаторы изолированы через opaque types.
 * Нельзя передать ContractId туда где ожидается OwnerId — ошибка компиляции.
 */

//  ContractId 

opaque type ContractId = String

object ContractId:
  def apply(value: String): ContractId =
    require(value.nonEmpty, "ContractId не может быть пустым")
    require(
      value.matches("[a-z0-9][a-z0-9-]*[a-z0-9]|[a-z0-9]"),
      s"ContractId может содержать только строчные буквы, цифры и дефис, " +
        s"не начинается и не заканчивается дефисом: '$value'",
    )
    value

  extension (id: ContractId)
    def value: String = id
    def show: String  = id

//  ContractName 

opaque type ContractName = String

object ContractName:
  def apply(value: String): ContractName =
    require(value.nonEmpty, "ContractName не может быть пустым")
    value

  extension (name: ContractName)
    def value: String = name
    def show: String  = name

//  OwnerId 

opaque type OwnerId = String

object OwnerId:
  def apply(value: String): OwnerId =
    require(value.nonEmpty, "OwnerId не может быть пустым")
    value

  extension (id: OwnerId)
    def value: String = id
    def show: String  = id

//  ConsumerId 

opaque type ConsumerId = String

object ConsumerId:
  def apply(value: String): ConsumerId =
    require(value.nonEmpty, "ConsumerId не может быть пустым")
    value

  extension (id: ConsumerId)
    def value: String = id
    def show: String  = id

package io.compact.circe

import io.circe.*
import io.circe.syntax.*
import io.compact.core.*

import scala.deriving.Mirror
import scala.compiletime.*

/** Typeclass объединяющий Encoder и Decoder с контрактом.
 *
 * Является свидетельством (evidence) что тип A совместим с contract:
 *  - Все required поля контракта присутствуют в A
 *  - Required поля → non-Option тип
 *  - Optional поля → Option[?] тип
 *
 * Создавать через [[ContractCodec.derived]].
 *
 * Пример:
 * {{{
 * import io.compact.circe.BuiltinCodecs.given
 *
 * case class UserCreated(id: UUID, email: String, age: Option[Int])
 *
 * given ContractCodec[UserCreated] =
 *   ContractCodec.derived[UserCreated](UserCreatedContract)
 *
 * // Использование
 * val json    = codec.encode(UserCreated(...))
 * val decoded = codec.decode(json)
 * }}}
 */
final class ContractCodec[A](
  val encoder:  Encoder[A],
  val decoder:  Decoder[A],
  val contract: Contract,
):
  /** Кодирует значение в JSON. None поля не включаются в результат. */
  def encode(value: A): Json =
    encoder(value)

  /** Кодирует значение в отформатированный JSON */
  def encodeString(value: A): String =
    encoder(value).spaces2

  /** Декодирует JSON в A */
  def decode(json: Json): Decoder.Result[A] =
    decoder.decodeJson(json)

  /** Декодирует JSON строку в A */
  def decodeString(jsonStr: String): Either[String, A] =
    io.circe.parser.parse(jsonStr)
      .left.map(_.message)
      .flatMap(decoder.decodeJson(_).left.map(_.message))

object ContractCodec:

  def apply[A](using cc: ContractCodec[A]): ContractCodec[A] = cc

  /** Создаёт ContractCodec для A с проверкой совместимости с контрактом.
   *
   * Проверка происходит при инициализации (startup), не при обработке сообщений.
   * Бросает [[ContractValidationException]] если A несовместим с contract.
   *
   * Что проверяется (V1):
   *  - Presence: required поля контракта присутствуют в case class
   *  - Optionality: required → non-Option, optional → Option[?]
   *
   * Что проверяется в compile time (прямо сейчас, через inline/Mirror):
   *  - Имена полей case class
   *  - Является ли каждое поле Option[?]
   *
   * Compile-time ошибка компиляции (не startup) → V2 через macros.
   */
  inline def derived[A <: Product](contract: Contract)(
    using m: Mirror.ProductOf[A]
  ): ContractCodec[A] =
    // ↓ Compile time: извлекаем имена и optionality через Mirror
    val labels     = constLabels[m.MirroredElemLabels]
    val isOptional = getIsOptional[m.MirroredElemTypes]
    val fieldInfo  = labels.zip(isOptional)

    // ↓ Startup time: сравниваем с Contract
    val errors = validate(contract, fieldInfo)
    if errors.nonEmpty then
      throw ContractValidationException(contract, errors)

    // ↓ Compile time: summon-им Encoder/Decoder для каждого поля
    val enc = buildEncoder[A, m.MirroredElemTypes](labels)
    val dec = buildDecoder[A, m.MirroredElemTypes](labels)

    new ContractCodec[A](enc, dec, contract)

  //  Compile-time извлечение структуры 

  /** Извлекает имена полей из Mirror labels в compile time */
  private inline def constLabels[T <: Tuple]: List[String] =
    constValueTuple[T].toList.asInstanceOf[List[String]]

  /** Для каждого поля определяет является ли оно Option[?] — в compile time */
  private inline def getIsOptional[T <: Tuple]: List[Boolean] =
    inline erasedValue[T] match
      case _: EmptyTuple       => Nil
      case _: (Option[?] *: t) => true  :: getIsOptional[t]
      case _: (? *: t)         => false :: getIsOptional[t]

  /** Summon Encoder для каждого типа из Tuple — в compile time */
  private inline def summonEncoders[T <: Tuple]: List[Encoder[?]] =
    inline erasedValue[T] match
      case _: EmptyTuple => Nil
      case _: (h *: t)   => summonInline[Encoder[h]] :: summonEncoders[t]

  /** Summon Decoder для каждого типа из Tuple — в compile time */
  private inline def summonDecoders[T <: Tuple]: List[Decoder[?]] =
    inline erasedValue[T] match
      case _: EmptyTuple => Nil
      case _: (h *: t)   => summonInline[Decoder[h]] :: summonDecoders[t]

  //  Startup-time валидация 

  private def validate(
    contract:  Contract,
    fieldInfo: List[(String, Boolean)],  // (name, isOptional)
  ): List[ContractValidationError] =
    val ccFieldMap = fieldInfo.toMap

    contract.fields.flatMap { f =>
      ccFieldMap.get(f.name) match

        case None if f.required =>
          List(ContractValidationError.RequiredFieldMissing(f.name, contract.id))

        case None =>
          List.empty // Optional поле контракта может отсутствовать в case class — OK

        case Some(isOpt) if f.required && isOpt =>
          List(ContractValidationError.OptionalityMismatch(
            fieldName  = f.name,
            contractId = contract.id,
            expected   = "non-Optional (поле required)",
            actual     = "Option[?]",
          ))

        case Some(isOpt) if !f.required && !isOpt =>
          List(ContractValidationError.OptionalityMismatch(
            fieldName  = f.name,
            contractId = contract.id,
            expected   = "Option[?] (поле optional)",
            actual     = "non-Optional",
          ))

        case _ => List.empty
    }

  // Построение кодеков 

  private inline def buildEncoder[A <: Product, Types <: Tuple](labels: List[String]): Encoder[A] =
    val encoders = summonEncoders[Types]
    Encoder.instance { a =>
      val product = a.asInstanceOf[scala.Product]
      val fields  =
        labels.zip(encoders).zipWithIndex.flatMap { case ((name, enc), i) =>
          val json = enc.asInstanceOf[Encoder[Any]].apply(product.productElement(i))
          // None кодируется как Json.Null — пропускаем, не пишем в JSON
          if json.isNull then None else Some(name -> json)
        }
      Json.obj(fields*)
    }.asInstanceOf[Encoder[A]]

  private inline def buildDecoder[A <: Product, Types <: Tuple](
    labels: List[String],
  )(using m: Mirror.ProductOf[A]): Decoder[A] =
    val decoders = summonDecoders[Types]
    Decoder.instance { cursor =>
      val results: List[Decoder.Result[Any]] =
        labels.zip(decoders).map { (name, dec) =>
          cursor.downField(name).as[Any](dec.asInstanceOf[Decoder[Any]])
        }
      // Собираем результаты: первая ошибка обрывает цепочку
      results
        .foldRight(Right(List.empty[Any]): Decoder.Result[List[Any]]) { (elem, acc) =>
          elem.flatMap(e => acc.map(e :: _))
        }
        .map { values =>
          // Конструируем A из списка значений через Mirror
          m.fromProduct(new scala.Product:
            def canEqual(that: Any): Boolean = true
            def productArity: Int            = values.size
            def productElement(n: Int): Any  = values(n)
          )
        }
    }

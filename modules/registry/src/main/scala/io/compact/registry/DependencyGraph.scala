package io.compact.registry

import io.compact.core.*

/** Граф зависимостей между контрактами.
 *
 * Зависимость возникает когда контракт A содержит поле [[FieldType.Nested]](B).
 * Реестр отслеживает эти связи чтобы при изменении B показать кто пострадает.
 *
 * @param dependents    contractId → контракты которые зависят от него (кто использует его через Nested)
 * @param dependencies  contractId → контракты от которых он зависит (кого он использует через Nested)
 */
final case class DependencyGraph(
  dependents:   Map[ContractId, Set[ContractId]],
  dependencies: Map[ContractId, Set[ContractId]],
):
  /** Прямые зависимости — контракты которые сломаются если contractId сломается */
  def directDependents(contractId: ContractId): Set[ContractId] =
    dependents.getOrElse(contractId, Set.empty)

  /** Прямые зависимости — от каких контрактов зависит contractId */
  def directDependencies(contractId: ContractId): Set[ContractId] =
    dependencies.getOrElse(contractId, Set.empty)

  /** Все транзитивные зависимости (рекурсивно) */
  def transitiveDependents(contractId: ContractId): Set[ContractId] =
    def go(id: ContractId, visited: Set[ContractId]): Set[ContractId] =
      val direct = directDependents(id).diff(visited)
      direct ++ direct.flatMap(dep => go(dep, visited ++ direct))
    go(contractId, Set(contractId))

  def isEmpty: Boolean = dependents.isEmpty && dependencies.isEmpty

object DependencyGraph:

  val empty: DependencyGraph = DependencyGraph(Map.empty, Map.empty)

  /** Строит граф зависимостей из списка контрактов */
  def build(contracts: List[Contract]): DependencyGraph =
    // contractId → set of contractIds it depends on (via Nested)
    val deps: Map[ContractId, Set[ContractId]] =
      contracts.map { c =>
        c.id -> c.fields.flatMap(f => extractNestedRefs(f.fieldType)).toSet
      }.toMap

    // Инвертируем: contractId → set of contractIds that depend on it
    val revDeps: Map[ContractId, Set[ContractId]] =
      deps.foldLeft(Map.empty[ContractId, Set[ContractId]]) { case (acc, (id, refs)) =>
        refs.foldLeft(acc) { case (a, ref) =>
          a.updated(ref, a.getOrElse(ref, Set.empty) + id)
        }
      }

    DependencyGraph(dependents = revDeps, dependencies = deps)

  private def extractNestedRefs(ft: FieldType): List[ContractId] = ft match
    case FieldType.Nested(contractId)    => List(contractId)
    case FieldType.Collection(element)   => extractNestedRefs(element)
    case FieldType.Mapping(key, value)   => extractNestedRefs(key) ++ extractNestedRefs(value)
    case FieldType.Union(variants)       => variants.flatMap(extractNestedRefs)
    case _                               => List.empty

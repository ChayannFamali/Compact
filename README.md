# compact

Библиотека для Scala 3 которая делает контракты между сервисами явными.

---

## Проблема

В микросервисной архитектуре сервисы общаются через Kafka и HTTP.
Контракт между ними существует — но нигде не записан явно.

Разработчик меняет тип поля в продюсере. Консьюмер об этом не знает.
Узнаёт в проде, когда начинает падать.

---

## Что такое контракт

Контракт — это описание данных которые путешествуют между сервисами.
Какие поля, какие типы, что обязательно, а что нет.

В compact контракт — это Scala 3 тип. Он живёт в отдельном модуле,
его видят и продюсер и консьюмер.

```scala
val UserCreatedContract = Contract.create(
  id     = ContractId("user-created"),
  name   = ContractName("User Created"),
  fields = List(
    Field("id",    FieldType.Uuid, required = true),
    Field("email", FieldType.Str,  required = true),
    Field("age",   FieldType.Int32, required = false),
  ),
  owner = OwnerId("user-service"),
)

case class UserCreated(id: UUID, email: String, age: Option[Int])

given ContractCodec[UserCreated] =
  ContractCodec.derived[UserCreated](UserCreatedContract)
```

Убрал поле `email` из case class? Ошибка компиляции.
Сделал `email: Option[String]` хотя в контракте `required = true`? Ошибка компиляции.

---

## Что умеет

**Версионирование без ручного управления**

compact сравнивает текущий контракт с зафиксированной версией и определяет
тяжесть изменения:

- Добавил необязательное поле → `Minor`. Сборка проходит.
- Удалил поле или изменил тип → `Major`. Сборка останавливается,
  пока не подтвердишь что уведомил консьюмеров.

**Kafka**

```scala
// Продюсер — отправляет только тип соответствующий контракту
producer.send(event.id.toString, event)
// Автоматически добавляет заголовки: X-Compact-Contract-Id, X-Compact-Contract-Version

// Консьюмер — проверяет версию при получении
ContractualConsumer.stream(settings, codec, binding,
  minVersion = SemanticVersion(1, 0, 0),
  strategy   = IncompatibilityStrategy.Skip,
)
```

**HTTP**

```scala
// Эндпоинт описан один раз
val createUserEndpoint = ContractEndpoint.post[CreateUserReq, CreateUserResp](
  pathSegments = List("users"),
  ...
  version      = SemanticVersion(1, 0, 0),  // → путь /v1/users
)

// Сервер
ServerBuilder.route(createUserEndpoint, logic)

// Клиент — генерируется из того же контракта, не нужно писать вручную
val createUser = ClientBuilder.make(createUserEndpoint, uri"http://user-service", client)
```

**Реестр**

Файловый реестр в репозитории. Знает кто продюсер, кто консьюмеры,
историю версий, граф зависимостей между контрактами.

```
contracts/
├── registry.json
├── user-created/
│   ├── contract.json      текущая версия
│   ├── v1.0.0.json        история
│   └── consumers.json     кто подписан и на какой версии
```

**CI/CD**

```bash
sbt compactCheck    # упадёт если консьюмеры несовместимы с текущей версией
sbt compactReport   # отчёт о всех контрактах
```

---

## Модули

| Модуль              | Зачем                                          |
|---------------------|------------------------------------------------|
| `compact-core`      | Базовые типы, версионирование, классификатор   |
| `compact-registry`  | Файловый реестр, граф зависимостей             |
| `compact-circe`     | JSON кодеки из контракта с проверкой в compile time |
| `compact-kafka`     | Типобезопасный продюсер и консьюмер            |
| `compact-http`      | tapir + http4s, автогенерация клиента          |
| `compact-sbt`       | SBT плагин для CI                              |

---

## Быстрый старт

```scala
// build.sbt
libraryDependencies += "io.compact" %% "compact-core"  % "0.1.0"
libraryDependencies += "io.compact" %% "compact-kafka" % "0.1.0"  // или compact-http
```

```scala
// 1. Описываешь контракт (shared модуль)
val OrderPlacedContract = Contract.create(...)
case class OrderPlaced(orderId: UUID, total: Long)
given ContractCodec[OrderPlaced] = ContractCodec.derived[OrderPlaced](OrderPlacedContract)

// 2. Продюсер
ContractualProducer.resource(settings, codec, binding).use { producer =>
  producer.send(order.orderId.toString, order)
}

// 3. Консьюмер
ContractualConsumer.stream(settings, codec, binding, minVersion, strategy)
  .evalMap(record => process(record.value) >> record.commit)
```

Полные примеры — в папке [`examples/`](examples/).

---

## Лицензия

[Apache 2.0](LICENSE)
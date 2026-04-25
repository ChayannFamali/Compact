# compact — HTTP пример

Показывает автогенерацию типобезопасного клиента из контракта.
**Не требует Docker** — только JVM.

## Что демонстрирует

- Контракт описывает запрос и ответ (`Contracts.scala`)
- Сервер генерируется из контракта — `ServerBuilder.route(endpoint, logic)`
- Клиент генерируется из контракта — `ClientBuilder.make(endpoint, baseUri, http4sClient)`
- URL с версией `/v1/users` добавляется автоматически из `SemanticVersion`
- Нет дублирования: один контракт → сервер + клиент + документация

## Быстрый старт (2 команды)

```bash
# Терминал 1 — сервер
sbt "httpExample/runMain io.compact.example.http.ServerApp"

# Терминал 2 — клиент
sbt "httpExample/runMain io.compact.example.http.ClientApp"

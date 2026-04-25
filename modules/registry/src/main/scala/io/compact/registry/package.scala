package io.compact.registry

/** compact-registry — файловый реестр контрактов.
  *
  * Читает и пишет контракты на диск. В V1 это файловая система.
  * В V2 станет отдельным сервисом, но интерфейс [[Registry]] не изменится.
  *
  * Структура на диске:
  * {{{
  * contracts/
  * ├── registry.json
  * ├── user-created/
  * │   ├── contract.json
  * │   ├── v1.0.0.json
  * │   └── consumers.json
  * └── order-placed/
  *     ├── contract.json
  *     └── consumers.json
  * }}}
  */

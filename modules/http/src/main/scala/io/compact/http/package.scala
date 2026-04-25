package io.compact.http

/** compact-http — tapir + http4s интеграция.
  *
  * Из контракта генерируется tapir Endpoint.
  * Из Endpoint — http4s Route на сервере и типобезопасный клиент.
  * OpenAPI документация получается бесплатно.
  *
  * Major версия автоматически добавляет /v1/, /v2/ в путь.
  */

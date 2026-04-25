package io.compact.kafka

/** compact-kafka — типобезопасный продюсер и консьюмер.
  *
  * Обёртка над fs2-kafka. Продюсер принимает только тип соответствующий контракту.
  * Консьюмер проверяет версию в заголовке сообщения перед десериализацией.
  *
  * Стратегии при несовместимой версии:
  *   - [[IncompatibilityStrategy.Fail]]       — упасть с ошибкой
  *   - [[IncompatibilityStrategy.Skip]]       — пропустить сообщение
  *   - [[IncompatibilityStrategy.DeadLetter]] — отправить в DLQ топик
  */

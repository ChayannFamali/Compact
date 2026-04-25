package io.compact.example.http

import cats.effect.{ExitCode, IO, IOApp}
import org.http4s.ember.client.EmberClientBuilder
import org.http4s.Uri
import io.compact.http.ClientBuilder

/** Запуск: sbt "httpExample/runMain io.compact.example.http.ClientApp"
 *
 * Требует запущенный ServerApp.
 */
object ClientApp extends IOApp:

  def run(args: List[String]): IO[ExitCode] =
    val baseUri = Uri.unsafeFromString(
      args.headOption.getOrElse("http://localhost:8080")
    )

    EmberClientBuilder.default[IO].build.use { httpClient =>

      // Клиент генерируется из контракта — не нужно писать HTTP код вручную
      val createUser = ClientBuilder.make(createUserEndpoint,    baseUri, httpClient)
      val getStats   = ClientBuilder.make(getUsersStatsEndpoint, baseUri, httpClient)

      for
        _ <- IO.println(
          s"""
             |╔══════════════════════════════════════════════════════╗
             |║         compact — HTTP Client Example                ║
             |╚══════════════════════════════════════════════════════╝
             |  Сервер: $baseUri
             |  Клиент сгенерирован из контракта — без ручного HTTP кода
             |""".stripMargin,
        )

        // Создаём пользователей
        _ <- IO.println("Создаём пользователей...")

        r1 <- createUser(CreateUserReq("alice@example.com", Some("Alice")))
        _  <- r1 match
          case Right(u) => IO.println(s"  ✓ Создан: id=${u.id.toString.take(8)}... email=${u.email}")
          case Left(e)  => IO.println(s"  ✗ Ошибка: ${e.message}")

        r2 <- createUser(CreateUserReq("bob@example.com", None))
        _  <- r2 match
          case Right(u) => IO.println(s"  ✓ Создан: id=${u.id.toString.take(8)}... email=${u.email}")
          case Left(e)  => IO.println(s"  ✗ Ошибка: ${e.message}")

        r3 <- createUser(CreateUserReq("carol@example.com", Some("Carol")))
        _  <- r3 match
          case Right(u) => IO.println(s"  ✓ Создан: id=${u.id.toString.take(8)}... email=${u.email}")
          case Left(e)  => IO.println(s"  ✗ Ошибка: ${e.message}")

        // Получаем статистику
        _ <- IO.println("\nПолучаем статистику...")
        s <- getStats(())
        _ <- s match
          case Right(stats) =>
            IO.println(s"  ✓ Пользователей: ${stats.total}") *>
              IO.println(s"  ✓ Последний:     ${stats.lastEmail.getOrElse("-")}")
          case Left(e) =>
            IO.println(s"  ✗ Ошибка: ${e.message}")

      yield ExitCode.Success
    }.handleErrorWith { err =>
      IO.println(s"\n✗ Не удалось подключиться к серверу: ${err.getMessage}") *>
        IO.println("  Убедись что ServerApp запущен.") *>
        IO.pure(ExitCode.Error)
    }

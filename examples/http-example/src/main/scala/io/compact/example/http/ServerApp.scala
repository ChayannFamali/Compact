package io.compact.example.http

import cats.effect.{ExitCode, IO, IOApp, Ref}
import com.comcast.ip4s.*
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.HttpRoutes
import io.compact.http.{ContractHttpError, ServerBuilder}

import java.time.Instant
import java.util.UUID

/** Запуск: sbt "httpExample/runMain io.compact.example.http.ServerApp" */
object ServerApp extends IOApp:

  def run(args: List[String]): IO[ExitCode] =
    for
      // Хранилище в памяти — Ref[IO] безопасен для конкурентного доступа
      usersRef  <- Ref.of[IO, List[CreateUserResp]](List.empty)
      routes     = buildRoutes(usersRef)
      exitCode  <- runServer(routes)
    yield exitCode

  private def buildRoutes(usersRef: Ref[IO, List[CreateUserResp]]): HttpRoutes[IO] =
    ServerBuilder.routes(
      // POST /v1/users
      ServerBuilder.route(
        createUserEndpoint,
        req =>
          for
            newUser <- IO.pure(
              CreateUserResp(
                id        = UUID.randomUUID(),
                email     = req.email,
                name      = req.name,
                createdAt = Instant.now(),
              )
            )
            _       <- usersRef.update(_ :+ newUser)
            _       <- IO.println(s"  → Создан пользователь: ${newUser.email}")
          yield Right(newUser),
      ),

      // GET /v1/users/stats
      ServerBuilder.route(
        getUsersStatsEndpoint,
        _ =>
          usersRef.get.map { users =>
            Right(UsersStats(
              total     = users.size,
              lastEmail = users.lastOption.map(_.email),
            ))
          },
      ),
    )

  private def runServer(routes: HttpRoutes[IO]): IO[ExitCode] =
    EmberServerBuilder
      .default[IO]
      .withHost(ipv4"0.0.0.0")
      .withPort(port"8080")
      .withHttpApp(routes.orNotFound)
      .build
      .use { server =>
        IO.println(
          s"""
             |╔══════════════════════════════════════════════════════╗
             |║         compact — HTTP Server Example                ║
             |╚══════════════════════════════════════════════════════╝
             |  Запущен: http://localhost:8080
             |
             |  Эндпоинты (из контракта, автоматически):
             |    POST http://localhost:8080${createUserEndpoint.fullPath}
             |    GET  http://localhost:8080${getUsersStatsEndpoint.fullPath}
             |
             |  Нажми Ctrl+C для остановки
             |""".stripMargin,
        ) *> IO.never
      }
      .as(ExitCode.Success)

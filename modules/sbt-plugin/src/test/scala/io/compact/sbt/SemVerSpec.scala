package io.compact.sbt

class SemVerSpec extends munit.FunSuite {

  test("parse — корректная версия") {
    assertEquals(SemVer.parse("1.2.3"), Right(SemVer(1, 2, 3)))
    assertEquals(SemVer.parse("0.0.0"), Right(SemVer(0, 0, 0)))
    assertEquals(SemVer.parse("10.20.30"), Right(SemVer(10, 20, 30)))
  }

  test("parse — некорректный формат") {
    assert(SemVer.parse("1.2").isLeft)
    assert(SemVer.parse("1.2.3.4").isLeft)
    assert(SemVer.parse("a.b.c").isLeft)
    assert(SemVer.parse("").isLeft)
  }

  test("show") {
    assertEquals(SemVer(1, 2, 3).show, "1.2.3")
  }

  test("ordering") {
    val versions = List(
      SemVer(2, 0, 0), SemVer(1, 5, 0), SemVer(1, 2, 3),
      SemVer(1, 2, 2), SemVer(0, 9, 9),
    )
    val sorted = versions.sorted
    assertEquals(sorted.head, SemVer(0, 9, 9))
    assertEquals(sorted.last, SemVer(2, 0, 0))
  }

  test("isBackwardCompatibleWith — совместимо") {
    assert(SemVer(1, 2, 0).isBackwardCompatibleWith(SemVer(1, 0, 0)))
    assert(SemVer(1, 0, 0).isBackwardCompatibleWith(SemVer(1, 0, 0)))
    assert(SemVer(1, 5, 3).isBackwardCompatibleWith(SemVer(1, 5, 0)))
  }

  test("isBackwardCompatibleWith — Major несовместимость") {
    assert(!SemVer(2, 0, 0).isBackwardCompatibleWith(SemVer(1, 0, 0)))
    assert(!SemVer(1, 0, 0).isBackwardCompatibleWith(SemVer(2, 0, 0)))
  }

  test("isBackwardCompatibleWith — producer старее минимальной версии") {
    assert(!SemVer(1, 0, 0).isBackwardCompatibleWith(SemVer(1, 2, 0)))
  }
}

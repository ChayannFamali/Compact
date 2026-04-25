package io.compact.core

class SemanticVersionSpec extends munit.FunSuite:

  test("parse — корректная версия"):
    assertEquals(SemanticVersion.parse("1.2.3"), Right(SemanticVersion(1, 2, 3)))
    assertEquals(SemanticVersion.parse("0.0.0"), Right(SemanticVersion(0, 0, 0)))
    assertEquals(SemanticVersion.parse("10.20.30"), Right(SemanticVersion(10, 20, 30)))

  test("parse — некорректная версия"):
    assert(SemanticVersion.parse("1.2").isLeft)
    assert(SemanticVersion.parse("1.2.3.4").isLeft)
    assert(SemanticVersion.parse("a.b.c").isLeft)
    assert(SemanticVersion.parse("").isLeft)
    assert(SemanticVersion.parse("-1.0.0").isLeft)

  test("show"):
    assertEquals(SemanticVersion(1, 2, 3).show, "1.2.3")
    assertEquals(SemanticVersion(0, 0, 0).show, "0.0.0")

  test("bump версий"):
    val v = SemanticVersion(1, 2, 3)
    assertEquals(v.bumpPatch, SemanticVersion(1, 2, 4))
    assertEquals(v.bumpMinor, SemanticVersion(1, 3, 0))
    assertEquals(v.bumpMajor, SemanticVersion(2, 0, 0))

  test("bumpMajor сбрасывает minor и patch"):
    val v = SemanticVersion(1, 5, 9)
    assertEquals(v.bumpMajor, SemanticVersion(2, 0, 0))

  test("ordering"):
    val versions = List(
      SemanticVersion(2, 0, 0),
      SemanticVersion(1, 5, 0),
      SemanticVersion(1, 2, 3),
      SemanticVersion(1, 2, 2),
      SemanticVersion(0, 9, 9),
    )
    val sorted = versions.sorted
    assertEquals(sorted.head, SemanticVersion(0, 9, 9))
    assertEquals(sorted.last, SemanticVersion(2, 0, 0))
    assertEquals(sorted(1), SemanticVersion(1, 2, 2))
    assertEquals(sorted(2), SemanticVersion(1, 2, 3))

  test("isBackwardCompatibleWith"):
    val v100 = SemanticVersion(1, 0, 0)
    val v110 = SemanticVersion(1, 1, 0)
    val v200 = SemanticVersion(2, 0, 0)

    // v1.1.0 совместима с v1.0.0 (producer новее)
    assert(v110.isBackwardCompatibleWith(v100))

    // v1.0.0 НЕ совместима с v1.1.0 (producer старее)
    assert(!v100.isBackwardCompatibleWith(v110))

    // Разные major — всегда несовместимы
    assert(!v200.isBackwardCompatibleWith(v100))
    assert(!v100.isBackwardCompatibleWith(v200))

    // Одинаковая версия — совместима
    assert(v100.isBackwardCompatibleWith(v100))

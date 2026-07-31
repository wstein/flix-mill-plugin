package flixmill

import utest.*

object FlixModuleTests extends TestSuite {
  def tests = Tests {
    test("builds a Flix CLI command") {
      val jar = os.pwd / "fixture" / "flix.jar"

      val actual = FlixCommand.arguments("java", jar, "run", Seq("hello", "world"))

      assert(actual == Seq("java", "-jar", jar.toString, "run", "hello", "world"))
    }

    test("does not add arguments when none are supplied") {
      val jar = os.pwd / "fixture" / "flix.jar"

      val actual = FlixCommand.arguments("java", jar, "check", Seq.empty)

      assert(actual == Seq("java", "-jar", jar.toString, "check"))
    }

    test("finds the sole package artifact") {
      val artifactDirectory = os.temp.dir()

      try {
        val packageFile = artifactDirectory / "example.fpkg"
        os.write(packageFile, "package")

        assert(FlixArtifact.packageFile(artifactDirectory) == packageFile)
      } finally os.remove.all(artifactDirectory)
    }

    test("rejects absent and ambiguous package artifacts") {
      val artifactDirectory = os.temp.dir()

      try {
        assertFails(FlixArtifact.packageFile(artifactDirectory))

        os.write(artifactDirectory / "one.fpkg", "one")
        os.write(artifactDirectory / "two.fpkg", "two")

        assertFails(FlixArtifact.packageFile(artifactDirectory))
      } finally os.remove.all(artifactDirectory)
    }
  }

  private def assertFails(value: => Any): Unit = {
    val failed =
      try {
        value
        false
      } catch {
        case _: IllegalStateException => true
      }

    assert(failed)
  }
}

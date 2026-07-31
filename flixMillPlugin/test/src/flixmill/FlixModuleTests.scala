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

    test("derives the package artifact path from the project directory") {
      val project = os.pwd / "fixture" / "example-project"

      assert(FlixArtifact.packageFile(project) == project / "artifact" / "example-project.fpkg")
    }
  }
}

package flixmill

import mill.*
import mill.api.{Discover, ExecResult}
import mill.testkit.{TestRootModule, UnitTester}
import utest.*

/** Task-level coverage for the extension points a consumer build overrides. */
object FlixTaskTests extends TestSuite {

  object defaultProject extends TestRootModule with FlixModule {
    lazy val millDiscover = Discover[this.type]
  }

  object nestedProject extends TestRootModule {
    object app extends FlixModule {
      override def flixWorkingDirectory = Task { moduleDir / "flix" }
    }

    lazy val millDiscover = Discover[this.type]
  }

  private def evaluated[T](
      result: Either[ExecResult.Failing[T], UnitTester.Result[T]]
  ): UnitTester.Result[T] =
    result.fold(failure => sys.error(s"task evaluation failed: $failure"), identity)

  def tests = Tests {
    test("resolves a Java executable that exists") {
      UnitTester(defaultProject, os.temp.dir()).scoped { eval =>
        val javaExecutable = evaluated(eval(defaultProject.flixJavaExecutable)).value

        assert(os.exists(os.Path(javaExecutable)))
      }
    }

    test("reads the Java executable as an input rather than caching it") {
      // A cached task records the JDK path in `out/` and keeps serving it after that JDK is
      // upgraded or moved, leaving every Flix task failing until the user runs `mill clean`.
      assert(defaultProject.flixJavaExecutable.isInstanceOf[Task.Input[?]])
    }

    test("falls back to a PATH lookup when the JDK has moved") {
      UnitTester(defaultProject, os.temp.dir()).scoped { eval =>
        val javaHome = sys.props("java.home")
        val afterMove =
          try {
            sys.props("java.home") = (os.temp.dir() / "relocated-jdk").toString
            evaluated(eval(defaultProject.flixJavaExecutable)).value
          } finally sys.props("java.home") = javaHome

        // Handing Flix an absolute path that no longer exists fails the build outright; a bare
        // `java` still works whenever the user has one on `PATH`.
        assert(afterMove == "java")
      }
    }

    test("runs Flix in the module directory by default") {
      UnitTester(defaultProject, os.temp.dir()).scoped { eval =>
        val workingDirectory = evaluated(eval(defaultProject.flixWorkingDirectory)).value

        assert(workingDirectory == defaultProject.moduleDir)
      }
    }

    test("follows an overridden working directory when locating the package") {
      UnitTester(nestedProject, os.temp.dir()).scoped { eval =>
        val workingDirectory = evaluated(eval(nestedProject.app.flixWorkingDirectory)).value

        assert(workingDirectory == nestedProject.app.moduleDir / "flix")
        assert(
          FlixArtifact.packageFile(workingDirectory) ==
            workingDirectory / "artifact" / "flix.fpkg"
        )
      }
    }
  }
}

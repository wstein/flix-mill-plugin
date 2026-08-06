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

  object managedManifestProject extends TestRootModule with FlixManifestModule {
    override def flixPackageDescription = Task { "A managed project." }
    override def flixPackageVersion = Task { "0.3.0" }
    override def flixLanguageVersion = Task { "0.75.1" }
    override def flixPackageAuthors = Task { Seq("Ada") }
    override def flixMvnDependencies = Task { Seq("org.postgresql:postgresql" -> "42.7.3") }

    lazy val millDiscover = Discover[this.type]
  }

  object nestedProject extends TestRootModule {
    object app extends FlixModule {
      override def flixWorkingDirectory = moduleDir / "flix"
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
      assert(defaultProject.flixWorkingDirectory == defaultProject.moduleDir)
    }

    test("writes the generated manifest where Flix looks for it") {
      // Flix locates its manifest by name in the directory it runs in, so this is the one file the
      // plugin writes into the project rather than into `Task.dest`.
      UnitTester(managedManifestProject, os.temp.dir()).scoped { eval =>
        val manifest = evaluated(eval(managedManifestProject.flixGeneratedManifest)).value

        assert(manifest.path == managedManifestProject.flixWorkingDirectory / "flix.toml")
        val contents = os.read(manifest.path)
        assert(contents.contains("version = \"0.3.0\""))
        assert(contents.contains("\"org.postgresql:postgresql\" = \"42.7.3\""))
      }
    }

    test("invalidates compilation when the generated manifest changes") {
      // `flixProjectInputs` is what `check` and `build` depend on. Leaving the generated manifest
      // out of it would let a dependency change compile against the previous one.
      UnitTester(managedManifestProject, os.temp.dir()).scoped { eval =>
        val inputs = evaluated(eval(managedManifestProject.flixProjectInputs))

        assert(inputs.evalCount > 0)
        assert(os.exists(managedManifestProject.flixWorkingDirectory / "flix.toml"))
      }
    }

    test("moves every tracked and generated path with the working directory") {
      UnitTester(nestedProject, os.temp.dir()).scoped { eval =>
        val workingDirectory = nestedProject.app.flixWorkingDirectory
        assert(workingDirectory == nestedProject.app.moduleDir / "flix")

        val jar = evaluated(eval(nestedProject.app.flixJar)).value
        val manifest = evaluated(eval(nestedProject.app.flixManifest)).value
        val sources = evaluated(eval(nestedProject.app.flixSourceDirectories)).value

        // Tracking inputs beside the module while the compiler reads them beside the manifest
        // would silently stop invalidating the build when a source file changes.
        assert(jar.path == workingDirectory / "flix.jar")
        assert(manifest.path == workingDirectory / "flix.toml")
        assert(sources.map(_.path) == Seq(workingDirectory / "src", workingDirectory / "test"))
        assert(
          FlixArtifact.packageFile(workingDirectory) ==
            workingDirectory / "artifact" / "flix.fpkg"
        )
      }
    }
  }
}

package flixmill

import mill.api.{FilesystemCheckerEnabled, PathRef}
import utest.*

object FlixModuleTests extends TestSuite {

  /** Mirrors how Mill guards a task's reads: reject anything the task did not declare, unless
    * the build has suspended the check.
    */
  private object RejectingChecker extends os.Checker {
    def onRead(path: os.ReadablePath): Unit =
      if (FilesystemCheckerEnabled.value) sys.error(s"Reading from $path not allowed")

    def onWrite(path: os.Path): Unit =
      if (FilesystemCheckerEnabled.value) sys.error(s"Writing to $path not allowed")
  }

  private def withProject[T](body: os.Path => T): T = {
    val project = os.temp.dir()
    try body(project)
    finally os.remove.all(project)
  }

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

    test("signs Flix output so that changing it invalidates the cached task") {
      withProject { project =>
        val classes = project / "build" / "class"
        os.write(classes / "Main.class", "bytecode", createFolders = true)

        val original = FlixArtifact.outputPathRef(classes)
        os.write.over(classes / "Main.class", "recompiled")
        val recompiled = FlixArtifact.outputPathRef(classes)

        // `Revalidate.Once` is what makes Mill re-check the signature when it reads the cached
        // result back; Mill's default of `Revalidate.Never` would replay a stale path forever.
        assert(original.revalidate == PathRef.Revalidate.Once)
        assert(original.path == classes)
        assert(original.sig != recompiled.sig)
      }
    }

    test("signs Flix output that a task is not otherwise allowed to read") {
      withProject { project =>
        val packageFile = FlixArtifact.packageFile(project)
        os.write(packageFile, "fpkg", createFolders = true)

        os.checker.withValue(RejectingChecker) {
          // Hashing Flix-owned output directly is what Mill rejects ...
          assert(scala.util.Try(PathRef(packageFile)).isFailure)

          // ... so `outputPathRef` has to suspend the check to sign it.
          assert(FlixArtifact.outputPathRef(packageFile).path == packageFile)
        }
      }
    }
  }
}

package flixmill

import utest.*

/** Integration coverage for the official compiler, enabled with `FLIX_JAR`. */
object FlixCliIntegrationTests extends TestSuite {
  def tests = Tests {
    test("initializes and builds a project") {
      sys.env.get("FLIX_JAR").foreach(runIntegration)
    }
  }

  private def runIntegration(jarPath: String): Unit = {
    val jar = os.Path(jarPath, os.pwd)
    val project = os.temp.dir()

    try {
      os.copy.over(jar, project / "flix.jar", createFolders = true)

      invoke(project, "init", "--yes")
      invoke(project, "check")
      invoke(project, "build")
      invoke(project, "test")
      invoke(project, "run")
      invoke(project, "build-pkg")

      assert(os.isDir(project / "build" / "class"))
      assert(os.isFile(FlixArtifact.packageFile(project / "artifact")))
    } finally os.remove.all(project)
  }

  private def invoke(project: os.Path, command: String*): Unit =
    FlixCommand.execute("java", project / "flix.jar", project, command.head, command.tail)
}

package flixmill

import mill.testkit.IntegrationTester
import utest.*

/** Verifies that a consumer can resolve the locally published plugin. */
object ConsumerIntegrationTests extends TestSuite {
  def tests = Tests {
    test("loads the published plugin and builds a Flix project") {
      sys.env.get("FLIX_JAR").foreach(runConsumerBuild)
    }
  }

  private def runConsumerBuild(jarPath: String): Unit = {
    val resources = os.Path(sys.env("MILL_TEST_RESOURCE_DIR"))
    val tester = new IntegrationTester(
      daemonMode = true,
      workspaceSourcePath = resources / "consumer-project",
      millExecutable = os.Path(sys.env("MILL_EXECUTABLE_PATH"))
    )
    val project = tester.workspacePath / "app"
    os.copy.over(os.Path(jarPath, os.pwd), project / "flix.jar", createFolders = true)

    val build = tester.eval("app.build")
    assert(build.isSuccess)
    assert(os.isDir(project / "build" / "class"))

    val test = tester.eval("app.test")
    assert(test.isSuccess)

    val run = tester.eval("app.run")
    assert(run.isSuccess)
    assert(run.out.contains("Hello World!"))

    val packageBuild = tester.eval("app.buildPkg")
    assert(packageBuild.isSuccess)
    assert(os.isFile(FlixArtifact.packageFile(project)))
  }
}

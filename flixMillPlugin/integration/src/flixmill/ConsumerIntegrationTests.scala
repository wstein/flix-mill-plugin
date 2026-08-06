package flixmill

import mill.testkit.IntegrationTester
import utest.*

/** Verifies that a consumer build can resolve and use the published plugin.
  *
  * This is the release gate, so a missing `FLIX_JAR` fails the suite instead of skipping it: a
  * silent pass here would let broken coordinates, POM metadata, or meta-build imports ship.
  */
object ConsumerIntegrationTests extends TestSuite {

  /** Version literal in the fixture's `build.mill`, substituted on every run. */
  private val PlaceholderVersion = "0.0.0-PLACEHOLDER"

  def tests = Tests {
    test("loads the published plugin and builds a Flix project") {
      val jarPath = sys.env.getOrElse(
        "FLIX_JAR",
        sys.error("FLIX_JAR must point at a Flix compiler JAR to run the consumer gate")
      )
      runConsumerBuild(jarPath)
    }
  }

  private def runConsumerBuild(jarPath: String): Unit = {
    val resources = os.Path(sys.env("MILL_TEST_RESOURCE_DIR"))
    val tester = new IntegrationTester(
      daemonMode = true,
      workspaceSourcePath = resources / "consumer-project",
      millExecutable = os.Path(sys.env("MILL_EXECUTABLE_PATH"))
    )

    // `close` removes the process-id file that tells the spawned Mill daemon to exit; without it
    // every run, including a failing one, leaks a daemon JVM holding the workspace.
    try {
      // The fixture carries a placeholder so it can never resolve a leftover build of some other
      // version; every run exercises exactly what this build published.
      tester.modifyFile(
        tester.workspacePath / "build.mill",
        _.replace(PlaceholderVersion, sys.env("MILL_PLUGIN_VERSION"))
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

      assertJvmModuleCallsFlixCode(tester, project)
      assertJointCompilation(tester, jarPath)
      assertRebuildsAfterOutputRemoval(tester, project)
      assertHonorsWorkingDirectoryOverride(tester, jarPath)
    } finally tester.close()
  }

  /** A JVM module must be able to compile and run against the Flix module's output.
    *
    * This is the whole point of `flixClasspath`, and nothing short of running it shows that the
    * exported class is on the classpath under the name a Java caller writes. Compiling alone would
    * pass with an empty classpath entry if the Flix build had silently produced nothing.
    */
  private def assertJvmModuleCallsFlixCode(tester: IntegrationTester, project: os.Path): Unit = {
    val jar = tester.eval("app.buildJar")
    assert(jar.isSuccess)
    assert(os.isFile(FlixArtifact.jarFile(project)))

    val run = tester.eval("javaConsumer.run")
    assert(run.isSuccess)
    assert(run.out.contains("sum = 42"))
  }

  /** Java and Flix must compile when they reference each other inside one module.
    *
    * There is no build order for this without generated stubs: `Helper` calls the Flix facade and
    * the Flix module calls `Helper`. Running it is what makes the test evidence -- the stub facade
    * throws on every call, so output that reads back correctly can only have come from the real
    * one.
    */
  private def assertJointCompilation(tester: IntegrationTester, jarPath: String): Unit = {
    val project = tester.workspacePath / "joint"
    os.copy.over(os.Path(jarPath, os.pwd), project / "flix.jar", createFolders = true)

    val run = tester.eval("joint.run")
    assert(run.isSuccess)
    assert(run.out.contains("Hello, Mill!")) // Java called Flix.
    assert(run.out.contains("HELLO")) // Flix called Java.

    // The stub classes must reach no run classpath. They would shadow the real facade, and every
    // exported call would throw instead of running -- which the assertions above would catch, but
    // only for the two methods they happen to exercise.
    val runClasspath = tester.eval(("show", "joint.runClasspath"))
    assert(runClasspath.isSuccess)
    assert(!runClasspath.out.contains("flixStubs"))
  }

  /** A cached `build` must not keep pointing at output that has since been removed. */
  private def assertRebuildsAfterOutputRemoval(
      tester: IntegrationTester,
      project: os.Path
  ): Unit = {
    val classes = project / "build" / "class"

    val cached = tester.eval("app.build")
    assert(cached.isSuccess)
    assert(os.isDir(classes))

    os.remove.all(classes)
    val rebuilt = tester.eval("app.build")
    assert(rebuilt.isSuccess)
    assert(os.isDir(classes))
  }

  /** Overriding `flixWorkingDirectory` must move the compiler, its inputs, and its output. */
  private def assertHonorsWorkingDirectoryOverride(
      tester: IntegrationTester,
      jarPath: String
  ): Unit = {
    val workingDirectory = tester.workspacePath / "nested" / "flix"
    os.copy.over(os.Path(jarPath, os.pwd), workingDirectory / "flix.jar", createFolders = true)

    val build = tester.eval("nested.build")
    assert(build.isSuccess)
    assert(os.isDir(workingDirectory / "build" / "class"))

    val packageBuild = tester.eval("nested.buildPkg")
    assert(packageBuild.isSuccess)
    // Named for the directory Flix actually packaged, not for the Mill module.
    assert(os.isFile(FlixArtifact.packageFile(workingDirectory)))
    assert(!os.exists(tester.workspacePath / "nested" / "artifact"))
  }
}

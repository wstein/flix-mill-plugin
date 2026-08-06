package flixmill

import mill.*
import mill.api.{Args, FilesystemCheckerEnabled, PathRef}
import mill.util.Jvm

/** Mill tasks for a Flix project that uses a project-local `flix.jar`. */
trait FlixModule extends Module {

  /** Java executable used to launch the Flix compiler.
    *
    * Resolved on every run rather than cached, so upgrading or moving the JDK cannot leave a stale
    * absolute path behind. `Jvm.javaExe` appends the platform's executable suffix and falls back to
    * a `PATH` lookup when `java.home` holds no `java` binary.
    */
  def flixJavaExecutable = Task.Input { Jvm.javaExe }

  /** Directory in which Flix commands execute and locate `flix.toml`.
    *
    * Every path the plugin tracks or validates is relative to this directory, so overriding it
    * moves the compiler JAR, the manifest, the source roots, and the generated output together. It
    * is a plain `def` rather than a task because Mill's source tasks cannot depend on one.
    */
  def flixWorkingDirectory: os.Path = moduleDir

  /** Project-local Flix compiler JAR. */
  def flixJar = Task.Source(flixWorkingDirectory / "flix.jar")

  /** Flix manifest. */
  def flixManifest = Task.Source(flixWorkingDirectory / "flix.toml")

  /** Standard Flix source roots. Override to track additional project inputs. */
  def flixSourceDirectories =
    Task.Sources(flixWorkingDirectory / "src", flixWorkingDirectory / "test")

  /** Inputs that invalidate Flix compilation, excluding generated `build/` and `artifact/` files.
    */
  def flixProjectInputs = Task {
    flixManifest()
    flixSourceDirectories()
  }

  /** Type-check the project without running it. */
  def check = Task {
    flixProjectInputs()
    FlixCommand.execute(flixJavaExecutable(), flixJar().path, flixWorkingDirectory, "check")
    Task.dest
  }

  /** Compile the project and return Flix's generated JVM class directory. */
  def build = Task {
    flixProjectInputs()
    val projectDirectory = flixWorkingDirectory
    FlixCommand.execute(flixJavaExecutable(), flixJar().path, projectDirectory, "build")
    val classes = projectDirectory / "build" / "class"
    require(os.exists(classes), s"Flix build completed without creating $classes")
    FlixArtifact.outputPathRef(classes)
  }

  /** Run the project's Flix test suite. */
  def test(args: Args) = Task.Command {
    flixProjectInputs()
    FlixCommand.execute(
      flixJavaExecutable(),
      flixJar().path,
      flixWorkingDirectory,
      "test",
      args.value
    )
  }

  /** Compile and run the project, forwarding arguments to Flix. */
  def run(args: Args) = Task.Command {
    flixProjectInputs()
    FlixCommand.execute(
      flixJavaExecutable(),
      flixJar().path,
      flixWorkingDirectory,
      "run",
      args.value
    )
  }

  /** Build the project's `.fpkg` artifact in its `artifact/` directory. */
  def buildPkg = Task {
    flixProjectInputs()
    val projectDirectory = flixWorkingDirectory
    FlixCommand.execute(flixJavaExecutable(), flixJar().path, projectDirectory, "build-pkg")
    val packageFile = FlixArtifact.packageFile(projectDirectory)
    require(os.exists(packageFile), s"Flix build-pkg completed without creating $packageFile")
    FlixArtifact.outputPathRef(packageFile)
  }

  /** Initialize a new Flix project in the module directory. */
  def init(args: Args) = Task.Command {
    FlixCommand.execute(
      flixJavaExecutable(),
      flixJar().path,
      flixWorkingDirectory,
      "init",
      args.value
    )
  }

  /** Show the help text supported by the configured Flix compiler. */
  def flixHelp(args: Args) = Task.Command {
    FlixCommand.execute(
      flixJavaExecutable(),
      flixJar().path,
      flixWorkingDirectory,
      "--help",
      args.value
    )
  }
}

private[flixmill] object FlixCommand {
  def arguments(
      javaExecutable: String,
      jar: os.Path,
      subcommand: String,
      args: Seq[String]
  ): Seq[String] =
    Seq(javaExecutable, "-jar", jar.toString, subcommand) ++ args

  def execute(
      javaExecutable: String,
      jar: os.Path,
      workingDirectory: os.Path,
      subcommand: String,
      args: Seq[String] = Seq.empty
  ): Unit =
    os.proc(arguments(javaExecutable, jar, subcommand, args))
      .call(
        cwd = workingDirectory,
        stdout = os.Inherit,
        stderr = os.Inherit
      )

  /** Runs a subcommand that reports diagnostics as JSON, and reports them.
    *
    * The compiler's console output is written for a person: prose, source excerpts, and
    * decorations. Recovering a file and a line from it means matching on wording that is free to
    * change, so `--diagnostics-json` exists to say the same thing in a shape a build tool can read.
    *
    * Only stdout carries the document; progress goes to stderr and is inherited into the build log
    * as before.
    */
  def executeReportingDiagnostics(
      javaExecutable: String,
      jar: os.Path,
      workingDirectory: os.Path,
      subcommand: String,
      args: Seq[String] = Seq.empty
  ): Unit = {
    val invocation = os.proc(arguments(javaExecutable, jar, subcommand, args :+ "--diagnostics-json"))
      .call(cwd = workingDirectory, stdout = os.Pipe, stderr = os.Inherit, check = false)

    FlixDiagnostics.parse(invocation.out.text()) match {
      case None if invocation.exitCode == 0 =>
        // Not a protocol document and no failure to explain it: an older compiler without
        // `--diagnostics-json`, or a crash. Treating that as "no diagnostics" would let a broken
        // build pass, so it is a failure of its own.
        sys.error(
          s"Flix $subcommand produced no readable diagnostics. The compiler may predate " +
            s"'--diagnostics-json'.\n${invocation.out.text()}"
        )
      case None =>
        sys.error(s"Flix $subcommand failed:\n${invocation.out.text()}")
      case Some(diagnostics) =>
        diagnostics.foreach(d => System.err.println(d.render))
        if (invocation.exitCode != 0) {
          sys.error(
            s"Flix $subcommand failed with ${diagnostics.length} error(s).\n\n" +
              diagnostics.map(_.fullMessage).mkString("\n")
          )
        }
    }
  }
}

/** One problem the compiler reported, as the build protocol describes it. */
private[flixmill] case class FlixDiagnostic(
    path: Option[String],
    line: Int,
    character: Int,
    code: Option[String],
    message: String,
    fullMessage: String
) {

  /** `file:line:column: CODE: message`, the form editors and CI logs linkify.
    *
    * The protocol's positions are LSP's and so zero-based, while a person reading a log expects
    * the numbers the compiler itself prints. This is the only place that conversion happens.
    */
  def render: String = path match {
    case Some(p) => s"$p:${line + 1}:${character + 1}: ${code.getOrElse("error")}: $message"
    case None => s"${code.getOrElse("error")}: $message"
  }
}

private[flixmill] object FlixDiagnostics {

  /** Returns the diagnostics in `stdout`, or `None` if it is not a protocol document. */
  def parse(stdout: String): Option[List[FlixDiagnostic]] =
    try {
      val document = ujson.read(stdout)
      // Keyed on `protocolVersion` rather than on `diagnostics`: a document without it is not this
      // protocol, and one with an empty diagnostics list is a build that simply succeeded.
      if (document.obj.get("protocolVersion").isEmpty) None
      else
        Some(document.obj.get("diagnostics").toList.flatMap(_.arr.toList).map { entry =>
          val start = entry.obj.get("range").flatMap(_.obj.get("start"))
          def number(key: String): Int =
            start.flatMap(_.obj.get(key)).map(_.num.toInt).getOrElse(0)
          FlixDiagnostic(
            path = entry.obj.get("path").flatMap(_.strOpt),
            line = number("line"),
            character = number("character"),
            code = entry.obj.get("code").flatMap(_.strOpt),
            message = entry.obj.get("message").flatMap(_.strOpt).getOrElse("unknown error"),
            fullMessage = entry.obj.get("fullMessage").flatMap(_.strOpt).getOrElse("")
          )
        })
    } catch {
      case _: Exception => None
    }
}

private[flixmill] object FlixArtifact {
  def packageFile(projectDirectory: os.Path): os.Path =
    projectDirectory / "artifact" / s"${projectDirectory.last}.fpkg"

  /** Signature for Flix-owned output that lives outside `Task.dest`.
    *
    * Mill's filesystem checker only lets a task read its own `Task.dest` or an upstream `PathRef`,
    * so hashing what Flix just wrote into the project directory requires suspending it. The
    * resulting `PathRef` is revalidated whenever a cached result is read back, so deleting the
    * output re-runs the task instead of replaying a path to a missing file.
    */
  def outputPathRef(path: os.Path): PathRef =
    FilesystemCheckerEnabled.withValue(false)(PathRef(path).withRevalidateOnce)
}

package flixmill

import mill.*
import mill.api.PathRef
import mill.javalib.JavaModule

/** A module whose Java and Flix sources may reference each other freely.
  *
  * A Java class calling an `@Export`-ed Flix def needs Flix codegen to have run; a Flix module
  * calling that Java class needs its class files. Both at once has no valid build order, and the
  * usual workaround is to split the code into two modules so the dependency runs one way -- which
  * partitions a codebase by build order rather than by meaning.
  *
  * This trait removes that. It derives the Flix facade's Java face *before* anything is compiled
  * and hands `javac` that, turning the cycle into a sequence:
  *
  *   1. [[flixStubSources]] writes a compile-only Java stub per exported Flix module.
  *   2. [[flixStubs]] compiles those stubs, into a module of their own.
  *   3. [[compile]] compiles the real Java sources against them.
  *   4. [[build]] compiles Flix against the real Java classes -- and against the stubs.
  *
  * ==Why one module rather than two==
  *
  * Mill resolves module references statically, so a `FlixModule` and a `JavaModule` that referred
  * to each other would be a cycle Mill rejects before any task runs. The ordering therefore has to
  * live inside a single module that owns both compiles, which is what this is. The two-module
  * arrangement in [[FlixModule.flixClasspath]] remains right when the dependency genuinely runs one
  * way; use this only when it does not.
  *
  * ==Two properties that are easy to break==
  *
  * The stub classes are on [[compileClasspath]] and on no run classpath. They would shadow the real
  * facade, and every exported call would throw `UnsupportedOperationException` instead of running.
  * That is why they are a separate module rather than generated sources of this one -- Mill would
  * otherwise put them in the same class directory, where nothing could keep them apart.
  *
  * They are also passed to Flix, not only to `javac`. Reading a Java class loads every type named
  * in its signatures, so a Java method whose signature mentions the facade cannot be read until
  * that class exists -- and when Flix runs, the real one does not yet.
  *
  * ==What is not here==
  *
  * There is no recompile of Java against the real facade. The stub is generated from the same
  * source as the facade, and a stub that no longer matches fails the build at the Java call site,
  * so the bytecode `javac` emitted already references what the facade provides. Adding the pass
  * would reproduce its own output.
  */
trait FlixJointModule extends FlixModule, JavaModule {

  /** The compiled export stubs.
    *
    * A module of its own so its classes land in their own directory. Nothing depends on it but
    * [[compileClasspath]] and [[flixLibs]], and neither puts it anywhere that runs.
    */
  object flixStubs extends JavaModule {
    override def generatedSources = Task { Seq(FlixJointModule.this.flixStubSources()) }
  }

  /** The Java sources compile against the stub facade, and against nothing else new. */
  override def compileClasspath = Task {
    super.compileClasspath() ++ Seq(flixStubs.compile().classes)
  }

  /** Flix compiles against this module's Java classes and the stubs.
    *
    * Jars because `--lib` takes jars: `Bootstrap` finds a project's own dependencies only under
    * `lib/cache` and `lib/external`, which the package managers own, so a build-produced classpath
    * entry has to be named explicitly.
    */
  override def flixLibs = Task { Seq(jar(), flixStubs.jar()) }

  /** The Flix output joins the run classpath, so an exported call reaches the real facade.
    *
    * The stub classes deliberately do not. They are on [[compileClasspath]] only.
    */
  override def runClasspath = Task {
    super.runClasspath() ++ Seq(build())
  }
}

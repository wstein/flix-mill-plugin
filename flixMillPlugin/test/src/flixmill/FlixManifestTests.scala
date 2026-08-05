package flixmill

import utest.*

/** Coverage for the generated `flix.toml`.
  *
  * The manifest is the one file the plugin writes into a project directory rather than into
  * `Task.dest`, so what it contains and when it refuses to write are both behavior a consumer
  * depends on.
  */
object FlixManifestTests extends TestSuite {

  private def render(
      name: String = "demo",
      description: String = "A demo project.",
      version: String = "1.2.3",
      flixVersion: String = "0.75.1",
      authors: Seq[String] = Seq("Ada"),
      license: Option[String] = None,
      repository: Option[String] = None,
      dependencies: Seq[(String, String)] = Seq.empty,
      mvnDependencies: Seq[(String, String)] = Seq.empty,
      jarDependencies: Seq[(String, String)] = Seq.empty
  ): String =
    FlixManifest.render(
      name,
      description,
      version,
      flixVersion,
      authors,
      license,
      repository,
      dependencies,
      mvnDependencies,
      jarDependencies
    )

  def tests = Tests {
    test("renders the keys Flix requires") {
      val manifest = render()

      assert(manifest.contains("[package]"))
      assert(manifest.contains("name = \"demo\""))
      assert(manifest.contains("description = \"A demo project.\""))
      assert(manifest.contains("version = \"1.2.3\""))
      assert(manifest.contains("flix = \"0.75.1\""))
      assert(manifest.contains("authors = [\"Ada\"]"))
    }

    test("marks the file as generated on its first line") {
      // The marker is what makes overwriting safe, so it has to be the first thing in the file
      // rather than merely present somewhere in it.
      val firstLine = render().linesIterator.next()

      assert(firstLine == FlixManifest.GeneratedMarker)
    }

    test("omits optional keys that were not set") {
      val manifest = render()

      assert(!manifest.contains("license"))
      assert(!manifest.contains("repository"))
    }

    test("includes optional keys that were set") {
      val manifest = render(license = Some("Apache-2.0"), repository = Some("wstein/demo"))

      assert(manifest.contains("license = \"Apache-2.0\""))
      assert(manifest.contains("repository = \"wstein/demo\""))
    }

    test("omits a dependency table that has no entries") {
      // Flix accepts an empty table, but a build with no Maven dependencies should not produce a
      // manifest that reads as though it has some.
      val manifest = render()

      assert(!manifest.contains("[dependencies]"))
      assert(!manifest.contains("[mvn-dependencies]"))
      assert(!manifest.contains("[jar-dependencies]"))
    }

    test("renders dependency tables in the format Flix parses") {
      val manifest = render(
        dependencies = Seq("github:flix/museum" -> "1.4.0"),
        mvnDependencies = Seq("org.postgresql:postgresql" -> "42.7.3"),
        jarDependencies = Seq("greeter.jar" -> "url:https://example.com/greeter.jar")
      )

      assert(manifest.contains("[dependencies]\n\"github:flix/museum\" = \"1.4.0\""))
      assert(manifest.contains("[mvn-dependencies]\n\"org.postgresql:postgresql\" = \"42.7.3\""))
      assert(
        manifest.contains("[jar-dependencies]\n\"greeter.jar\" = \"url:https://example.com/greeter.jar\"")
      )
    }

    test("escapes what a TOML basic string cannot hold literally") {
      // A description is free text and reaches the file verbatim otherwise, so an unescaped quote
      // or backslash would produce a manifest Flix cannot parse.
      val manifest = render(description = "Quote \" backslash \\ tab \t done")

      assert(manifest.contains("description = \"Quote \\\" backslash \\\\ tab \\t done\""))
    }

    test("leaves ordinary punctuation alone") {
      // Escaping more than TOML requires would be just as wrong: the value read back must equal
      // the value written.
      val manifest = render(description = "It's a café — really.")

      assert(manifest.contains("description = \"It's a café — really.\""))
    }

    test("rejects an empty author list") {
      // Flix requires at least one author, so failing here beats writing a manifest that only
      // fails once the compiler reads it.
      val failure = scala.util.Try(render(authors = Seq.empty))

      assert(failure.isFailure)
      assert(failure.failed.get.getMessage.contains("flixPackageAuthors"))
    }

    test("turns a Mill dependency into a Flix coordinate") {
      val dep = mill.javalib.Dep.parse("org.postgresql:postgresql:42.7.3")

      assert(FlixManifest.coordinateOf(dep) == ("org.postgresql:postgresql" -> "42.7.3"))
    }

    test("rejects a cross-versioned dependency") {
      // `mvn"org::artifact:version"` resolves to an artifact whose name ends in a Scala
      // binary-version suffix. Flix appends none, so the coordinate would name something that does
      // not exist -- and it would fail at resolution time, far from the build file that caused it.
      val dep = mill.javalib.Dep.parse("com.lihaoyi::os-lib:0.11.8")

      val failure = scala.util.Try(FlixManifest.coordinateOf(dep))

      assert(failure.isFailure)
      assert(failure.failed.get.getMessage.contains("cross-versioned"))
    }

    test("rejects a dependency carrying exclusions") {
      // The manifest has nowhere to put them, so writing the coordinate alone would resolve
      // something other than what the build asked for.
      val dep = mill.javalib.Dep
        .parse("org.postgresql:postgresql:42.7.3")
        .exclude("org.slf4j" -> "slf4j-api")

      val failure = scala.util.Try(FlixManifest.coordinateOf(dep))

      assert(failure.isFailure)
      assert(failure.failed.get.getMessage.contains("exclusions"))
    }

    test("rejects a dependency carrying a classifier") {
      val dep = mill.javalib.Dep.parse("org.postgresql:postgresql:42.7.3;classifier=linux")

      val failure = scala.util.Try(FlixManifest.coordinateOf(dep))

      assert(failure.isFailure)
      assert(failure.failed.get.getMessage.contains("classifier"))
    }

    test("writes a manifest when none is present") {
      val directory = os.temp.dir()
      val target = directory / "flix.toml"

      FlixManifest.writeIfOwned(target, render())

      assert(os.exists(target))
      assert(FlixManifest.isGenerated(target))
    }

    test("overwrites a manifest it generated earlier") {
      val directory = os.temp.dir()
      val target = directory / "flix.toml"
      FlixManifest.writeIfOwned(target, render(version = "1.0.0"))

      FlixManifest.writeIfOwned(target, render(version = "2.0.0"))

      assert(os.read(target).contains("version = \"2.0.0\""))
    }

    test("refuses to overwrite a manifest it did not generate") {
      // A hand-written manifest can hold metadata and dependencies that exist nowhere else, so
      // replacing it silently would destroy the only copy.
      val directory = os.temp.dir()
      val target = directory / "flix.toml"
      val handWritten = "[package]\nname = \"mine\"\n"
      os.write(target, handWritten)

      val failure = scala.util.Try(FlixManifest.writeIfOwned(target, render()))

      assert(failure.isFailure)
      assert(failure.failed.get.getMessage.contains("did not generate"))
      assert(os.read(target) == handWritten)
    }
  }
}

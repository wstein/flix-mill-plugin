package flixmill

import utest.*

/** Tests the reader for the compiler's build-protocol output.
  *
  * The value of reading the protocol rather than the console is that a location and an error code
  * survive as data. These tests are what stop that quietly regressing to "the build failed".
  */
object FlixDiagnosticsTests extends TestSuite {

  private val Document =
    """{
      |  "protocolVersion": 1,
      |  "flixVersion": "0.75.1",
      |  "success": false,
      |  "diagnostics": [{
      |    "path": "/w/src/Acme/Api.flix",
      |    "range": { "start": { "line": 2, "character": 52 }, "end": { "line": 2, "character": 55 } },
      |    "severity": 1,
      |    "code": "E2136",
      |    "kind": "Resolution Error",
      |    "message": "Undefined name: 'nam'.",
      |    "fullMessage": "-- Resolution Error [E2136] --"
      |  }]
      |}""".stripMargin

  def tests = Tests {

    test("reads a diagnostic's location, code, and message") {
      val diagnostic = FlixDiagnostics.parse(Document).get.head
      assert(diagnostic.path.contains("/w/src/Acme/Api.flix"))
      assert(diagnostic.code.contains("E2136"))
      assert(diagnostic.message == "Undefined name: 'nam'.")
    }

    test("renders positions the way the compiler prints them") {
      // The protocol's positions are LSP's and so zero-based, while the compiler's own output and
      // every editor's "go to line" are one-based. Getting this backwards puts a marker one line
      // and one column off every time, which reads as a rounding error rather than as a bug.
      val diagnostic = FlixDiagnostics.parse(Document).get.head
      assert(diagnostic.render == "/w/src/Acme/Api.flix:3:53: E2136: Undefined name: 'nam'.")
    }

    test("a successful build reports no diagnostics rather than no document") {
      // An empty list and an unreadable document must not look alike: one is a build that passed,
      // the other is a compiler that could not be understood.
      val success = """{"protocolVersion": 1, "success": true, "diagnostics": []}"""
      assert(FlixDiagnostics.parse(success).contains(Nil))
    }

    test("output that is not this protocol is refused") {
      // Each of these would otherwise read as "no diagnostics" and let a failing build pass: a
      // compiler too old to know the flag, one that crashed, and JSON that is something else.
      assert(FlixDiagnostics.parse("").isEmpty)
      assert(FlixDiagnostics.parse("Exception in thread \"main\"").isEmpty)
      assert(FlixDiagnostics.parse("""{"diagnostics": []}""").isEmpty)
    }

    test("a diagnostic with no location is still reported") {
      // A malformed manifest or an unreachable dependency has no source location. Dropping it
      // would leave a build failing with nothing said about why.
      val document = """{"protocolVersion": 1, "success": false, "diagnostics": [
                       |  {"path": null, "code": null, "message": "Cannot read flix.toml"}]}""".stripMargin
      val diagnostic = FlixDiagnostics.parse(document).get.head
      assert(diagnostic.path.isEmpty)
      assert(diagnostic.render == "error: Cannot read flix.toml")
    }
  }
}

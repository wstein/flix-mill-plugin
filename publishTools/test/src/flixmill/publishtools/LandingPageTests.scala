package flixmill.publishtools

import utest.*

object LandingPageTests extends TestSuite {

  private val versions = MavenMetadata.Versions(List("0.1.0", "0.2.0"))

  private def rendered: String =
    LandingPage.render(
      "io.github.wstein",
      "flix-mill-plugin_mill1_3",
      "flix-mill-plugin",
      versions,
      "wstein.github.io/flix-mill-plugin",
      "0.75.1"
    )

  /** Every opened tag closes, in order, none left open at the end. The same regression class this
    * guards against broke `wstein/flix-spec`'s first landing page: a template with enough
    * interpolation points that nothing actually checked the result was valid HTML.
    */
  private def wellFormed(html: String): Either[String, Unit] = {
    val voidElements = Set("meta", "link", "br", "img", "hr", "input", "!doctype")
    val tagPattern = """<(/?)([a-zA-Z][a-zA-Z0-9]*)[^>]*>""".r
    var stack = List.empty[String]
    val mismatches = scala.collection.mutable.ListBuffer.empty[String]

    tagPattern.findAllMatchIn(html).foreach { m =>
      val closing = m.group(1) == "/"
      val tag = m.group(2).toLowerCase
      if (!voidElements.contains(tag) && !m.group(0).endsWith("/>")) {
        if (!closing) stack = tag :: stack
        else
          stack match {
            case head :: tail if head == tag => stack = tail
            case _                            => mismatches += s"unexpected </$tag>, stack was $stack"
          }
      }
    }
    if (mismatches.nonEmpty) Left(mismatches.mkString("; "))
    else if (stack.nonEmpty) Left(s"unclosed at end: $stack")
    else Right(())
  }

  def tests: Tests = Tests {

    test("HTML is well-formed") {
      wellFormed(rendered) match {
        case Right(())   => ()
        case Left(error) => throw new java.lang.AssertionError(error)
      }
    }

    test("the latest version drives the usage snippet") {
      assert(rendered.contains("io.github.wstein::flix-mill-plugin::0.2.0"))
    }

    test("the usage snippet uses the unsuffixed artifact name, never the resolved artifact id") {
      // Real bug, caught only by an actual release run: Mill's `::` mvnDeps syntax appends the
      // platform suffix itself, so a snippet built from the already-suffixed artifactId
      // ("flix-mill-plugin_mill1_3") resolves to a coordinate that doesn't exist
      // ("flix-mill-plugin_mill1_3_mill1_3"). The snippet must use the unsuffixed artifactName;
      // the resolved artifactId belongs only in the real Maven paths below.
      assert(!rendered.contains("flix-mill-plugin_mill1_3::"))
      assert(rendered.contains("maven/io/github/wstein/flix-mill-plugin_mill1_3/0.2.0/"))
    }

    test("every published version is listed") {
      assert(rendered.contains(">0.1.0<"))
      assert(rendered.contains(">0.2.0<"))
    }

    test("no published versions renders an explicit empty state, not an empty table") {
      val html =
        LandingPage.render("g", "a_mill1_3", "a", MavenMetadata.Versions(Nil), "example.invalid", "0.75.1")
      assert(html.contains("none published yet"))
      assert(html.contains("(none published yet)")) // the usage snippet's placeholder version
    }

    test("dynamic values are HTML-escaped") {
      val html = LandingPage.render("<g>", "a\"b_mill1_3", "a\"b", versions, "example.invalid", "0.75.1")
      assert(!html.contains("<g>"))
    }

    test("does not fabricate a dependents/consumers section") {
      // The only "consumer" on record is this repository's own integration-test fixture, which is
      // not an honest external-usage claim -- see the class doc for why flix-spec's equivalent
      // section does not appear here at all.
      val html = rendered.toLowerCase
      assert(!html.contains("dependent"))
    }
  }
}

package flixmill.publishtools

import utest.*

import java.time.Instant

object MavenMetadataTests extends TestSuite {

  def tests: Tests = Tests {

    test("first publish: no existing metadata, one version") {
      val merged = MavenMetadata.merge(MavenMetadata.parseExisting(None), "0.1.0")
      assert(merged.all == List("0.1.0"))
      assert(merged.latest.contains("0.1.0"))
      assert(merged.release.contains("0.1.0"))
    }

    test("second publish: existing metadata gains the new version, keeps the old") {
      val existingXml = MavenMetadata.render("io.github.wstein", "flix-mill-plugin", MavenMetadata.Versions(List("0.1.0")))
      val merged = MavenMetadata.merge(MavenMetadata.parseExisting(Some(existingXml)), "0.2.0")
      assert(merged.all == List("0.1.0", "0.2.0"))
      assert(merged.latest.contains("0.2.0"))
    }

    test("republishing the same version is idempotent, not duplicated") {
      val once = MavenMetadata.merge(MavenMetadata.parseExisting(None), "0.1.0")
      val xml = MavenMetadata.render("g", "a", once)
      val twice = MavenMetadata.merge(MavenMetadata.parseExisting(Some(xml)), "0.1.0")
      assert(twice.all == List("0.1.0"))
    }

    test("render round-trips through parseExisting: what is written can be read back exactly") {
      val versions = MavenMetadata.Versions(List("0.1.0", "0.2.0", "0.3.0"))
      val xml = MavenMetadata.render("io.github.wstein", "flix-mill-plugin", versions)
      val parsed = MavenMetadata.parseExisting(Some(xml))
      assert(parsed.all == versions.all)
    }

    test("render escapes XML special characters in identifiers") {
      val xml = MavenMetadata.render("g<r>oup", "art&id", MavenMetadata.Versions(List("1.0.0")))
      assert(xml.contains("g&lt;r&gt;oup"))
      assert(xml.contains("art&amp;id"))
      assert(!xml.contains("<r>"))
    }

    test("lastUpdated is a fixed-width UTC timestamp in Maven's own format") {
      val xml = MavenMetadata.render("g", "a", MavenMetadata.Versions(List("1.0.0")), Instant.parse("2026-08-02T18:30:59Z"))
      assert(xml.contains("<lastUpdated>20260802183059</lastUpdated>"))
    }

    test("empty versions renders empty latest/release rather than throwing") {
      val xml = MavenMetadata.render("g", "a", MavenMetadata.Versions(Nil))
      assert(xml.contains("<latest></latest>"))
      assert(xml.contains("<release></release>"))
    }
  }
}

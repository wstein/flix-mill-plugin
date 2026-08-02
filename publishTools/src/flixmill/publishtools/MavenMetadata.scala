package flixmill.publishtools

import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.ZoneOffset
import javax.xml.parsers.DocumentBuilderFactory

/** Reads and writes the group-level `maven-metadata.xml` for one artifact.
  *
  * Simpler than a two-channel (release/snapshot) scheme: this plugin's `publishVersion` is always a
  * plain release version -- `release.yml` requires the triggering tag to match it exactly, so there
  * is no floating `-SNAPSHOT` coordinate here at all, and therefore no `<snapshotVersions>` block
  * to maintain.
  *
  * `PublishModule.publishM2Local` writes only the artifact files for one version and knows nothing
  * about any other version already published; Gradle's `maven-publish` merges this file
  * automatically when publishing to a repository that already has one, but Mill's local-M2
  * publisher does not, so the merge has to happen here, deliberately, rather than being assumed.
  */
object MavenMetadata {

  final case class Versions(all: List[String]) {
    def latest: Option[String] = all.lastOption
    def release: Option[String] = all.lastOption
  }

  /** Parses the existing metadata, or `Versions(Nil)` if there is none yet (first publish). Real
    * XML parsing, not regex over a structured format.
    */
  def parseExisting(xml: Option[String]): Versions = xml match {
    case None       => Versions(Nil)
    case Some(text) =>
      val factory = DocumentBuilderFactory.newInstance()
      factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
      val doc =
        factory.newDocumentBuilder().parse(new java.io.ByteArrayInputStream(text.getBytes("UTF-8")))
      val nodes = doc.getElementsByTagName("version")
      Versions((0 until nodes.getLength).map(nodes.item(_).getTextContent).toList)
  }

  /** Appends `newVersion` if not already present, preserving publish order. Idempotent: publishing
    * the same version twice (a rerun of a successful step, say) does not duplicate the entry.
    */
  def merge(existing: Versions, newVersion: String): Versions =
    if (existing.all.contains(newVersion)) existing
    else Versions(existing.all :+ newVersion)

  private def esc(s: String): String =
    s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

  def render(
      groupId: String,
      artifactId: String,
      versions: Versions,
      now: Instant = Instant.now()
  ): String = {
    val lastUpdated =
      DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(ZoneOffset.UTC).format(now)
    val versionLines = versions.all.map(v => s"      <version>${esc(v)}</version>").mkString("\n")
    s"""<?xml version="1.0" encoding="UTF-8"?>
       |<metadata>
       |  <groupId>${esc(groupId)}</groupId>
       |  <artifactId>${esc(artifactId)}</artifactId>
       |  <versioning>
       |    <latest>${esc(versions.latest.getOrElse(""))}</latest>
       |    <release>${esc(versions.release.getOrElse(""))}</release>
       |    <versions>
       |$versionLines
       |    </versions>
       |    <lastUpdated>$lastUpdated</lastUpdated>
       |  </versioning>
       |</metadata>
       |""".stripMargin
  }
}

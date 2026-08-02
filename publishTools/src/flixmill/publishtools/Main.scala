package flixmill.publishtools

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}

/** Orchestrates the GitHub-Pages Maven publish: checksums for the files `publishM2Local` just
  * wrote, an additive merge of the group's `maven-metadata.xml`, and the landing page.
  *
  * Deliberately a thin driver over pure functions in [[Checksums]], [[MavenMetadata]] and
  * [[LandingPage]] -- the logic worth testing lives there, not here. This is what
  * `flix.spec.LandingPage`'s `main` plays in `wstein/flix-spec`: I/O and argument parsing only.
  */
object Main {

  def main(args: Array[String]): Unit = {
    if (args.length != 6) {
      System.err.println(
        "usage: publishTools <m2RepoPath> <indexOutputPath> <groupId> <artifactId> <version> <repositoryUrl>"
      )
      sys.exit(2)
    }
    val Array(m2RepoPathArg, indexOutputPathArg, groupId, artifactId, version, repositoryUrl) = args

    val m2RepoPath = Paths.get(m2RepoPathArg)
    val groupPath = groupId.replace('.', '/')
    val versionDir = m2RepoPath.resolve(groupPath).resolve(artifactId).resolve(version)
    require(Files.isDirectory(versionDir), s"FATAL: expected publishM2Local output at $versionDir")

    val sidecars = Checksums.writeSidecarsRecursively(versionDir)
    println(s"Wrote ${sidecars.length} checksum sidecar(s) under $versionDir")

    val artifactDir = m2RepoPath.resolve(groupPath).resolve(artifactId)
    val metadataPath = artifactDir.resolve("maven-metadata.xml")
    val existing =
      if (Files.exists(metadataPath)) Some(Files.readString(metadataPath, StandardCharsets.UTF_8))
      else None
    val merged = MavenMetadata.merge(MavenMetadata.parseExisting(existing), version)
    val metadataXml = MavenMetadata.render(groupId, artifactId, merged)
    Files.writeString(metadataPath, metadataXml, StandardCharsets.UTF_8)
    Checksums.writeSidecars(metadataPath)
    println(s"maven-metadata.xml: ${merged.all.mkString(", ")}")

    val flixVersion = sys.env.getOrElse("FLIX_VERSION", "unknown")
    val html = LandingPage.render(groupId, artifactId, merged, repositoryUrl, flixVersion)
    val indexPath: Path = Paths.get(indexOutputPathArg)
    Option(indexPath.getParent).foreach(Files.createDirectories(_))
    Files.writeString(indexPath, html, StandardCharsets.UTF_8)
    println(s"Wrote $indexPath")
  }
}

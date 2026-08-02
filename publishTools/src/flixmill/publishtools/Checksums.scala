package flixmill.publishtools

import java.nio.file.{Files, Path}
import java.security.MessageDigest

/** Checksum sidecar files for a Maven-layout repository.
  *
  * `PublishModule.publishM2Local` writes only the jar/sources/javadoc/pom themselves -- Gradle's
  * `maven-publish` plugin generates `.md5`/`.sha1`/`.sha256`/`.sha512` sidecars automatically, but
  * Mill's local-M2 publisher does not. Real Maven repository layout has always required `.md5` and
  * `.sha1` for resolvers that verify; `.sha256`/`.sha512` are the modern addition most tooling
  * (including Sonatype/Nexus-family repositories) now also publishes.
  */
object Checksums {

  val Algorithms: List[(String, String)] =
    List("MD5" -> "md5", "SHA-1" -> "sha1", "SHA-256" -> "sha256", "SHA-512" -> "sha512")

  def hex(bytes: Array[Byte]): String = bytes.map("%02x".format(_)).mkString

  def digestOf(algorithm: String, file: Path): String =
    hex(MessageDigest.getInstance(algorithm).digest(Files.readAllBytes(file)))

  /** Writes every sidecar for one file, returning the paths written. Skips a file that does not
    * exist rather than failing -- not every artifact type (e.g. `-javadoc.jar`) is always present.
    */
  def writeSidecars(file: Path): List[Path] =
    if (!Files.exists(file)) Nil
    else
      Algorithms.map { case (algorithm, ext) =>
        val sidecar = file.resolveSibling(file.getFileName.toString + "." + ext)
        Files.writeString(sidecar, digestOf(algorithm, file))
        sidecar
      }

  /** Sidecars for every regular file already present under `dir`, recursively. Meant to run once
    * over a freshly `publishM2Local`-populated version directory, so it never touches a sidecar it
    * just wrote in a prior invocation (checksums are regenerated fresh each run, not accumulated).
    */
  def writeSidecarsRecursively(dir: Path): List[Path] = {
    import scala.jdk.CollectionConverters._
    if (!Files.isDirectory(dir)) Nil
    else
      Files
        .walk(dir)
        .iterator()
        .asScala
        .filter(Files.isRegularFile(_))
        .filterNot(p =>
          Algorithms.exists { case (_, ext) => p.getFileName.toString.endsWith("." + ext) }
        )
        .flatMap(writeSidecars)
        .toList
  }
}

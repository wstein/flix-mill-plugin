package flixmill.publishtools

import utest.*

import java.nio.file.Files

object ChecksumsTests extends TestSuite {

  def tests: Tests = Tests {

    test("known digests: sha1/md5 of an empty file match well-known constants") {
      val dir = Files.createTempDirectory("checksums-test")
      val f = dir.resolve("empty.txt")
      Files.writeString(f, "")

      assert(Checksums.digestOf("MD5", f) == "d41d8cd98f00b204e9800998ecf8427e")
      assert(Checksums.digestOf("SHA-1", f) == "da39a3ee5e6b4b0d3255bfef95601890afd80709")
    }

    test("writeSidecars produces one file per algorithm, each containing the matching digest") {
      val dir = Files.createTempDirectory("checksums-test")
      val f = dir.resolve("artifact.jar")
      Files.writeString(f, "not a real jar, just needs bytes")

      val written = Checksums.writeSidecars(f)
      assert(written.length == Checksums.Algorithms.length)
      Checksums.Algorithms.foreach { case (algorithm, ext) =>
        val sidecar = dir.resolve(s"artifact.jar.$ext")
        assert(Files.exists(sidecar))
        assert(Files.readString(sidecar) == Checksums.digestOf(algorithm, f))
      }
    }

    test("a missing file produces no sidecars rather than throwing") {
      val dir = Files.createTempDirectory("checksums-test")
      val missing = dir.resolve("does-not-exist.jar")
      assert(Checksums.writeSidecars(missing) == Nil)
    }

    test("writeSidecarsRecursively covers every real file under a tree, never a sidecar of a sidecar") {
      val dir = Files.createTempDirectory("checksums-test")
      val sub = Files.createDirectories(dir.resolve("1.0.0"))
      Files.writeString(sub.resolve("a.jar"), "jar contents")
      Files.writeString(sub.resolve("a.pom"), "pom contents")

      val written = Checksums.writeSidecarsRecursively(dir)
      // 2 real files x 4 algorithms, and none of the sidecars themselves got sidecar'd.
      assert(written.length == 2 * Checksums.Algorithms.length)
      assert(written.forall(p => !Checksums.Algorithms.exists { case (_, ext) => p.toString.endsWith(s".jar.$ext.$ext") }))
    }
  }
}

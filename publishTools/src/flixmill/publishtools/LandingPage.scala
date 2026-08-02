package flixmill.publishtools

/** Generates the landing page served at this repository's GitHub Pages root.
  *
  * Modeled on `wstein/flix-spec`'s landing page -- same reasoning, same constraints, deliberately
  * not a copy of its content. flix-spec publishes generated *data* (fixtures, schemas, a corpus
  * definition) and its page reports coverage/reachability numbers and self-declared consumers. This
  * publishes *code* -- a Mill plugin -- and none of that applies: there is no coverage to measure,
  * and the only "consumer" on record is this repository's own integration-test fixture, which is
  * not an honest "dependents" claim. What transfers is the discipline, not the layout: every fact
  * here is a typed parameter to [[render]], not a hand-maintained sentence that can go stale or
  * reference the wrong value -- the exact defect class that broke flix-spec's first version of this
  * page (a prose sentence silently reading the wrong variable, only coincidentally correct until
  * the two numbers it conflated diverged).
  */
object LandingPage {

  def escapeHtml(s: String): String =
    s.replace("&", "&amp;")
      .replace("<", "&lt;")
      .replace(">", "&gt;")
      .replace("\"", "&quot;")
      .replace("'", "&#39;")

  private val Styles: String =
    """<style>
      |  :root {
      |    color-scheme: light dark;
      |    --bg: #ffffff; --fg: #1a1a1a; --muted: #5b5b5b; --border: #e2e2e2;
      |    --card: #f7f7f7; --accent: #0969da; --code-bg: #f0f0f0;
      |    --badge-bg: #dafbe1; --badge-fg: #116329;
      |  }
      |  @media (prefers-color-scheme: dark) {
      |    :root {
      |      --bg: #0d1117; --fg: #e6edf3; --muted: #9198a1; --border: #30363d;
      |      --card: #161b22; --accent: #4493f8; --code-bg: #1c2128;
      |      --badge-bg: #133a24; --badge-fg: #56d364;
      |    }
      |  }
      |  * { box-sizing: border-box; }
      |  body {
      |    margin: 0; padding: 0 1.25rem 4rem;
      |    background: var(--bg); color: var(--fg);
      |    font: 16px/1.55 -apple-system, BlinkMacSystemFont, "Segoe UI", Helvetica, Arial, sans-serif;
      |  }
      |  main { max-width: 42rem; margin: 0 auto; }
      |  header { padding: 3rem 0 1.5rem; border-bottom: 1px solid var(--border); margin-bottom: 2rem; }
      |  h1 { font-size: 1.9rem; margin: 0 0 0.4rem; }
      |  h2 { font-size: 1.2rem; margin: 2.5rem 0 0.75rem; padding-top: 0.5rem; border-top: 1px solid var(--border); }
      |  .tagline { color: var(--muted); font-size: 1.05rem; margin: 0; }
      |  .note { background: var(--card); border: 1px solid var(--border); border-radius: 8px;
      |          padding: 1rem 1.25rem; margin: 1.25rem 0; font-size: 0.95rem; color: var(--muted); }
      |  .note strong { color: var(--fg); }
      |  table { width: 100%; border-collapse: collapse; margin: 0.5rem 0; font-size: 0.92rem; }
      |  th, td { text-align: left; padding: 0.5rem 0.6rem; border-bottom: 1px solid var(--border); }
      |  th { color: var(--muted); font-weight: 500; font-size: 0.82rem; text-transform: uppercase; letter-spacing: 0.02em; }
      |  code { background: var(--code-bg); padding: 0.15em 0.4em; border-radius: 4px; font-size: 0.9em; }
      |  a { color: var(--accent); }
      |  .badge { display: inline-block; padding: 0.1rem 0.55rem; border-radius: 999px; font-size: 0.78rem;
      |           font-weight: 500; background: var(--badge-bg); color: var(--badge-fg); }
      |  pre { overflow-x: auto; background: var(--code-bg); padding: 0.9rem 1rem; border-radius: 8px; font-size: 0.85rem; }
      |  footer { margin-top: 3rem; padding-top: 1.5rem; border-top: 1px solid var(--border); color: var(--muted); font-size: 0.85rem; }
      |  .overflow { overflow-x: auto; }
      |</style>""".stripMargin

  def render(
      groupId: String,
      artifactId: String,
      versions: MavenMetadata.Versions,
      repositoryUrl: String,
      flixVersion: String
  ): String = {
    // Escaped once, up front, so every interpolation site below is guaranteed-safe by
    // construction rather than by remembering to wrap each one individually -- the latter is
    // exactly how the usage-snippet interpolations were missed on the first pass (caught by
    // LandingPageTests, not by review).
    val safeGroupId = escapeHtml(groupId)
    val safeArtifactId = escapeHtml(artifactId)
    val safeFlixVersion = escapeHtml(flixVersion)
    val latest = escapeHtml(versions.latest.getOrElse("(none published yet)"))
    val rows = versions.all.reverse
      .map { v =>
        s"""        <tr>
           |          <td><code>${escapeHtml(v)}</code></td>
           |          <td><a href="maven/${escapeHtml(groupId.replace('.', '/'))}/${escapeHtml(
            artifactId
          )}/${escapeHtml(v)}/">browse</a></td>
           |        </tr>""".stripMargin
      }
      .mkString("\n")
    val versionTable =
      if (versions.all.isEmpty) """        <tr><td colspan="2">none published yet</td></tr>"""
      else rows

    s"""<!doctype html>
       |<html lang="en">
       |<head>
       |<meta charset="utf-8">
       |<meta name="viewport" content="width=device-width, initial-scale=1">
       |<title>$safeArtifactId</title>
       |<meta name="description" content="A Mill build-tool plugin for the Flix compiler.">
       |$Styles
       |</head>
       |<body>
       |<main>
       |
       |<header>
       |  <h1>$safeArtifactId</h1>
       |  <p class="tagline">Flix compiler support for <a href="https://mill-build.org">Mill</a></p>
       |</header>
       |
       |<p>
       |  Wraps the official Flix CLI (<code>flix.jar</code>) as a Mill module: cached <code>check</code>,
       |  <code>build</code> and <code>buildPkg</code> tasks, and command tasks for
       |  <code>test</code>/<code>run</code>/<code>init</code>. Consumers extend
       |  <code>flixmill.FlixModule</code> in their own <code>build.mill</code>. Verified against Flix
       |  <code>v$safeFlixVersion</code> in CI, but does not bundle or resolve the compiler itself -- each
       |  consumer project supplies its own <code>flix.jar</code>.
       |</p>
       |
       |<div class="note">
       |  <strong>Hosted here as an additional channel, not the primary one.</strong> This artifact is
       |  also intended for <a href="https://central.sonatype.com/">Maven Central</a>, which most Mill
       |  builds will resolve automatically with no repository configuration at all. This GitHub Pages
       |  repository exists because Central publication requires an approved account and PGP signing
       |  that may not always be configured yet -- see <code>README.md</code>'s "Publishing" section in
       |  the repository for the Central release procedure.
       |</div>
       |
       |<h2>Use it</h2>
       |<pre><code>//| mvnDeps:
       |//| - $safeGroupId::$safeArtifactId::$latest
       |
       |import flixmill.FlixModule
       |
       |object app extends FlixModule</code></pre>
       |<p style="font-size: 0.9rem;">
       |  Add this repository if resolving from here rather than Central:
       |</p>
       |<pre><code>//| repositories:
       |//| - https://${escapeHtml(repositoryUrl)}/maven/</code></pre>
       |
       |<h2>Versions</h2>
       |<div class="overflow">
       |<table>
       |  <tr><th>Version</th><th></th></tr>
       |$versionTable
       |</table>
       |</div>
       |<p style="font-size: 0.85rem; color: var(--muted);">
       |  Read from this repository's own <code>maven-metadata.xml</code> -- not maintained separately here.
       |</p>
       |
       |<footer>
       |  <a href="https://github.com/wstein/flix-mill-plugin">github.com/wstein/flix-mill-plugin</a> &middot; Apache-2.0 &middot;
       |  generated from <code>maven-metadata.xml</code> by
       |  <a href="https://github.com/wstein/flix-mill-plugin/blob/main/publishTools/src/flixmill/publishtools/LandingPage.scala">LandingPage.scala</a>,
       |  never hand-edited
       |</footer>
       |
       |</main>
       |</body>
       |</html>
       |""".stripMargin
  }
}

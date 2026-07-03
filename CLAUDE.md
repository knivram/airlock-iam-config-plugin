## Create release

Releasing is automatic on push to `main` when `version` changes. To cut a release:

1. Bump `version` in `gradle.properties`.
2. Run `./gradlew patchChangelog` — rotates `## [Unreleased]` into `## [<version>]`.
3. Commit both files and push to `main`.

After CI (build/test/verify) passes, the `release` job in `.github/workflows/build.yml` tags
`v<version>`, publishes the GitHub Release with the plugin zip, and refreshes `updatePlugins.xml` on
GitHub Pages. It **no-ops** if tag `v<version>` already exists, and **fails before tagging** if
`gradle.properties` and `CHANGELOG.md` don't both name the new version.

The built plugin's `<change-notes>` are auto-filled from the `## [<version>]` CHANGELOG section at build
time (via `patchPluginXml`), so they show in the IDE's Change Notes tab. This is automatic — no manual
step. The GitHub Release body is rendered separately from the same section by the `release` job, so the
two agree. Note `updatePlugins.xml` carries no change-notes, so they surface from the downloaded plugin
descriptor, not the pre-install update listing.

One-time setup: Settings → Pages → Source = "GitHub Actions"; keep the repo public (the IDE fetches
the plugin and `updatePlugins.xml` without auth).

---
name: keep-a-changelog
description: Guidance for writing and editing CHANGELOG entries in the Keep a Changelog 1.1.0 style. Use when editing a CHANGELOG.md, adding a changelog entry for a change, writing release notes, or deciding which category (Added/Changed/Deprecated/Removed/Fixed/Security) a change belongs under.
---

# Keep a Changelog entries

Guidance for writing notable-change entries in the [Keep a Changelog 1.1.0](https://keepachangelog.com/en/1.1.0/) format. This skill is about **writing entries**, not creating changelog files or cutting releases.

A changelog is a curated, chronologically ordered list of **notable** changes, written **for humans**. Curate it — it is not a commit log.

## Where entries go

Add new entries under the `## [Unreleased]` section at the top of the file, grouped beneath a `### <Category>` heading. Create the category heading if it isn't there yet.

```markdown
## [Unreleased]

### Added

- New feature, described from the user's point of view.

### Fixed

- The bug that is now fixed, described by its observable symptom.
```

Each entry is a bullet (`-`). Newest version is at the top of the file; within a version, group bullets by category.

## The six categories

Pick the category that matches the change's effect on the user. Use only these:

| Category | Use for |
|---|---|
| **Added** | New features. |
| **Changed** | Changes in existing functionality. |
| **Deprecated** | Features soon to be removed. |
| **Removed** | Features now removed. |
| **Fixed** | Bug fixes. |
| **Security** | Vulnerabilities — what was vulnerable and that it's resolved. |

If a change touches several categories, split it into one bullet per category rather than forcing it into one.

## Writing a good entry

- **Describe the user-visible change, not the implementation or the commit.** "Hover documentation now shows the plugin's display name" — not "refactor `DocProvider`".
- **Lead with the observable effect.** For a fix, name the symptom that's gone; for a feature, name what the user can now do.
- **One bullet per notable change.** Keep each self-contained and readable on its own.
- **Present tense, concise.** Skip "this commit", "we", and ticket-speak.
- **Match the house style of the file.** This repo prefixes a bolded component then an em dash: `**Navigation** — go to definition from a ref: to its id:`. Follow whatever convention the surrounding entries already use.
- **Link issues/PRs only when it helps the reader**, never as a substitute for describing the change.
- **Skip noise.** Internal refactors, test-only changes, formatting, and dependency bumps that don't affect users generally don't belong in the changelog.

## Anti-patterns to avoid

- **No commit-log dumps.** `git log` output is full of merge commits, obscure titles, and internal churn. Commits explain how the code evolved; changelog entries explain what changed for the user.
- **Don't omit notable changes.** A changelog that lists only some of the changes is as dangerous as none — users rely on it as the source of truth.
- **Be painfully clear about breaking changes and deprecations.** Users should be able to upgrade to a version that lists a deprecation, remove what's deprecated, then upgrade to the version where it's removed. Never let a breaking change go unmentioned.
- **Use ISO 8601 dates (`YYYY-MM-DD`)** wherever a date appears, e.g. `## [1.2.0] - 2026-06-29`. Avoid region-ambiguous formats.

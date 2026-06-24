# airlock-iam-config-plugin

![Build](https://github.com/knivram/airlock-iam-config-plugin/workflows/Build/badge.svg)

<!-- Plugin description -->
Editor support for **Airlock IAM** configuration files — the large YAML documents with
`type: IamConfig` and `schemaVersion: iam.airlock.com/…`.

These configs are trees of *plugins*. A plugin is a YAML mapping with a `type:` and an `id:`;
instead of inlining a plugin, another place can reference it by `ref:` (or inherit via `parentRef:`)
using that `id`. Because ids are opaque (usually UUIDs), navigating and maintaining these files by
hand is painful. This plugin makes the references first-class:

- **Go to definition** — Ctrl/Cmd+Click a `ref:` / `parentRef:` value to jump to the plugin `id:`
  it points at.
- **Find usages** — from a plugin's `id:`, list every `ref:` / `parentRef:` that points at it.
- **Code completion**
  - `ref:` / `parentRef:` values — pick an existing plugin (shown by its `displayName` and type);
    the id is inserted for you.
  - `type:` values — all Airlock IAM plugin types (a bundled IAM 8.6 catalog of ~1600 types, plus
    any types already used in the file).
- **Inspections** — a dangling `ref:` (no plugin declares that id) and a duplicate plugin `id:`
  are highlighted.

Everything is scoped to a **single file**: configs are commonly duplicated (e.g. a production copy),
where the same ids legitimately appear in several files — so resolving, finding usages and validating
across files would produce false duplicate-id warnings and ambiguous navigation. Ordinary YAML
(non-IamConfig) is untouched.
<!-- Plugin description end -->

## Requirements

IntelliJ IDEA 2025.2+ (the bundled YAML plugin must be enabled).

## How it works

- Each IamConfig file's plugins/references/types are collected in one PSI pass into a per-file model
  that is cached (and rebuilt only when that file is edited), keeping navigation, completion,
  find-usages and inspections fast without any cross-file lookup.
- A reference is *the scalar value of a key named `ref` or `parentRef`*. A plugin definition is *an
  `id:` whose mapping also has a sibling `type:`* — so a property that merely happens to be called
  `id` (e.g. `StepId`'s `properties.id: register-mtan`) is correctly **not** treated as a definition.

## Updating the bundled type catalog

`type:` completion is backed by `src/main/resources/airlock/iam-8.6-plugins.json`, generated offline
from the official Airlock IAM plugin documentation HTML (pinned to the IAM version in
`metadata.iamVersion`, currently 8.6). The 20 MB source HTML is intentionally not committed. To
regenerate for a new IAM version:

```bash
python3 tools/generate_catalog.py <iam-plugin-docs.html> src/main/resources/airlock/iam-8.6-plugins.json
```

## Building

```bash
./gradlew test            # run the test suite
./gradlew verifyPlugin    # IntelliJ Plugin Verifier
./gradlew runIde          # launch a sandbox IDE with the plugin
./gradlew buildPlugin     # produce the installable .zip in build/distributions
```

---
Plugin based on the [IntelliJ Platform Plugin Template](https://github.com/JetBrains/intellij-platform-plugin-template).

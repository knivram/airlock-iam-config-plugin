# airlock-iam-config-plugin

![Build](https://github.com/knivram/airlock-iam-config-plugin/workflows/Build/badge.svg)

<!-- Plugin description -->
Editor support for **Airlock IAM** configuration files — the large YAML documents with
`type: IamConfig` and `schemaVersion: iam.airlock.com/…`. Backed by a bundled catalog of the
IAM 8.6 plugin types: their properties, the child plugins allowed in each slot, and documentation.

These configs are trees of *plugins*. A plugin is a YAML mapping with a `type:` and an `id:`;
instead of inlining a plugin, another place can reference it by `ref:` (or inherit via `parentRef:`)
using that `id`. Because ids are opaque (usually UUIDs) and the schema is large, these files are
painful to author and read by hand. This plugin makes them first-class:

- **Navigation** — Ctrl/Cmd+Click a `ref:` / `parentRef:` to jump to the plugin `id:` it points at;
  from an `id:`, Find Usages (or Ctrl/Cmd+B) lists every reference.
- **Code completion**
  - `ref:` / `parentRef:` values — pick an existing plugin (shown by its `displayName` and type,
    matched **case-insensitively**); the id is inserted for you.
  - `type:` values — constrained to the plugins assignable to the surrounding slot, falling back to
    the full bundled catalog (~1600 IAM 8.6 types) plus types already used in the file.
  - plugin **property names**, plugin-definition keys
    (`type`/`id`/`displayName`/`comment`/`parentRef`/`properties`),
    `pluginList`/`pluginMap`/`plugin` scaffolds, and `env:` / `var:` for environment-specific values.
- **Quick documentation** — hover (or Ctrl-Q) a `type:`, a property key, or a `ref:` / `id:` to see
  the plugin's description, its properties and the child plugins allowed in each slot.
- **Folding** — a `ref:` id is displayed as the referenced plugin's display name; expand it for the id.
- **Inspections** — dangling references, duplicate ids, unknown properties, wrong child plugin types,
  missing mandatory properties (with an "add them" quick-fix), and variable/environment misconfigurations.
- A dedicated **file icon** for IamConfig files.

Everything is scoped to a **single file**: configs are commonly duplicated (e.g. a production copy),
where the same ids legitimately appear in several files — so resolving, finding usages and validating
across files would produce false duplicate-id warnings and ambiguous navigation. Validation only
applies to plugin types known in the bundled catalog, so custom/newer plugins are never flagged.
Ordinary YAML (non-IamConfig) is untouched.
<!-- Plugin description end -->

## Requirements

IntelliJ IDEA 2025.2+ (the bundled YAML plugin must be enabled).

## How it works

- Each IamConfig file's plugins/references/types are collected in one PSI pass into a per-file model
  that is cached (and rebuilt on any PSI change), keeping navigation, completion, find-usages,
  documentation, folding and inspections fast without any cross-file lookup.
- A reference is *the scalar value of a key named `ref` or `parentRef`*. A plugin definition is *an
  `id:` whose mapping also has a sibling `type:`* — so a property that merely happens to be called
  `id` (e.g. `StepId`'s `properties.id: register-mtan`) is correctly **not** treated as a definition.
- Slot-aware completion, child-type validation, property/type docs and the missing-mandatory
  inspection are driven by the bundled plugin catalog (type → properties, kinds, mandatory flags,
  assignable child types, and descriptions).

## Updating the bundled type catalog

The catalog is `src/main/resources/airlock/iam-8.6-plugins.json.gz` (gzipped; per type: properties,
kinds, mandatory flags, assignable child types and descriptions), generated offline from the official
Airlock IAM plugin documentation HTML — pinned to the IAM version in `metadata.iamVersion` (currently
8.6). The 20 MB source HTML is intentionally not committed. To regenerate for a new IAM version (the
generator gzips the output to `.gz` when it exceeds ~1 MB):

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

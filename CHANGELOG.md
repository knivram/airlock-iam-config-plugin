<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# airlock-iam-config-plugin Changelog

## [Unreleased]

### Added

- **Navigation** — go to definition from a `ref:` / `parentRef:` to the plugin `id:` it points at;
  find usages of an `id:` (and Ctrl/Cmd+B from the id) listing every reference.
- **Code completion** — `ref:` / `parentRef:` values (existing plugins, by display name, matched
  case-insensitively); slot-aware `type:` values (the plugins assignable to the surrounding slot,
  else the bundled IAM 8.6 catalog plus project-local types); plugin property names;
  plugin-definition root keys (`type`/`id`/`displayName`/`comment`/`parentRef`/`properties`);
  `pluginList`/`pluginMap`/`plugin` wrapper scaffolds; and `env:` / `var:` completion.
- **Quick documentation** on hover / Ctrl-Q for `type:` values, property keys and `ref:` / `id:`
  targets, sourced from the bundled IAM 8.6 plugin catalog.
- **Folding** of a `ref:` / `parentRef:` id to the referenced plugin's display name (expand for the id).
- **Inspections** — dangling references, duplicate plugin ids, unknown properties, wrong child plugin
  types, missing mandatory properties (error, with an "Add missing mandatory properties" quick-fix),
  and variable/environment misconfigurations.
- A dedicated file icon for IamConfig files.

All features are scoped to a single file, and validation applies only to plugin types known in the
bundled catalog (custom/newer plugins are never flagged).

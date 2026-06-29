<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# airlock-iam-config-plugin Changelog

## [Unreleased]

### Added

- **Generate UUID** — Alt+Enter on an empty plugin `id:` fills it with a fresh UUID.

### Fixed

- Missing-mandatory inspection no longer flags a Mandatory Plugin-Link that IAM auto-links from a global
  default (a singleton in the `GlobalConfiguration` settings tree).

## [0.0.2] - 2026-06-27

### Changed

- Renamed the plugin to **Config Navigator** (formerly "Airlock IAM Config"); the plugin id is unchanged.

## [0.0.1] - 2026-06-26

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

[Unreleased]: https://github.com/knivram/airlock-iam-config-plugin/compare/0.0.2...HEAD
[0.0.2]: https://github.com/knivram/airlock-iam-config-plugin/compare/0.0.1...0.0.2
[0.0.1]: https://github.com/knivram/airlock-iam-config-plugin/commits/0.0.1

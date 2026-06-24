<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# airlock-iam-config-plugin Changelog

## [Unreleased]

### Added

- Go to definition from `ref:` / `parentRef:` values to the plugin `id:` they point at, across files.
- Find usages of a plugin `id:` — lists every `ref:` / `parentRef:` that points at it.
- Code completion for `ref:` / `parentRef:` values (existing plugins, by display name) and `type:`
  values (bundled Airlock IAM 8.6 catalog plus project-local types).
- Inspections for dangling references and duplicate plugin ids.

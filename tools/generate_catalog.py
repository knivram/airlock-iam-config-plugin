#!/usr/bin/env python3
"""Generate the bundled Airlock IAM plugin-type catalog from the official HTML docs.

The Airlock IAM "plugin documentation" is published as a single self-contained HTML
page (one per IAM version, e.g. the `/iam/8.6/` plugin docs). Each plugin type is one
section that looks like:

    <h2 class="plugin-title">Abort Step</h2>
    ...
    <div class="plugin-meta-data">
        <div class="label">Type name</div>
        <div class="value">AbortStep</div>
    </div>
    <div class="plugin-meta-data">
        <div class="label">Class</div>
        <div class="value">com.airlock.iam.flow.shared...AbortStepConfig</div>
    </div>

The YAML `type:` token is the `Type name` value (NOT the class' simple name -- e.g. the
class is `AbortStepConfig` but the token is `AbortStep`). This script extracts, per
section, the type token, the human-readable title and the implementation class, and
writes a compact JSON array used by the plugin for `type:` completion.

Provenance: the source HTML is the Airlock IAM plugin documentation for the pinned IAM
version (see metadata.iamVersion in the configs, currently 8.6). The 20 MB source HTML
is intentionally NOT committed; only the generated JSON is. Regenerate after an IAM
upgrade:

    python3 tools/generate_catalog.py <plugin-docs.html> src/main/resources/airlock/iam-8.6-plugins.json
"""
from __future__ import annotations

import html
import json
import re
import sys
from pathlib import Path

TITLE_SPLIT = re.compile(r'<h2 class="plugin-title">')
TITLE_TEXT = re.compile(r'^(.*?)</h2>', re.DOTALL)
# A meta-data row: <div class="label">LABEL</div> ... <div class="value">VALUE</div>
META_ROW = re.compile(
    r'<div class="label">\s*([^<]+?)\s*</div>\s*<div class="value">\s*(.*?)\s*</div>',
    re.DOTALL,
)


def clean(text: str) -> str:
    """Unescape HTML entities and collapse whitespace."""
    return re.sub(r"\s+", " ", html.unescape(text)).strip()


def parse(source: str) -> list[dict[str, str]]:
    parts = TITLE_SPLIT.split(source)
    entries: list[dict[str, str]] = []
    seen: set[str] = set()
    for part in parts[1:]:  # parts[0] is everything before the first plugin-title
        title_match = TITLE_TEXT.match(part)
        title = clean(title_match.group(1)) if title_match else ""
        meta = {clean(label): clean(value) for label, value in META_ROW.findall(part)}
        type_name = meta.get("Type name", "")
        if not type_name:
            continue
        if type_name in seen:
            continue
        seen.add(type_name)
        entry = {"type": type_name, "title": title}
        cls = meta.get("Class")
        if cls:
            entry["class"] = cls
        entries.append(entry)
    entries.sort(key=lambda e: e["type"])
    return entries


def main() -> int:
    if len(sys.argv) != 3:
        print(__doc__)
        print("usage: generate_catalog.py <input.html> <output.json>", file=sys.stderr)
        return 2
    src = Path(sys.argv[1]).read_text(encoding="utf-8")
    entries = parse(src)
    # Sanity guard: the real IAM catalog has ~1600 plugins. A tiny count means the markup drifted
    # and the regexes silently stopped matching — fail loudly rather than ship an empty catalog.
    MIN_EXPECTED = 500
    if len(entries) < MIN_EXPECTED:
        print(
            f"ERROR: parsed only {len(entries)} plugin types (expected >= {MIN_EXPECTED}). "
            "The source HTML markup likely changed; update the parser before regenerating.",
            file=sys.stderr,
        )
        return 1
    out_path = Path(sys.argv[2])
    out_path.parent.mkdir(parents=True, exist_ok=True)
    out_path.write_text(json.dumps(entries, indent=0, ensure_ascii=False) + "\n", encoding="utf-8")
    print(f"wrote {len(entries)} plugin types to {out_path}")
    # sanity sample
    for sample in ("AbortStep", "JdbcConnectionPool", "TagCondition"):
        hit = next((e for e in entries if e["type"] == sample), None)
        print(f"  {sample!r:24} -> {hit}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

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
    ...
    <div class="plugin-property">
        <div class="property-name">Error Code&nbsp;(<code>errorCode</code>)</div>
        <div class="property-label">Attributes</div>
        <div class="property-value">
            <div class="property-tag">String</div>
            <div class="property-tag">Mandatory</div>
        </div>
    </div>

The YAML `type:` token is the `Type name` value (NOT the class' simple name -- e.g. the
class is `AbortStepConfig` but the token is `AbortStep`). The YAML property key is the
`<code>` content inside `property-name`.

This script extracts, per section: the type token, human-readable title, implementation
class, an optional plugin-level `description`, and the list of properties. Each property
records its yamlKey, its KIND tag (String/Boolean/.../Plugin-Link/Plugin-List/Plugin-Map),
whether it is Mandatory, whether it is Sensitive, an optional `description`, and -- for the
plugin-valued kinds (Plugin-Link/List/Map) -- the list of assignable plugin *type tokens*
(resolved from the assignable-plugin FQCN links through the catalog-wide class->type map;
unresolved FQCNs are dropped).

Both the plugin-level and per-property `description` are the docs' "Description" prose,
SANITIZED at build time to the IntelliJ quick-doc HTML subset (keep p/br/code/pre/em/i/b/
strong/ul/ol/li; turn `<a ...>text</a>` into its inner text; strip every other tag keeping
inner text; collapse whitespace outside `<pre>`). Storing render-ready HTML keeps the
runtime provider trivial and still compresses well.

The result is a compact JSON array. Backward compatible with the previous format: the
loader's `RawEntry`/`RawProperty` ignore unknown fields, and the `description` fields are
simply absent when missing. If the serialized JSON exceeds ~1 MB it is written gzip'd as
`<output>.gz` (and the plain `<output>` is removed) -- the plugin's loader tries `.gz` first.

Provenance: the source HTML is the Airlock IAM plugin documentation for the pinned IAM
version (see metadata.iamVersion in the configs, currently 8.6). The 20 MB source HTML is
intentionally NOT committed; only the generated JSON[.gz] is. Regenerate after an IAM
upgrade:

    python3 tools/generate_catalog.py <plugin-docs.html> src/main/resources/airlock/iam-8.6-plugins.json
"""
from __future__ import annotations

import gzip
import html
import json
import re
import sys
from pathlib import Path

TITLE_SPLIT = re.compile(r'<h2 class="plugin-title">')
TITLE_TEXT = re.compile(r"^(.*?)</h2>", re.DOTALL)
# A meta-data row: <div class="label">LABEL</div> ... <div class="value">VALUE</div>
META_ROW = re.compile(
    r'<div class="label">\s*([^<]+?)\s*</div>\s*<div class="value">\s*(.*?)\s*</div>',
    re.DOTALL,
)

# One <div class="plugin-property"> ... block (up to the next plugin-property or end of section).
PROPERTY_BLOCK = re.compile(
    r'<div class="plugin-property">(.*?)(?=<div class="plugin-property">|\Z)',
    re.DOTALL,
)
# The yamlKey is the <code> content inside property-name.
PROPERTY_NAME_CODE = re.compile(
    r'<div class="property-name">.*?<code>\s*(.*?)\s*</code>',
    re.DOTALL,
)
PROPERTY_TAG = re.compile(r'<div class="property-tag">\s*(.*?)\s*</div>')
ASSIGN_LINK = re.compile(r'<a class="plugin-link" href="#([^"]+)"')

# Plugin-level description. The `value` never nests a <div> (verified), so a non-greedy
# capture to the first </div> is safe.
PLUGIN_DESC = re.compile(
    r'<div class="plugin-description">\s*<div class="label">\s*Description\s*</div>\s*'
    r'<div class="value">(.*?)</div>',
    re.DOTALL,
)

# HTML sanitization for the IntelliJ quick-doc popup (build-time -> render-ready).
ALLOWED_TAGS = {"p", "br", "code", "pre", "em", "i", "b", "strong", "ul", "ol", "li"}
TAG_RE = re.compile(r"<(/?)([a-zA-Z][a-zA-Z0-9]*)\b[^>]*>")


def sanitize_doc_html(raw: str) -> str:
    """Reduce docs HTML to the IntelliJ quick-doc subset. <a>->inner text; unknown tags
    stripped (inner text kept); allowed tags kept without attributes. Whitespace collapsed
    outside <pre>. Returns '' for empty/whitespace-only input. Entities are left escaped
    (do NOT html.unescape -- the swing HTML pane wants &lt;/&amp; intact)."""
    if not raw or not raw.strip():
        return ""
    # 1) drop <a ...> and </a> markers entirely, keeping inner text (no cross-nav).
    s = re.sub(r"</?a\b[^>]*>", "", raw)

    # 2) rewrite each remaining tag: keep allowed as bare <tag>/</tag>; strip others.
    def repl(m: re.Match) -> str:
        closing, name = m.group(1), m.group(2).lower()
        if name not in ALLOWED_TAGS:
            return ""
        if name == "br":
            return "<br>"
        return f"</{name}>" if closing else f"<{name}>"

    s = TAG_RE.sub(repl, s)
    # 2b) balance block tags: drop orphan closers, auto-close anything left open (LIFO).
    bal: list[str] = []
    stack: list[str] = []
    pos = 0
    for m in TAG_RE.finditer(s):
        bal.append(s[pos:m.start()])
        pos = m.end()
        closing, name = m.group(1), m.group(2).lower()
        if name == "br":
            bal.append("<br>")
            continue
        if not closing:
            stack.append(name)
            bal.append(f"<{name}>")
        elif stack and stack[-1] == name:
            stack.pop()
            bal.append(f"</{name}>")
        # else: orphan close with no matching opener -> drop it
    bal.append(s[pos:])
    bal += [f"</{t}>" for t in reversed(stack)]
    s = "".join(bal)
    # 3) collapse whitespace outside <pre>...</pre>; preserve pre verbatim.
    out: list[str] = []
    i = 0
    for m in re.finditer(r"<pre>.*?</pre>", s, re.DOTALL):
        out.append(re.sub(r"\s+", " ", s[i:m.start()]))
        out.append(m.group(0))  # verbatim
        i = m.end()
    out.append(re.sub(r"\s+", " ", s[i:]))
    return "".join(out).strip()

# Property KIND tags (exactly one per property). Order/spelling per the docs vocabulary.
KIND_TAGS = {
    "String",
    "Boolean",
    "Integer",
    "Long",
    "Double",
    "Enum",
    "RegEx",
    "Multi-line-text",
    "File/Path",
    "String-List",
    "RegEx-List",
    "Plugin-Link",
    "Plugin-List",
    "Plugin-Map",
}
PLUGIN_VALUED_KINDS = {"Plugin-Link", "Plugin-List", "Plugin-Map"}

# YAML structural wrapper keys -- container/override forms, never catalog property names. Used only
# by the heuristic cross-check to avoid counting them as "unknown properties".
STRUCTURAL_KEYS = {
    "value", "var", "env", "ref", "plugin", "pluginList", "pluginMap", "valueList",
}


def clean(text: str) -> str:
    """Unescape HTML entities and collapse whitespace."""
    return re.sub(r"\s+", " ", html.unescape(text)).strip()


def split_label_segments(block: str) -> dict[str, str]:
    """Split a property block into {label: value-html} segments.

    Property values can contain nested <div>s (tags, links, <p>), so we never regex *across*
    a value. Instead we slice the block on `<div class="property-label">` markers: each marker
    starts a label whose text runs to its closing `</div>`, and the value-html is everything
    up to the next label marker (or end of block). The leading chunk before the first label is
    the property-name and is ignored here.
    """
    LABEL_MARK = '<div class="property-label">'
    VALUE_MARK = '<div class="property-value">'
    segments: dict[str, str] = {}
    parts = block.split(LABEL_MARK)
    for part in parts[1:]:  # skip the property-name chunk
        # part == "Attributes   </div>\n<div class=\"property-value\">...value...(next label or EOB)"
        end_label = part.find("</div>")
        if end_label == -1:
            continue
        label = clean(part[:end_label])
        rest = part[end_label + len("</div>"):]
        v_start = rest.find(VALUE_MARK)
        if v_start == -1:
            segments.setdefault(label, "")
            continue
        value_html = rest[v_start + len(VALUE_MARK):]
        # Keep only the first label's value for a given label.
        segments.setdefault(label, value_html)
    return segments


def parse_properties(section: str) -> list[dict]:
    """Extract the raw property descriptors from one plugin section (FQCNs not yet resolved)."""
    props: list[dict] = []
    for block in PROPERTY_BLOCK.findall(section):
        name_match = PROPERTY_NAME_CODE.search(block)
        if not name_match:
            continue  # no yamlKey -> skip (never observed, but guard)
        key = clean(name_match.group(1))
        if not key:
            continue
        segments = split_label_segments(block)
        attributes_html = segments.get("Attributes", "")
        tags = [clean(t) for t in PROPERTY_TAG.findall(attributes_html)]
        kind = next((t for t in tags if t in KIND_TAGS), None)
        mandatory = "Mandatory" in tags
        sensitive = "Sensitive" in tags
        prop: dict = {"key": key}
        if kind:
            prop["kind"] = kind
        if mandatory:
            prop["mandatory"] = True
        if sensitive:
            prop["sensitive"] = True
        desc = sanitize_doc_html(segments.get("Description", ""))
        if desc:
            prop["description"] = desc
        if kind in PLUGIN_VALUED_KINDS:
            assignable_html = segments.get("Assignable plugins", "")
            fqcns = ASSIGN_LINK.findall(assignable_html)
            # store raw FQCNs for a second resolution pass; dedupe preserving order
            seen_f: set[str] = set()
            ordered: list[str] = []
            for f in fqcns:
                fq = html.unescape(f).strip()
                if fq and fq not in seen_f:
                    seen_f.add(fq)
                    ordered.append(fq)
            if ordered:
                prop["_assignableFqcns"] = ordered
        props.append(prop)
    return props


def parse(source: str) -> list[dict]:
    parts = TITLE_SPLIT.split(source)
    entries: list[dict] = []
    seen: set[str] = set()
    total_props = 0
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
        entry: dict = {"type": type_name, "title": title}
        cls = meta.get("Class")
        if cls:
            entry["class"] = cls
        dm = PLUGIN_DESC.search(part)
        desc = sanitize_doc_html(dm.group(1)) if dm else ""
        if desc:
            entry["description"] = desc
        properties = parse_properties(part)
        total_props += len(properties)
        entry["properties"] = properties
        entries.append(entry)
    entries.sort(key=lambda e: e["type"])

    # Second pass: resolve assignable FQCNs to type tokens via the class->type map.
    fqcn_to_type = {e["class"]: e["type"] for e in entries if e.get("class")}
    unresolved_samples: list[tuple[str, str, str]] = []
    for entry in entries:
        for prop in entry["properties"]:
            fqcns = prop.pop("_assignableFqcns", None)
            if not fqcns:
                continue
            resolved: list[str] = []
            seen_t: set[str] = set()
            for fq in fqcns:
                token = fqcn_to_type.get(fq)
                if token is None:
                    if len(unresolved_samples) < 20:
                        unresolved_samples.append((entry["type"], prop["key"], fq))
                    continue
                if token not in seen_t:
                    seen_t.add(token)
                    resolved.append(token)
            if resolved:
                prop["assignableTypes"] = sorted(resolved)
    parse.total_props = total_props  # type: ignore[attr-defined]
    parse.unresolved_samples = unresolved_samples  # type: ignore[attr-defined]
    return entries


def crosscheck(entries: list[dict], example_path: Path) -> None:
    """Cross-check against example-config.yaml: every used `type:` (except IamConfig) must be in
    the catalog, and property keys used under known parents should be known catalog keys. Reports
    coverage and FAILS LOUDLY if type coverage is not essentially complete."""
    if not example_path.exists():
        print(f"  (skip cross-check: {example_path} not found)")
        return
    text = example_path.read_text(encoding="utf-8")
    used_types = set(re.findall(r"^\s*(?:- )?type:\s*([A-Za-z0-9_]+)\s*$", text, re.MULTILINE))
    used_types.discard("IamConfig")
    by_type = {e["type"]: e for e in entries}
    missing = sorted(t for t in used_types if t not in by_type)
    found = len(used_types) - len(missing)
    type_cov = 100.0 * found / len(used_types) if used_types else 100.0
    print(f"  type coverage: {found}/{len(used_types)} = {type_cov:.1f}%")
    if missing:
        print(f"  MISSING TYPES ({len(missing)}): {missing[:30]}")

    # Property-key coverage: parse the YAML structurally enough to map property keys to the
    # enclosing plugin type. We do a light indentation-based walk: track a stack of (indent,
    # type) for mappings that declare a `type:`, and when we see a key directly under a
    # `properties:` block of a known type, check membership. This is heuristic but good enough
    # to surface gross markup/extraction failures.
    known_keys_by_type = {
        e["type"]: {p["key"] for p in e.get("properties", [])} for e in entries
    }
    total_keys = 0
    known_keys = 0
    unknown_samples: list[tuple[str, str]] = []
    # Build a simple line model.
    lines = text.split("\n")

    def indent_of(s: str) -> int:
        return len(s) - len(s.lstrip(" "))

    # Stack frames: dicts with indent, type (str|None), in_properties (bool), props_indent (int|None)
    # We approximate: a plugin mapping is recognised when we see `type: X` at some indent; the
    # `properties:` key under it is at the same indent; its children are at properties_indent+more.
    # For each scalar/mapping key directly under a properties block we check membership.
    # Track most-recent type at each indent level.
    type_at_indent: dict[int, str] = {}
    props_active: dict[int, str] = {}  # properties-children-indent -> owner type (unknown via deeper)
    for raw in lines:
        if not raw.strip() or raw.lstrip().startswith("#"):
            continue
        ind = indent_of(raw)
        # drop deeper frames
        for k in [k for k in type_at_indent if k > ind]:
            del type_at_indent[k]
        for k in [k for k in props_active if k > ind]:
            del props_active[k]
        stripped = raw.strip()
        m_type = re.match(r"(?:- )?type:\s*([A-Za-z0-9_]+)\s*$", stripped)
        if m_type:
            type_at_indent[ind] = m_type.group(1)
            continue
        m_props = re.match(r"properties:\s*$", stripped)
        if m_props:
            owner = type_at_indent.get(ind)
            if owner and owner in known_keys_by_type and known_keys_by_type[owner]:
                props_active[ind + 2] = owner  # children typically indented +2
            continue
        # A property key directly at a properties-children indent?
        owner = props_active.get(ind)
        if owner:
            m_key = re.match(r"(?:- )?([A-Za-z0-9_]+):", stripped)
            if m_key:
                key = m_key.group(1)
                # Skip YAML structural wrapper keys -- these are container forms (Plugin-List/Map,
                # value+env overrides), not catalog property names.
                if key in STRUCTURAL_KEYS:
                    continue
                total_keys += 1
                if key in known_keys_by_type[owner]:
                    known_keys += 1
                elif len(unknown_samples) < 30:
                    unknown_samples.append((owner, key))
    key_cov = 100.0 * known_keys / total_keys if total_keys else 100.0
    print(f"  property-key coverage (sampled under known parents): {known_keys}/{total_keys} = {key_cov:.1f}%")
    if unknown_samples:
        print(f"  unknown property-key samples: {unknown_samples[:30]}")

    if missing:
        raise SystemExit(
            f"ERROR: {len(missing)} type(s) used in {example_path.name} are absent from the catalog "
            f"-> extraction is incomplete: {missing}"
        )


def main() -> int:
    if len(sys.argv) != 3:
        print(__doc__)
        print("usage: generate_catalog.py <input.html> <output.json>", file=sys.stderr)
        return 2
    in_path = Path(sys.argv[1])
    src = in_path.read_text(encoding="utf-8")
    entries = parse(src)

    # Sanity guard: the real IAM catalog has ~1600 plugins. A tiny count means the markup drifted.
    MIN_EXPECTED = 500
    if len(entries) < MIN_EXPECTED:
        print(
            f"ERROR: parsed only {len(entries)} plugin types (expected >= {MIN_EXPECTED}). "
            "The source HTML markup likely changed; update the parser before regenerating.",
            file=sys.stderr,
        )
        return 1
    total_props = getattr(parse, "total_props", 0)
    MIN_PROPS = 5000  # measured ~7085; a big drop means the property parser broke.
    if total_props < MIN_PROPS:
        print(
            f"ERROR: parsed only {total_props} properties (expected >= {MIN_PROPS}). "
            "The property-block markup likely changed; update the parser.",
            file=sys.stderr,
        )
        return 1

    plugin_desc = sum(1 for e in entries if e.get("description"))
    prop_desc = sum(1 for e in entries for p in e["properties"] if p.get("description"))
    MIN_PLUGIN_DESC, MIN_PROP_DESC = 1400, 6000  # measured ~1587 / ~7085
    if plugin_desc < MIN_PLUGIN_DESC or prop_desc < MIN_PROP_DESC:
        print(
            f"ERROR: descriptions under-extracted (plugin={plugin_desc}, prop={prop_desc}; "
            f"expected >= {MIN_PLUGIN_DESC}/{MIN_PROP_DESC}). The description markup likely "
            "changed; update the parser.",
            file=sys.stderr,
        )
        return 1

    payload = json.dumps(entries, indent=0, ensure_ascii=False) + "\n"
    size = len(payload.encode("utf-8"))

    out_path = Path(sys.argv[2])
    out_path.parent.mkdir(parents=True, exist_ok=True)
    gz_path = out_path.with_suffix(out_path.suffix + ".gz")
    GZIP_THRESHOLD = 1_000_000
    if size > GZIP_THRESHOLD:
        with gzip.open(gz_path, "wt", encoding="utf-8") as fh:
            fh.write(payload)
        if out_path.exists():
            out_path.unlink()  # avoid shipping both forms for the same version
        written = gz_path
        gz_size = gz_path.stat().st_size
        print(f"wrote {len(entries)} plugin types ({total_props} properties) to {written}")
        print(f"  raw JSON size: {size:,} bytes (> {GZIP_THRESHOLD:,}) -> gzipped to {gz_size:,} bytes")
    else:
        out_path.write_text(payload, encoding="utf-8")
        if gz_path.exists():
            gz_path.unlink()
        written = out_path
        print(f"wrote {len(entries)} plugin types ({total_props} properties) to {written}")
        print(f"  JSON size: {size:,} bytes (<= {GZIP_THRESHOLD:,}) -> plain JSON")

    # Report unresolved-assignable samples (informational; expected to be near-zero).
    unresolved = getattr(parse, "unresolved_samples", [])
    if unresolved:
        print(f"  unresolved assignable FQCNs (sample {len(unresolved)}): {unresolved[:10]}")
    else:
        print("  all assignable FQCNs resolved to type tokens")

    print(f"  descriptions: {plugin_desc} plugin-level, {prop_desc} per-property")

    # Cross-check against the example config (fails loudly on missing types).
    example = in_path  # placeholder; real path resolved below
    example = Path(__file__).resolve().parent.parent / "example-config.yaml"
    crosscheck(entries, example)

    # Sample dump.
    print("  samples:")
    for sample in ("AbortStep", "JdbcConnectionPool", "GenericHttpSmsGateway"):
        hit = next((e for e in entries if e["type"] == sample), None)
        if hit:
            nprops = len(hit.get("properties", []))
            plugin_props = [p["key"] for p in hit.get("properties", []) if p.get("kind") in PLUGIN_VALUED_KINDS]
            print(f"    {sample!r:24} props={nprops} plugin-valued={plugin_props}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

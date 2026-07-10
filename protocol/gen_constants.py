#!/usr/bin/env python3
"""Generate the shared wire-protocol constants for both language sides from protocol.json.

`protocol/protocol.json` is the single source of truth for the versions, movement/action
bitmask positions, slot-group opcodes, and inventory-op opcodes that the OCLO/OCLI/OCLV wire
format shares between the mod (Java) and the controller (Python). This script turns that one
spec into two generated, checked-in files:

  * src/main/java/mod/kelvinlby/link/generated/Protocol.java  (public static final ints)
  * pylib/src/ocl/_protocol.py                                (module-level ints)

Both are regenerated and committed; CI runs this script and `git diff --exit-code` so a spec
edit that wasn't regenerated fails the build. Bit positions in the spec become `1 << pos`;
group/op opcodes are emitted verbatim. Pure stdlib (Python 3.9+), no third-party deps.

Usage: python protocol/gen_constants.py   (writes both files, relative to the repo root)
"""

from __future__ import annotations

import json
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
SPEC_PATH = REPO_ROOT / "protocol" / "protocol.json"
JAVA_OUT = REPO_ROOT / "src" / "main" / "java" / "mod" / "kelvinlby" / "link" / "generated" / "Protocol.java"
PYTHON_OUT = REPO_ROOT / "pylib" / "src" / "ocl" / "_protocol.py"

GENERATED_NOTE = "GENERATED FROM protocol/protocol.json BY protocol/gen_constants.py — DO NOT EDIT BY HAND."


def load_spec() -> dict:
    with SPEC_PATH.open("r", encoding="utf-8") as fh:
        return json.load(fh)


def java_constants(spec: dict) -> list[str]:
    """Ordered (name, value, comment) constant lines for the Java file, as `public static final int`."""
    lines: list[str] = []
    lines.append(f"\tpublic static final int VERSION = {spec['version']};")
    lines.append(f"\tpublic static final int VIS_VERSION = {spec['vis_version']};")
    lines.append("")
    lines.append("\t// Movement bitmask (1 << position).")
    for name, pos in spec["movement_bits"].items():
        lines.append(f"\tpublic static final int M_{name} = 1 << {pos};")
    lines.append("")
    lines.append("\t// Action bitmask (1 << position).")
    for name, pos in spec["action_bits"].items():
        lines.append(f"\tpublic static final int A_{name} = 1 << {pos};")
    lines.append("")
    lines.append("\t// Slot-group wire opcodes.")
    for name, val in spec["slot_groups"].items():
        lines.append(f"\tpublic static final int G_{name} = {val};")
    lines.append("")
    lines.append("\t// Inventory-action wire opcodes.")
    for name, val in spec["ops"].items():
        lines.append(f"\tpublic static final int OP_{name} = {val};")
    return lines


def render_java(spec: dict) -> str:
    body = "\n".join(java_constants(spec))
    return (
        "package mod.kelvinlby.link.generated;\n"
        "\n"
        "/**\n"
        f" * {GENERATED_NOTE}\n"
        " *\n"
        " * <p>Shared OCLO/OCLI/OCLV wire constants — versions, movement/action bitmasks, slot-group\n"
        " * opcodes, and inventory-op opcodes. The Python side mirror is {@code pylib/src/ocl/_protocol.py},\n"
        " * generated from the same spec so the two languages can never silently disagree.\n"
        " */\n"
        "public final class Protocol {\n"
        "\tprivate Protocol() {}\n"
        "\n"
        f"{body}\n"
        "}\n"
    )


def python_constants(spec: dict) -> list[str]:
    lines: list[str] = []
    lines.append(f"VERSION = {spec['version']}")
    lines.append(f"VIS_VERSION = {spec['vis_version']}")
    lines.append("")
    lines.append("# Movement bitmask (1 << position).")
    for name, pos in spec["movement_bits"].items():
        lines.append(f"M_{name} = 1 << {pos}")
    lines.append("")
    lines.append("# Action bitmask (1 << position).")
    for name, pos in spec["action_bits"].items():
        lines.append(f"A_{name} = 1 << {pos}")
    lines.append("")
    lines.append("# Slot-group wire opcodes.")
    for name, val in spec["slot_groups"].items():
        lines.append(f"G_{name} = {val}")
    lines.append("")
    lines.append("# Inventory-action wire opcodes.")
    for name, val in spec["ops"].items():
        lines.append(f"OP_{name} = {val}")
    return lines


def render_python(spec: dict) -> str:
    body = "\n".join(python_constants(spec))
    return (
        '"""Shared OCLO/OCLI/OCLV wire constants (mirror of the mod\'s Protocol.java).\n'
        "\n"
        f"{GENERATED_NOTE}\n"
        '"""\n'
        "\n"
        f"{body}\n"
    )


def write_if_changed(path: Path, content: str) -> bool:
    path.parent.mkdir(parents=True, exist_ok=True)
    old = path.read_text(encoding="utf-8") if path.exists() else None
    if old == content:
        return False
    path.write_text(content, encoding="utf-8")
    return True


def main() -> None:
    spec = load_spec()
    changed_java = write_if_changed(JAVA_OUT, render_java(spec))
    changed_py = write_if_changed(PYTHON_OUT, render_python(spec))
    for path, changed in ((JAVA_OUT, changed_java), (PYTHON_OUT, changed_py)):
        rel = path.relative_to(REPO_ROOT)
        print(f"{'wrote' if changed else 'unchanged'}: {rel}")


if __name__ == "__main__":
    main()

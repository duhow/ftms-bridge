#!/usr/bin/env python3
"""Automatic linter for Android project.

Checks:
  - XML validity for all resource XML files
  - Language string parity across locale folders (same keys as base values/)
  - Language array counts (language_codes vs language_names in arrays.xml)
  - Bracket balance ( ) { } [ ] in Kotlin/Java source files

Usage (standalone — exit code = number of failed checks):
    python tools/lint.py [--max-warns N] [--no-pytest]

Usage (via pytest — standard pytest exit codes):
    pytest tools/lint.py

Options:
    --max-warns N   Allow up to N failures without exiting non-zero (for CI).
    --no-pytest     Skip pytest even if available; use built-in runner.
"""
from __future__ import annotations

import argparse
import sys
from pathlib import Path
from xml.etree import ElementTree as ET

# ---------------------------------------------------------------------------
# Paths
# ---------------------------------------------------------------------------
REPO_ROOT = Path(__file__).resolve().parent.parent
RES_DIR = REPO_ROOT / "app" / "src" / "main" / "res"
SRC_DIRS = [REPO_ROOT / "app" / "src"]

# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

def _strip_code_noise(content: str) -> str:
    """Strip string literals and comments, preserving only structural chars."""
    result: list[str] = []
    i = 0
    n = len(content)
    while i < n:
        # Triple-quoted string (Kotlin multiline)
        if content[i:i + 3] == '"""':
            i += 3
            while i < n and content[i:i + 3] != '"""':
                i += 1
            i += 3
        # Double-quoted string (single line)
        elif content[i] == '"':
            i += 1
            while i < n and content[i] != '"' and content[i] != '\n':
                if content[i] == '\\' and i + 1 < n:
                    i += 1
                i += 1
            i += 1
        # Single-quoted char literal
        elif content[i] == "'":
            i += 1
            while i < n and content[i] != "'" and content[i] != '\n':
                if content[i] == '\\' and i + 1 < n:
                    i += 1
                i += 1
            i += 1
        # Line comment
        elif content[i:i + 2] == '//':
            while i < n and content[i] != '\n':
                i += 1
        # Block comment
        elif content[i:i + 2] == '/*':
            i += 2
            while i < n - 1 and content[i:i + 2] != '*/':
                i += 1
            i += 2
        else:
            result.append(content[i])
            i += 1
    return ''.join(result)


def _get_string_names(xml_path: Path) -> set[str]:
    try:
        tree = ET.parse(xml_path)
        return {e.get("name") for e in tree.getroot().findall("string") if e.get("name")}
    except ET.ParseError:
        return set()


# ---------------------------------------------------------------------------
# Core checks — return list[str] of issue descriptions (empty list = pass)
# ---------------------------------------------------------------------------

def _check_xml_validity() -> list[str]:
    issues: list[str] = []
    for xml_file in sorted(RES_DIR.rglob("*.xml")):
        try:
            ET.parse(xml_file)
        except ET.ParseError as exc:
            issues.append(f"{xml_file.relative_to(REPO_ROOT)}: {exc}")
    return issues


def _check_language_parity() -> list[str]:
    issues: list[str] = []
    base = RES_DIR / "values" / "strings.xml"
    if not base.exists():
        return []
    base_names = _get_string_names(base)
    for lang_dir in sorted(RES_DIR.glob("values-*")):
        lang_file = lang_dir / "strings.xml"
        if not lang_file.exists():
            issues.append(f"{lang_dir.name}: missing strings.xml")
            continue
        lang_names = _get_string_names(lang_file)
        missing = base_names - lang_names
        extra = lang_names - base_names
        if missing:
            issues.append(f"{lang_dir.name}/strings.xml: missing keys {sorted(missing)}")
        if extra:
            issues.append(f"{lang_dir.name}/strings.xml: extra keys {sorted(extra)}")
    return issues


def _check_language_arrays() -> list[str]:
    issues: list[str] = []
    arrays_xml = RES_DIR / "values" / "arrays.xml"
    if not arrays_xml.exists():
        return []
    try:
        root = ET.parse(arrays_xml).getroot()
    except ET.ParseError:
        return []  # covered by XML validity check

    arrays: dict[str, list[str]] = {}
    for arr in root.findall("string-array"):
        name = arr.get("name", "")
        arrays[name] = [item.text or "" for item in arr.findall("item")]

    codes = arrays.get("language_codes", [])
    names = arrays.get("language_names", [])

    if "language_codes" in arrays and "language_names" in arrays:
        if len(codes) != len(names):
            issues.append(
                f"arrays.xml: language_codes has {len(codes)} items "
                f"but language_names has {len(names)} items"
            )

    # Cross-check non-default codes appear somewhere in locale_config.xml
    locale_config = RES_DIR / "xml" / "locale_config.xml"
    if locale_config.exists() and codes:
        try:
            lc_text = locale_config.read_text(encoding="utf-8")
            for code in codes:
                if code == "en":
                    continue  # default locale, may be implicit
                if code not in lc_text:
                    issues.append(
                        f"arrays.xml: language '{code}' not found in locale_config.xml"
                    )
        except OSError:
            pass

    return issues


def _check_bracket_balance() -> list[str]:
    issues: list[str] = []
    openers = set("({[")
    pairs = {"(": ")", "{": "}", "[": "]"}
    closers = set(")]}")

    source_files: list[Path] = []
    for src_dir in SRC_DIRS:
        source_files.extend(src_dir.rglob("*.kt"))
        source_files.extend(src_dir.rglob("*.java"))

    for src_file in sorted(source_files):
        try:
            content = src_file.read_text(encoding="utf-8")
        except OSError:
            continue
        stripped = _strip_code_noise(content)
        stack: list[tuple[str, int]] = []
        error: str | None = None
        line = 1
        for ch in stripped:
            if ch == '\n':
                line += 1
            elif ch in openers:
                stack.append((ch, line))
            elif ch in closers:
                if not stack:
                    error = f"unexpected '{ch}' at line {line}"
                    break
                opener, _ = stack.pop()
                if pairs[opener] != ch:
                    error = f"'{opener}' closed by '{ch}' at line {line}"
                    break
        if error:
            issues.append(f"{src_file.relative_to(REPO_ROOT)}: {error}")
        elif stack:
            ch, at_line = stack[-1]
            issues.append(
                f"{src_file.relative_to(REPO_ROOT)}: unclosed '{ch}' opened at line {at_line}"
            )

    return issues


# ---------------------------------------------------------------------------
# Pytest-compatible test functions
# ---------------------------------------------------------------------------

def test_xml_files_valid() -> None:
    issues = _check_xml_validity()
    assert not issues, f"{len(issues)} invalid XML file(s):\n" + "\n".join(
        f"  {i}" for i in issues
    )


def test_language_parity() -> None:
    issues = _check_language_parity()
    assert not issues, "Language string parity issues:\n" + "\n".join(
        f"  {i}" for i in issues
    )


def test_language_arrays() -> None:
    issues = _check_language_arrays()
    assert not issues, "Language array issues:\n" + "\n".join(
        f"  {i}" for i in issues
    )


def test_bracket_balance() -> None:
    issues = _check_bracket_balance()
    assert not issues, f"Bracket balance issues:\n" + "\n".join(
        f"  {i}" for i in issues
    )


# ---------------------------------------------------------------------------
# Standalone runner (no pytest required)
# ---------------------------------------------------------------------------

_CHECKS: list[tuple[str, object]] = [
    ("XML validity", _check_xml_validity),
    ("Language parity", _check_language_parity),
    ("Language arrays", _check_language_arrays),
    ("Bracket balance", _check_bracket_balance),
]


def _run_standalone(max_warns: int = 0) -> int:
    sep = "=" * 60
    print(sep)
    print("Android Project Linter")
    print(sep)
    failures = 0
    for name, check_fn in _CHECKS:
        issues = check_fn()  # type: ignore[call-arg]
        if issues:
            failures += 1
            print(f"\nFAIL  {name}")
            for issue in issues:
                print(f"      {issue}")
        else:
            print(f"PASS  {name}")

    print(f"\n{sep}")
    total = len(_CHECKS)
    print(f"Results: {total - failures}/{total} checks passed, {failures} failed.")

    if max_warns > 0 and failures <= max_warns:
        print(f"(Tolerating up to {max_warns} failure(s) — exiting 0 for CI)")
        return 0

    return min(failures, 125)  # keep within safe exit-code range


# ---------------------------------------------------------------------------
# Entry point
# ---------------------------------------------------------------------------

if __name__ == "__main__":
    parser = argparse.ArgumentParser(
        description=__doc__,
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    parser.add_argument(
        "--max-warns",
        type=int,
        default=0,
        metavar="N",
        help="Allow up to N check failures without exiting non-zero",
    )
    parser.add_argument(
        "--no-pytest",
        action="store_true",
        help="Skip pytest even if available; use built-in runner",
    )
    args = parser.parse_args()

    # Delegate to pytest when available and no special exit-code flags are set
    use_pytest = not args.no_pytest and args.max_warns == 0
    if use_pytest:
        try:
            import pytest  # type: ignore[import]

            sys.exit(pytest.main([__file__, "-v"]))
        except ImportError:
            pass

    sys.exit(_run_standalone(max_warns=args.max_warns))

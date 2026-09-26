#!/usr/bin/env python3
"""Fail when a Hermes-host network transport bypasses the tailnet enforcer.

"Always connect via Tailscale" (per connection, opt-in) is enforced at exactly
one seam: every OkHttp client that talks to the Hermes computer is constructed
through `HermesClients.build(...)` or decorated with `.enforceTailnetPolicy(...)`
(`app/src/main/kotlin/.../network/shared/HermesClients.kt`). That seam only holds
if nothing else builds a client — which is exactly what this script proves.

It is pure static analysis of Kotlin/Java source text, so it runs in CI where no
JDK/Android SDK exists (the same reason `check-android-collection-apis.py` exists).

Rule A — enforcement coverage. Every `OkHttpClient.Builder()` / `OkHttpClient()`
construction site under `app/src/main/kotlin` must either use the enforcement
vocabulary with the site in scope, or appear in `EXEMPTIONS` (the single data
table at the top of this file). An unlisted, undecorated site is a violation, so
a new client cannot slip in without a reviewed exemption.

Rule B — forbidden OS-level routing APIs. `bindProcessToNetwork`,
`setProcessDefaultNetwork`, `VpnService` and `setUnderlyingNetworks` may not
appear anywhere under `app/src/main` (any file type). The setting is per
connection and per socket; the app never becomes a VPN and never binds the whole
process (DECISIONS D4 / ADR 75; that is also why "route the whole phone" is
never offered to the user).

Rule C — frozen package. The tailnet enforcement classes live in
`com.hermesandroid.relay.network.shared`; the retired
`com.hermesandroid.relay.net.tailnet` package must not come back.

Usage:
    python3 scripts/check-android-hermes-transports.py [--repo-root DIR]
                                                       [--json] [--quiet]

Exit codes: 0 = pass, 1 = violations found, 2 = usage/internal error.
"""
from __future__ import annotations

import argparse
from collections import namedtuple
import json
import re
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]

# Only Kotlin application sources are scanned for transport constructions; the
# forbidden-API and package rules scan every file type under APP_MAIN.
APP_MAIN = Path("app/src/main")
KOTLIN_SOURCES = Path("app/src/main/kotlin")
IGNORED_PARTS = {".git", ".gradle", ".idea", "build", "node_modules"}

# The one accepted way to build a client that may reach the Hermes computer.
ENFORCEMENT_VOCABULARY = (
    "HermesClients.build(",
    ".enforceTailnetPolicy(",
)

# Rule B: device-wide / process-wide routing APIs. The feature binds one socket
# per connection instead; these must never appear.
FORBIDDEN_APIS = (
    "bindProcessToNetwork",
    "setProcessDefaultNetwork",
    "VpnService",
    "setUnderlyingNetworks",
)

# Rule C: the retired package for these classes (they live in network/shared).
FROZEN_PACKAGE = "com.hermesandroid.relay.net.tailnet"
FROZEN_PACKAGE_DECL = re.compile(
    rf"^[ \t]*package[ \t]+({re.escape(FROZEN_PACKAGE)}(?:\.[\w.]+)?)[ \t]*;?[ \t]*$",
    re.MULTILINE,
)

# Rule A construction sites. `OkHttpClient.Builder(` covers the builder form
# (optionally `okhttp3.`-qualified); the bare form is `OkHttpClient()` with no
# `.Builder` after it.
CONSTRUCTION_SITE = re.compile(
    r"(?:(?<=okhttp3)\.)?OkHttpClient\.Builder[ \t]*\("
    r"|(?<![\w.])OkHttpClient[ \t]*\([ \t]*\)"
)

# ---------------------------------------------------------------------------
# EXEMPTIONS — the single data table of deliberately un-enforced sites.
#
# Key is either
#   "app/src/main/kotlin/....kt"                  -> the whole file is exempt, or
#   "app/src/main/kotlin/....kt#<needle>"         -> only construction sites
#                                                    whose enclosing statement
#                                                    contains <needle>.
# Anything under app/src/main/kotlin that is not listed here and does not use
# the enforcement vocabulary fails the gate.
#
# Derived from the design's complete transport inventory (design D1, section
# "1.3 Complete transport inventory"; numbering T1-T26 below). These are the
# transports deliberately outside the enforcement vocabulary:
EXEMPTIONS = {
    # The vocabulary itself: this file defines `HermesClients.build(...)` and
    # `OkHttpClient.Builder.enforceTailnetPolicy(...)`, and its only construction
    # site is the default `builder` parameter of `build`.
    "app/src/main/kotlin/com/hermesandroid/relay/network/shared/HermesClients.kt#fun build(":
        "defines the enforcement vocabulary itself (HermesClients.build / enforceTailnetPolicy)",
    # T19: scans RFC1918 addresses by design; a LAN result saved while the policy
    # is on is inert (the route policy filters it out).
    "app/src/main/kotlin/com/hermesandroid/relay/network/shared/HermesLanDiscovery.kt":
        "T19 LAN discovery: RFC1918-only host scan, never a Hermes transport by design",
    # T20-T22: non-Hermes public hosts.
    "app/src/main/kotlin/com/hermesandroid/relay/update/UpdateChecker.kt":
        "T20 update check: api.github.com, a non-Hermes public host",
    "app/src/main/kotlin/com/hermesandroid/relay/wake/WakeWordModelInstaller.kt":
        "T21 wake-word model download: non-Hermes public host (modelscope)",
    "app/src/main/kotlin/com/hermesandroid/relay/petdex/PetdexHttp.kt":
        "T22 petdex: non-Hermes allowlisted hosts",
    # T23/T24: authority-split fetchers. The plain client serves non-Hermes image
    # hosts; the Hermes branch of each file builds a bound client.
    "app/src/main/kotlin/com/hermesandroid/relay/util/MediaSaver.kt#private val httpClient":
        "T23 authority split: plain client for non-Hermes image hosts; `hermesClient` is bound",
    "app/src/main/kotlin/com/hermesandroid/relay/HermesRelayApp.kt#private val plainClient":
        "T24 Coil authority split: HermesAwareCallFactory picks per request; `hermesClient` is bound",
    #
    # Inventory entries that need no exemption because they contain no OkHttp
    # construction site at all:
    #   T6  Hermes Reach (network/shared/HermesReachTransport.kt) reuses an
    #       already-built outer client and is never route-eligible while the
    #       policy is on (route policy rule 2).
    #   T14 PKCE loopback (network/upstream/NativeDashboardSignInCoordinator.kt)
    #       is an inbound 127.0.0.1 ServerSocket, not a client.
    #   T25/T26 WebView + Custom Tabs cannot take a per-socket factory; they are
    #       URL-gated with TailnetEnforcer.checkUrl at load/launch instead.
}

Violation = namedtuple("Violation", "path line rule message")


def _relative(path: Path, root: Path) -> str:
    try:
        return path.relative_to(root).as_posix()
    except ValueError:
        return path.as_posix()


def _iter_files(root: Path, base: Path):
    """Every regular file under `base`, skipping build/output directories."""
    directory = root / base
    if not directory.is_dir():
        return
    for path in sorted(directory.rglob("*")):
        if not path.is_file():
            continue
        if IGNORED_PARTS & set(path.parts):
            continue
        yield path


def _read(path: Path) -> str:
    return path.read_text(encoding="utf-8", errors="replace")


def _is_comment(line: str) -> bool:
    stripped = line.strip()
    return stripped.startswith("//") or stripped.startswith("*") or stripped.startswith("/*")


def _continues(line: str) -> bool:
    """True when `line` cannot end a statement — the next line keeps it open."""
    if _is_comment(line):
        return False
    stripped = line.strip()
    if not stripped:
        return False
    return stripped.endswith(("(", "[", "{", ",", "=", ".", ":", "->", "?."))


def _net_depth(text: str) -> int:
    return (text.count("(") - text.count(")")) + (text.count("{") - text.count("}"))


def _statement_span(lines: list[str], index: int) -> tuple[int, int]:
    """Bracketed span of the statement/chain holding `lines[index]`.

    Walks back over continuation lines (so a `HermesClients.build(` wrapper one
    or two lines above the site is inside the span) and forward to the end of
    the chain (paren/brace balance plus leading-dot continuations).
    """
    start = index
    while start > 0 and index - start < 4 and _continues(lines[start - 1]):
        start -= 1

    depth = 0
    end = index
    while end < len(lines):
        text = lines[end]
        depth += _net_depth(text)
        nxt = lines[end + 1].strip() if end + 1 < len(lines) else ""
        chained = nxt.startswith(".") or nxt.startswith("?.")
        if depth <= 0 and not chained:
            break
        if end - start > 40:
            break
        end += 1
    return start, end


_ASSIGNED_NAME = re.compile(r"\b(?:val|var)\s+([A-Za-z_]\w*)")


def _exemption_for(relative: str, span_text: str) -> str | None:
    for key, justification in EXEMPTIONS.items():
        path, _, anchor = key.partition("#")
        if path != relative:
            continue
        if not anchor or anchor in span_text:
            return justification
    return None


def check_transports(root: Path) -> list[Violation]:
    """Rule A: every OkHttp construction site is decorated or exempt."""
    violations: list[Violation] = []
    for path in _iter_files(root, KOTLIN_SOURCES):
        if path.suffix != ".kt":
            continue
        lines = _read(path).splitlines()
        relative = _relative(path, root)
        for index, line in enumerate(lines):
            if _is_comment(line):
                continue
            match = CONSTRUCTION_SITE.search(line)
            if match is None:
                continue
            start, end = _statement_span(lines, index)
            span_text = "\n".join(lines[start : end + 1])
            decorated = any(token in span_text for token in ENFORCEMENT_VOCABULARY)
            if not decorated:
                # `val builder = OkHttpClient.Builder()` then later
                # `HermesClients.build(builder)` / `builder.enforceTailnetPolicy()`.
                for name in _ASSIGNED_NAME.findall(span_text):
                    if f"HermesClients.build({name}" in _file_text_cache(path) or \
                            f"{name}.enforceTailnetPolicy(" in _file_text_cache(path):
                        decorated = True
                        break
            if decorated:
                continue
            if _exemption_for(relative, span_text) is not None:
                continue
            violations.append(
                Violation(
                    path=relative,
                    line=index + 1,
                    rule="A",
                    message=(
                        f"OkHttp client construction is not decorated with "
                        f"{' or '.join(ENFORCEMENT_VOCABULARY)} and is not in the "
                        f"EXEMPTIONS table"
                    ),
                )
            )
    return violations


_FILE_TEXT: dict[Path, str] = {}


def _file_text_cache(path: Path) -> str:
    text = _FILE_TEXT.get(path)
    if text is None:
        text = _read(path)
        _FILE_TEXT[path] = text
    return text


def check_forbidden_apis(root: Path) -> list[Violation]:
    """Rule B: no device-wide / process-wide routing API anywhere in app/src/main."""
    violations: list[Violation] = []
    for path in _iter_files(root, APP_MAIN):
        text = _read(path)
        for token in FORBIDDEN_APIS:
            for line_number, line in enumerate(text.splitlines(), start=1):
                if token in line:
                    violations.append(
                        Violation(
                            path=_relative(path, root),
                            line=line_number,
                            rule="B",
                            message=(
                                f"forbidden OS-level routing API '{token}' — the tailnet "
                                f"policy binds one socket per connection, never the app or "
                                f"the device"
                            ),
                        )
                    )
    return violations


def check_frozen_package(root: Path) -> list[Violation]:
    """Rule C: the retired net.tailnet package must not be declared again."""
    violations: list[Violation] = []
    for path in _iter_files(root, APP_MAIN):
        text = _read(path)
        for match in FROZEN_PACKAGE_DECL.finditer(text):
            line_number = text.count("\n", 0, match.start()) + 1
            violations.append(
                Violation(
                    path=_relative(path, root),
                    line=line_number,
                    rule="C",
                    message=(
                        f"declares frozen package '{match.group(1)}' — the tailnet "
                        f"enforcement classes live in com.hermesandroid.relay.network.shared"
                    ),
                )
            )
    return violations


RULE_FUNCTIONS = (check_transports, check_forbidden_apis, check_frozen_package)


def scan(root: Path) -> tuple[list[Violation], dict[str, int]]:
    violations: list[Violation] = []
    for rule in RULE_FUNCTIONS:
        violations.extend(rule(root))
    violations.sort(key=lambda v: (v.path, v.line, v.rule))

    kotlin_files = [p for p in _iter_files(root, KOTLIN_SOURCES) if p.suffix == ".kt"]
    sites = 0
    exempt_sites = 0
    for path in kotlin_files:
        lines = _read(path).splitlines()
        relative = _relative(path, root)
        for index, line in enumerate(lines):
            if _is_comment(line):
                continue
            found = CONSTRUCTION_SITE.findall(line)
            if not found:
                continue
            sites += len(found)
            start, end = _statement_span(lines, index)
            if _exemption_for(relative, "\n".join(lines[start : end + 1])) is not None:
                exempt_sites += len(found)
    stats = {
        "scanned_kotlin_files": len(kotlin_files),
        "construction_sites": sites,
        "exempt_sites": exempt_sites,
        "exemption_entries": len(EXEMPTIONS),
    }
    return violations, stats


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="Fail when a Hermes-host network transport bypasses the tailnet enforcer."
    )
    parser.add_argument(
        "--repo-root",
        type=Path,
        default=ROOT,
        help="Repository root to scan (default: the repository containing this script).",
    )
    parser.add_argument("--json", action="store_true", help="Emit machine-readable JSON.")
    parser.add_argument("--quiet", action="store_true", help="Suppress the success summary.")
    args = parser.parse_args(argv)

    root = args.repo_root.resolve()
    if not (root / APP_MAIN).is_dir():
        print(f"{root} does not look like the Android repository root "
              f"(missing {APP_MAIN})", file=sys.stderr)
        return 2

    try:
        violations, stats = scan(root)
    except OSError as error:
        print(f"Android Hermes transport check failed to read sources: {error}",
              file=sys.stderr)
        return 2

    if args.json:
        print(json.dumps(
            {
                "ok": not violations,
                "repo_root": str(root),
                "violation_count": len(violations),
                "violations": [
                    {"path": v.path, "line": v.line, "rule": v.rule, "message": v.message}
                    for v in violations
                ],
                **stats,
            },
            indent=2,
        ))
        return 1 if violations else 0

    if violations:
        print("Android Hermes transport enforcement check failed "
              f"({len(violations)} violation(s)):", file=sys.stderr)
        for violation in violations:
            print(f"  x {violation.path}:{violation.line}: "
                  f"[rule {violation.rule}] {violation.message}", file=sys.stderr)
        print("Route every Hermes-host client through "
              "HermesClients.build(...) / OkHttpClient.Builder.enforceTailnetPolicy(...) "
              "or add a reviewed EXEMPTIONS entry in scripts/check-android-hermes-transports.py.",
              file=sys.stderr)
        return 1

    if not args.quiet:
        print(
            "Android Hermes transport enforcement check passed "
            f"({stats['construction_sites']} OkHttp construction site(s), "
            f"{stats['exempt_sites']} exempt via {stats['exemption_entries']} table "
            f"entries, {stats['scanned_kotlin_files']} Kotlin file(s) scanned)"
        )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

#!/usr/bin/env python3
"""Assert the Tailscale mode is reachable from the app's Settings surface.

The routing half of "Always connect via Tailscale" is guarded by
``check-android-hermes-transports.py`` (every Hermes OkHttp client is bound) and by
the feature's JVM unit tests (address predicates, route eligibility, enforcer
state, client binding). Neither of those notices if the USER-FACING half is
unwired: the Enforcement could be perfect and the mode unreachable because the
Settings row, the subpage destination, or the navigation edge was dropped.

This gate pins that wiring statically, in the same JDK-free style as the other
``check-android-*.py`` gates. It asserts:

  S1  the Settings row exists and sits directly under the Gateways row
  S2  the Settings row routes through the Tailscale settings callback
  S3  the nav graph registers the Tailscale settings destination
  S4  the settings call site navigates to that destination
  S5  the route constant, the subpage composable and its card are present, and
      the Routes tab carries the read-only status row

Exit codes: 0 aligned, 1 violations, 2 usage/read error.
"""

from __future__ import annotations

import argparse
import json
import pathlib
import sys

REPO_ROOT = pathlib.Path(__file__).resolve().parents[1]

SETTINGS_ROW = pathlib.Path("app/src/main/kotlin/com/hermesandroid/relay/ui/screens/SettingsScreen.kt")
RELAY_APP = pathlib.Path("app/src/main/kotlin/com/hermesandroid/relay/ui/RelayApp.kt")
CARD = pathlib.Path("app/src/main/kotlin/com/hermesandroid/relay/ui/components/TailscaleAlwaysConnectCard.kt")
SUBPAGE = pathlib.Path("app/src/main/kotlin/com/hermesandroid/relay/ui/screens/TailscaleSettingsScreen.kt")
ROUTES_SECTION = pathlib.Path("app/src/main/kotlin/com/hermesandroid/relay/ui/components/ActiveConnectionSections.kt")

GATEWAYS_ANCHOR = "R.string.settings_connections"
ROW_TITLE = "R.string.settings_tailscale_always_connect_title"
CALLBACK = "onNavigateToTailscaleSettings"
ROUTE_CONST = "TAILSCALE_SETTINGS_ROUTE"
ROUTE_VALUE = "settings/tailscale"
NAV_OBJECT = "Screen.TailscaleSettings"
STATUS_ROW = "TailscaleAlwaysConnectStatusRow("

# The Settings row must sit at most this many lines below the Gateways row, so a
# future reorder cannot quietly bury it in another section.
MAX_ROWS_BELOW_GATEWAYS = 15


def _read(repo_root: pathlib.Path, relative: pathlib.Path) -> str:
    return (repo_root / relative).read_text(encoding="utf-8")


def _line_of(text: str, needle: str) -> int | None:
    for index, line in enumerate(text.splitlines(), start=1):
        if needle in line:
            return index
    return None


def collect_violations(repo_root: pathlib.Path) -> list[str]:
    violations: list[str] = []
    try:
        row_text = _read(repo_root, SETTINGS_ROW)
        app_text = _read(repo_root, RELAY_APP)
        card_text = _read(repo_root, CARD)
        subpage_text = _read(repo_root, SUBPAGE)
        routes_text = _read(repo_root, ROUTES_SECTION)
    except OSError as exc:
        return [f"cannot read a required source file: {exc}"]

    # S1 - the row exists, and directly under Gateways.
    gateways_line = _line_of(row_text, GATEWAYS_ANCHOR)
    title_line = _line_of(row_text, ROW_TITLE)
    if title_line is None:
        violations.append(
            f"{SETTINGS_ROW}: the Settings surface has no row titled {ROW_TITLE}; "
            "the mode would be unreachable from Settings"
        )
    elif gateways_line is None:
        violations.append(f"{SETTINGS_ROW}: cannot locate the Gateways row ({GATEWAYS_ANCHOR}) to order against")
    elif not 0 < title_line - gateways_line <= MAX_ROWS_BELOW_GATEWAYS:
        violations.append(
            f"{SETTINGS_ROW}:{title_line}: the Tailscale row is {title_line - gateways_line} lines from the "
            f"Gateways row ({gateways_line}); expected it directly beneath it (1-{MAX_ROWS_BELOW_GATEWAYS})"
        )

    # S2 - the row routes through the Tailscale settings callback.
    if CALLBACK not in row_text:
        violations.append(f"{SETTINGS_ROW}: no {CALLBACK} callback; the row cannot open the subpage")
    else:
        click_line = _line_of(row_text, f"onClick = {CALLBACK}")
        if click_line is None:
            violations.append(
                f"{SETTINGS_ROW}: {CALLBACK} exists but no row wires it as its onClick"
            )

    # S3 - the destination is registered.
    if f"composable({NAV_OBJECT}.route)" not in app_text:
        violations.append(f"{RELAY_APP}: the nav graph does not register {NAV_OBJECT}.route")
    if f"data object TailscaleSettings" not in app_text:
        violations.append(f"{RELAY_APP}: no {NAV_OBJECT} destination object")

    # S4 - the settings call site navigates to it.
    if f"navigate({NAV_OBJECT}.route)" not in app_text:
        violations.append(f"{RELAY_APP}: nothing navigates to {NAV_OBJECT}.route")

    # S5 - route constant, subpage, card and the read-only status row.
    if ROUTE_CONST not in card_text or ROUTE_VALUE not in card_text:
        violations.append(f"{CARD}: {ROUTE_CONST} = \"{ROUTE_VALUE}\" is missing")
    if f"const val {ROUTE_CONST}" not in card_text:
        violations.append(f"{CARD}: {ROUTE_CONST} is not declared as a const")
    if "fun TailscaleSettingsScreen(" not in subpage_text:
        violations.append(f"{SUBPAGE}: no TailscaleSettingsScreen composable")
    if "TailscaleAlwaysConnectCard(" not in subpage_text:
        violations.append(f"{SUBPAGE}: the subpage does not host the TailscaleAlwaysConnectCard")
    if f"fun {STATUS_ROW}" not in card_text:
        violations.append(f"{CARD}: no {STATUS_ROW.rstrip('(')} composable")
    if STATUS_ROW not in routes_text:
        violations.append(f"{ROUTES_SECTION}: the Routes section does not show the Tailscale status row")

    return violations


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo-root", type=pathlib.Path, default=REPO_ROOT)
    parser.add_argument("--json", action="store_true")
    args = parser.parse_args()

    violations = collect_violations(args.repo_root)
    if args.json:
        print(json.dumps({"violations": violations}, indent=2))
    elif violations:
        print(
            f"Android Tailscale settings-wiring check failed ({len(violations)} violation(s)):",
            file=sys.stderr,
        )
        for violation in violations:
            print(f"  x {violation}", file=sys.stderr)
    else:
        print("Android Tailscale settings-wiring check passed (Settings row, subpage, nav and status row wired)")
    return 1 if violations else 0


if __name__ == "__main__":
    raise SystemExit(main())

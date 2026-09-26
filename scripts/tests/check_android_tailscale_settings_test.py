#!/usr/bin/env python3
"""Tests for check-android-tailscale-settings.py.

Fixture trees only - nothing here reads or writes the real app sources. The
suite proves the gate is not vacuous: a correctly wired tree passes, and each
individual rule goes red when its wiring is removed.
"""

from __future__ import annotations

import importlib.util
import pathlib
import tempfile
import unittest

GATE = pathlib.Path(__file__).resolve().parents[1] / "check-android-tailscale-settings.py"


def _load_gate():
    spec = importlib.util.spec_from_file_location("check_android_tailscale_settings", GATE)
    if spec is None or spec.loader is None:
        raise RuntimeError(f"cannot load {GATE}")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


GATE_MODULE = _load_gate()

FILES = {
    GATE_MODULE.SETTINGS_ROW: (
        "package x\n"
        "SettingsCategoryRow(title = stringResource(R.string.settings_connections))\n"
        "SettingsCategoryRow(title = stringResource(R.string.settings_tailscale_always_connect_title),\n"
        "    onClick = onNavigateToTailscaleSettings)\n"
        "onNavigateToTailscaleSettings: () -> Unit = {},\n"
    ),
    GATE_MODULE.RELAY_APP: (
        "package x\n"
        "data object TailscaleSettings : Screen(TAILSCALE_SETTINGS_ROUTE, \"Tailscale\")\n"
        "composable(Screen.TailscaleSettings.route) { }\n"
        "navController.navigate(Screen.TailscaleSettings.route)\n"
    ),
    GATE_MODULE.CARD: (
        "package x\n"
        "const val TAILSCALE_SETTINGS_ROUTE = \"settings/tailscale\"\n"
        "fun TailscaleAlwaysConnectStatusRow(uiState: X, onClick: () -> Unit) { }\n"
    ),
    GATE_MODULE.SUBPAGE: (
        "package x\n"
        "fun TailscaleSettingsScreen(vm: X, onBack: () -> Unit) {\n"
        "    TailscaleAlwaysConnectCard(state, onToggle)\n"
        "}\n"
    ),
    GATE_MODULE.ROUTES_SECTION: "package x\nTailscaleAlwaysConnectStatusRow(uiState = state, onClick = { })\n",
}


class TailscaleSettingsGateTest(unittest.TestCase):
    def _tree(self, **overrides: str) -> pathlib.Path:
        directory = pathlib.Path(tempfile.mkdtemp(prefix="tailscale-settings-"))
        self.addCleanup(lambda: __import__("shutil").rmtree(directory, ignore_errors=True))
        for relative, body in FILES.items():
            content = overrides.get(relative.name, body)
            target = directory / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text(content, encoding="utf-8")
        for name, body in overrides.items():
            if any(path.name == name for path in FILES):
                continue
            target = directory / "app/src/main/kotlin/extra" / name
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text(body, encoding="utf-8")
        return directory

    def test_fully_wired_tree_passes(self):
        self.assertEqual([], GATE_MODULE.collect_violations(self._tree()))

    def test_missing_settings_row_is_flagged(self):
        tree = self._tree(**{GATE_MODULE.SETTINGS_ROW.name: "package x\n// no Tailscale row\n"})
        violations = GATE_MODULE.collect_violations(tree)
        self.assertTrue(any("would be unreachable from Settings" in v for v in violations), violations)

    def test_row_moved_far_from_gateways_is_flagged(self):
        body = (
            "package x\n"
            "SettingsCategoryRow(title = stringResource(R.string.settings_connections))\n"
            + "\n" * 20
            + "SettingsCategoryRow(title = stringResource(R.string.settings_tailscale_always_connect_title),\n"
            "    onClick = onNavigateToTailscaleSettings)\n"
        )
        violations = GATE_MODULE.collect_violations(self._tree(**{GATE_MODULE.SETTINGS_ROW.name: body}))
        self.assertTrue(any("expected it directly beneath it" in v for v in violations), violations)

    def test_row_without_callback_is_flagged(self):
        body = (
            "package x\n"
            "SettingsCategoryRow(title = stringResource(R.string.settings_connections))\n"
            "SettingsCategoryRow(title = stringResource(R.string.settings_tailscale_always_connect_title),\n"
            "    onClick = { })\n"
        )
        violations = GATE_MODULE.collect_violations(self._tree(**{GATE_MODULE.SETTINGS_ROW.name: body}))
        self.assertTrue(any("no onNavigateToTailscaleSettings callback" in v for v in violations), violations)

    def test_destination_not_registered_is_flagged(self):
        body = (
            "package x\n"
            "data object TailscaleSettings : Screen(TAILSCALE_SETTINGS_ROUTE, \"Tailscale\")\n"
            "navController.navigate(Screen.TailscaleSettings.route)\n"
        )
        violations = GATE_MODULE.collect_violations(self._tree(**{GATE_MODULE.RELAY_APP.name: body}))
        self.assertTrue(any("does not register" in v for v in violations), violations)

    def test_missing_route_constant_is_flagged(self):
        body = "package x\nfun TailscaleAlwaysConnectStatusRow(uiState: X, onClick: () -> Unit) { }\n"
        violations = GATE_MODULE.collect_violations(self._tree(**{GATE_MODULE.CARD.name: body}))
        self.assertTrue(any("is missing" in v for v in violations), violations)

    def test_status_row_missing_from_routes_is_flagged(self):
        violations = GATE_MODULE.collect_violations(
            self._tree(**{GATE_MODULE.ROUTES_SECTION.name: "package x\n// nothing here\n"})
        )
        self.assertTrue(any("Routes section does not show" in v for v in violations), violations)

    def test_subpage_hosting_no_card_is_flagged(self):
        body = "package x\nfun TailscaleSettingsScreen(vm: X, onBack: () -> Unit) { }\n"
        violations = GATE_MODULE.collect_violations(self._tree(**{GATE_MODULE.SUBPAGE.name: body}))
        self.assertTrue(any("does not host" in v for v in violations), violations)

    def test_unreadable_tree_is_reported_not_crashed(self):
        empty = pathlib.Path(tempfile.mkdtemp(prefix="tailscale-settings-empty-"))
        self.addCleanup(lambda: __import__("shutil").rmtree(empty, ignore_errors=True))
        violations = GATE_MODULE.collect_violations(empty)
        self.assertTrue(violations and violations[0].startswith("cannot read"), violations)

    def test_rules_are_load_bearing(self):
        """Removing the row from an otherwise perfect tree must fail the gate."""
        tree = self._tree(**{GATE_MODULE.SETTINGS_ROW.name: "package x\nSettingsCategoryRow()\n"})
        self.assertTrue(GATE_MODULE.collect_violations(tree))


if __name__ == "__main__":
    unittest.main()

import contextlib
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import textwrap
import unittest

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location(
    "hermes_transports", ROOT / "scripts/check-android-hermes-transports.py"
)
checker = importlib.util.module_from_spec(spec)
spec.loader.exec_module(checker)

PKG = "app/src/main/kotlin/com/hermesandroid/relay"

# A fixture tree where every construction site is decorated with the enforcement
# vocabulary or is an explicit exemption-table entry. The paths mirror the real
# repository so the checker's `EXEMPTIONS` table applies to them.
PASSING_TREE = {
    # Exempt through the site anchor `fun build(`: this file *defines* the
    # vocabulary, so its own default `builder` parameter is not decorated.
    f"{PKG}/network/shared/HermesClients.kt": """
        package com.hermesandroid.relay.network.shared

        object HermesClients {
            fun build(
                builder: OkHttpClient.Builder = OkHttpClient.Builder(),
                enforcer: TailnetEnforcer = TailnetEnforcer.get(),
            ): OkHttpClient = enforcer.register(builder.enforceTailnetPolicy(enforcer).build())
        }
    """,
    # Decorated directly, inside the builder chain.
    f"{PKG}/network/upstream/DashboardApiClient.kt": """
        package com.hermesandroid.relay.network.upstream

        object DashboardApiClient {
            fun defaultClient(): OkHttpClient =
                HermesClients.build(
                    OkHttpClient.Builder().cookieJar(cookieJar).connectTimeout(10, TimeUnit.SECONDS),
                )
        }
    """,
    # Assigned to a local variable and wrapped a few lines later.
    f"{PKG}/network/relay/ConnectionManager.kt": """
        package com.hermesandroid.relay.network.relay

        class ConnectionManager {
            private fun buildClient(): OkHttpClient {
                val builder = OkHttpClient.Builder()
                    .connectTimeout(20, TimeUnit.SECONDS)
                    .pingInterval(30, TimeUnit.SECONDS)
                return HermesClients.build(builder)
            }
        }
    """,
    # Whole-file exemption: LAN discovery scans RFC1918 hosts by design.
    f"{PKG}/network/shared/HermesLanDiscovery.kt": """
        package com.hermesandroid.relay.network.shared

        object HermesLanDiscovery {
            fun client(): OkHttpClient =
                OkHttpClient.Builder().connectTimeout(300, TimeUnit.MILLISECONDS).build()
        }
    """,
    # Anchored exemption: only the plain (non-Hermes) client is exempt.
    f"{PKG}/util/MediaSaver.kt": """
        package com.hermesandroid.relay.util

        object MediaSaver {
            private val httpClient: OkHttpClient by lazy {
                OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).build()
            }

            private val hermesClient: OkHttpClient by lazy {
                HermesClients.build(OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS))
            }
        }
    """,
}

PASSING_SITES = 6
PASSING_EXEMPT = 3

# The negative-control tree: one undecorated client, three forbidden APIs and
# the retired package. Every rule has something to report.
FAILING_TREE = {
    f"{PKG}/network/upstream/LateClientFactory.kt": """
        package com.hermesandroid.relay.network.upstream

        object LateClientFactory {
            fun client(): OkHttpClient =
                OkHttpClient.Builder()
                    .connectTimeout(5, TimeUnit.SECONDS)
                    .build()
        }
    """,
    f"{PKG}/network/relay/ProcessBinder.kt": """
        package com.hermesandroid.relay.network.relay

        class ProcessBinder {
            fun bind(connectivity: ConnectivityManager) {
                connectivity.bindProcessToNetwork(network)
            }
        }
    """,
    # Rule B scans every file type under app/src/main, not only Kotlin.
    "app/src/main/java/com/hermesandroid/relay/legacy/VpnBridge.java": """
        package com.hermesandroid.relay.legacy;

        class VpnBridge extends android.net.VpnService {
            void underlay(Network[] networks) { setUnderlyingNetworks(networks); }
        }
    """,
    f"{PKG}/net/tailnet/TailnetAddresses.kt": """
        package com.hermesandroid.relay.net.tailnet

        object TailnetAddresses {
            fun isTailnetHost(host: String?): Boolean = false
        }
    """,
}

RULE_NAMES = ("check_transports", "check_forbidden_apis", "check_frozen_package")
RULE_TAG = {"check_transports": "A", "check_forbidden_apis": "B", "check_frozen_package": "C"}


def _write(root: Path, relative: str, text: str) -> None:
    path = root / relative
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(textwrap.dedent(text).lstrip("\n"), encoding="utf-8")


def run_checker(root: Path, *extra: str) -> tuple[int, str, str]:
    out, err = io.StringIO(), io.StringIO()
    with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
        code = checker.main(["--repo-root", str(root), *extra])
    return code, out.getvalue(), err.getvalue()


def report_lines(err: str) -> list[str]:
    return [line.strip() for line in err.splitlines() if line.strip().startswith("x ")]


class FixtureMixin:
    def fixture(self, tree: dict) -> Path:
        temp = tempfile.TemporaryDirectory(prefix="hermes-transport-gate-")
        self.addCleanup(temp.cleanup)
        root = Path(temp.name)
        for relative, text in tree.items():
            _write(root, relative, text)
        return root


class PassingTreeTest(FixtureMixin, unittest.TestCase):
    def test_decorated_and_exempt_tree_passes(self):
        root = self.fixture(PASSING_TREE)
        code, out, err = run_checker(root)
        self.assertEqual(code, 0, err)
        self.assertEqual(err, "")
        self.assertIn("check passed", out)
        self.assertIn(f"{PASSING_SITES} OkHttp construction site(s)", out)
        self.assertIn(f"{PASSING_EXEMPT} exempt", out)

    def test_json_report_is_ok_and_counts_every_site(self):
        root = self.fixture(PASSING_TREE)
        code, out, _ = run_checker(root, "--json")
        self.assertEqual(code, 0)
        report = json.loads(out)
        self.assertTrue(report["ok"])
        self.assertEqual(report["violations"], [])
        self.assertEqual(report["construction_sites"], PASSING_SITES)
        self.assertEqual(report["exempt_sites"], PASSING_EXEMPT)

    def test_quiet_suppresses_the_success_summary(self):
        root = self.fixture(PASSING_TREE)
        code, out, err = run_checker(root, "--quiet")
        self.assertEqual(code, 0)
        self.assertEqual(out, "")
        self.assertEqual(err, "")

    def test_second_client_in_an_anchored_exempt_file_is_still_reported(self):
        """A `path#anchor` exemption covers one site, not the whole file."""
        root = self.fixture(PASSING_TREE)
        _write(root, f"{PKG}/util/MediaSaver.kt", """
            package com.hermesandroid.relay.util

            object MediaSaver {
                private val httpClient: OkHttpClient by lazy {
                    OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).build()
                }

                private val sneakyClient: OkHttpClient = OkHttpClient.Builder().build()
            }
        """)
        code, _, err = run_checker(root)
        self.assertEqual(code, 1)
        self.assertEqual(len(report_lines(err)), 1)
        self.assertIn("util/MediaSaver.kt:8", err)


class FailingTreeTest(FixtureMixin, unittest.TestCase):
    """Negative control: every rule must report the failing fixture."""

    def test_failing_tree_reports_every_rule(self):
        root = self.fixture(FAILING_TREE)
        code, _, err = run_checker(root)
        self.assertEqual(code, 1)
        for tag in RULE_TAG.values():
            self.assertIn(f"[rule {tag}]", err)

    def test_rule_a_names_the_undecorated_client_file_and_line(self):
        root = self.fixture(FAILING_TREE)
        _, _, err = run_checker(root)
        self.assertIn("x app/src/main/kotlin/com/hermesandroid/relay/network/upstream/"
                      "LateClientFactory.kt:5: [rule A]", err)
        self.assertIn("OkHttp client construction is not decorated with HermesClients.build("
                      " or .enforceTailnetPolicy(", err)

    def test_rule_b_names_every_forbidden_api_file_and_line(self):
        root = self.fixture(FAILING_TREE)
        _, _, err = run_checker(root)
        self.assertIn("x app/src/main/kotlin/com/hermesandroid/relay/network/relay/"
                      "ProcessBinder.kt:5: [rule B]", err)
        self.assertIn("bindProcessToNetwork", err)
        self.assertIn("x app/src/main/java/com/hermesandroid/relay/legacy/"
                      "VpnBridge.java:3: [rule B]", err)
        self.assertIn("VpnService", err)
        self.assertIn("setUnderlyingNetworks", err)

    def test_rule_c_names_the_frozen_package_file_and_line(self):
        root = self.fixture(FAILING_TREE)
        _, _, err = run_checker(root)
        self.assertIn("x app/src/main/kotlin/com/hermesandroid/relay/net/tailnet/"
                      "TailnetAddresses.kt:1: [rule C]", err)
        self.assertIn("com.hermesandroid.relay.net.tailnet", err)

    def test_json_report_lists_the_violations(self):
        root = self.fixture(FAILING_TREE)
        code, out, _ = run_checker(root, "--json")
        self.assertEqual(code, 1)
        report = json.loads(out)
        self.assertFalse(report["ok"])
        self.assertEqual(report["violation_count"], len(report["violations"]))
        self.assertEqual({v["rule"] for v in report["violations"]}, set(RULE_TAG.values()))

    def test_quiet_never_hides_violations(self):
        root = self.fixture(FAILING_TREE)
        code, _, err = run_checker(root, "--quiet")
        self.assertEqual(code, 1)
        self.assertTrue(report_lines(err))


class RuleRemovalNegativeControlTest(FixtureMixin, unittest.TestCase):
    """Each rule must be load-bearing: remove it and its violations disappear.

    A checker whose rules are vacuous would still "pass" the healthy tree, so
    the removal control is what proves the failing fixture fails *because of*
    each rule rather than by accident.
    """

    def setUp(self):
        self.root = self.fixture(FAILING_TREE)
        self.original_rules = checker.RULE_FUNCTIONS
        self.addCleanup(setattr, checker, "RULE_FUNCTIONS", self.original_rules)

    def rule(self, name: str):
        return next(r for r in self.original_rules if r.__name__ == name)

    def test_each_rule_detects_its_own_fixture_in_isolation(self):
        expectations = {
            "check_transports": "LateClientFactory.kt:5",
            "check_forbidden_apis": "bindProcessToNetwork",
            "check_frozen_package": "com.hermesandroid.relay.net.tailnet",
        }
        for name, expected in expectations.items():
            with self.subTest(rule=name):
                messages = [f"{v.path}:{v.line}: {v.message}" for v in self.rule(name)(self.root)]
                self.assertTrue(messages, f"{name} detected nothing")
                self.assertTrue(any(expected in m for m in messages), messages)

    def test_removing_one_rule_removes_only_that_rules_violations(self):
        for name in RULE_NAMES:
            with self.subTest(removed=name):
                checker.RULE_FUNCTIONS = tuple(
                    r for r in self.original_rules if r.__name__ != name
                )
                violations, _ = checker.scan(self.root)
                tags = {v.rule for v in violations}
                self.assertNotIn(RULE_TAG[name], tags,
                                 f"{name} was removed but its violations are still reported")
                self.assertTrue(tags, "removing one rule dropped every other violation")
                self.assertTrue(tags <= set(RULE_TAG.values()) - {RULE_TAG[name]}, tags)

    def test_removing_every_rule_makes_the_failing_fixture_pass(self):
        checker.RULE_FUNCTIONS = ()
        code, out, err = run_checker(self.root)
        self.assertEqual(code, 0, err)
        self.assertIn("check passed", out)

    def test_emptying_the_vocabulary_breaks_the_healthy_tree(self):
        """Proves the vocabulary itself — not the fixture — makes sites pass."""
        root = self.fixture(PASSING_TREE)
        original = checker.ENFORCEMENT_VOCABULARY
        self.addCleanup(setattr, checker, "ENFORCEMENT_VOCABULARY", original)
        checker.ENFORCEMENT_VOCABULARY = ("never.matches(",)
        violations = checker.check_transports(root)
        self.assertTrue(violations, "the vocabulary is not what gates rule A")
        self.assertEqual({v.rule for v in violations}, {"A"})

    def test_empty_exemption_table_fails_the_exempt_files(self):
        """Proves the exemption table is load-bearing, not decorative."""
        root = self.fixture(PASSING_TREE)
        original = checker.EXEMPTIONS
        self.addCleanup(setattr, checker, "EXEMPTIONS", original)
        checker.EXEMPTIONS = {}
        paths = {v.path for v in checker.check_transports(root)}
        self.assertIn(f"{PKG}/network/shared/HermesLanDiscovery.kt", paths)
        self.assertIn(f"{PKG}/network/shared/HermesClients.kt", paths)
        self.assertIn(f"{PKG}/util/MediaSaver.kt", paths)
        self.assertNotIn(f"{PKG}/network/upstream/DashboardApiClient.kt", paths)

    def test_exemptions_only_ever_list_real_repository_paths(self):
        for key in checker.EXEMPTIONS:
            relative = key.partition("#")[0]
            with self.subTest(key=key):
                self.assertTrue(relative.startswith("app/src/main/kotlin/"), relative)
                self.assertEqual(len(checker.EXEMPTIONS[key].splitlines()), 1, key)


class UsageTest(unittest.TestCase):
    def test_missing_repo_root_exits_two(self):
        with tempfile.TemporaryDirectory(prefix="hermes-transport-gate-") as name:
            code, _, err = run_checker(Path(name))
        self.assertEqual(code, 2)
        self.assertIn("does not look like the Android repository root", err)

    def test_default_repo_root_is_the_repository_containing_the_script(self):
        self.assertEqual(checker.ROOT, ROOT)
        self.assertTrue(
            (checker.ROOT / "scripts" / "check-android-hermes-transports.py").is_file()
        )

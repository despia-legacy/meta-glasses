#!/usr/bin/env python3
"""iOS and Android parity checks: the display view tree rules on both platforms, and every route,
parameter and event of manifest.json handled by both entry points.

The Java check needs a JDK; the Swift check needs swiftc on macOS. Derived files use TMPDIR.
"""
import json
import os
from pathlib import Path
import platform
import re
import shutil
import subprocess
import tempfile
import unittest

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent
SUPPORT = HERE / "metaglasses" / "display"
JAVA = ROOT / "android/glasses/java/despia/ext/metaglasses"
IOS = ROOT / "ios/app"
VECTORS = SUPPORT / "display-vectors.json"


def run(command, timeout=120):
    return subprocess.run([str(p) for p in command], text=True, capture_output=True, timeout=timeout)


def expected():
    return {v["name"]: v["ok"] for v in json.loads(VECTORS.read_text())}


def parse_lines(out):
    rows = {}
    for line in out.splitlines():
        name, result = line.split("\t", 1)
        rows[name] = result
    return rows


_java_rows = None


def java_rows():
    global _java_rows
    if _java_rows is None:
        work = Path(tempfile.mkdtemp(prefix="glasses-tree-java-"))
        built = run(["javac", "-d", work, JAVA / "GlassesCore.java", SUPPORT / "DisplayTreeVectors.java"])
        if built.returncode:
            raise AssertionError("javac failed:\n" + built.stdout + built.stderr)
        ran = run(["java", "-cp", work, "despia.ext.metaglasses.DisplayTreeVectors", VECTORS])
        if ran.returncode:
            raise AssertionError("vectors failed:\n" + ran.stdout + ran.stderr)
        _java_rows = parse_lines(ran.stdout)
    return _java_rows


@unittest.skipUnless(shutil.which("javac") and shutil.which("java"), "a JDK is required")
class DisplayTreeJava(unittest.TestCase):
    def test_vectors(self):
        rows = java_rows()
        for name, ok in expected().items():
            with self.subTest(name=name):
                self.assertEqual(rows[name].startswith("ok"), ok, rows[name])


@unittest.skipUnless(platform.system() == "Darwin" and shutil.which("swiftc"), "swiftc on macOS is required")
class DisplayTreeSwift(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.work = Path(tempfile.mkdtemp(prefix="glasses-tree-swift-"))
        shutil.copy(SUPPORT / "display_main.swift", cls.work / "main.swift")
        cls.exe = cls.work / "vectors"
        built = run(["swiftc", "-module-cache-path", cls.work / "module-cache", IOS / "GlassesCore.swift",
                     cls.work / "main.swift", "-o", cls.exe], timeout=300)
        if built.returncode:
            raise AssertionError("swiftc failed:\n" + built.stdout + built.stderr)
        ran = run([cls.exe, VECTORS])
        if ran.returncode:
            raise AssertionError("vectors failed:\n" + ran.stdout + ran.stderr)
        cls.rows = parse_lines(ran.stdout)

    def test_vectors(self):
        for name, ok in expected().items():
            with self.subTest(name=name):
                self.assertEqual(self.rows[name].startswith("ok"), ok, self.rows[name])

    @unittest.skipUnless(shutil.which("javac") and shutil.which("java"), "a JDK is required")
    def test_same_trees_as_java(self):
        self.assertEqual(self.rows, java_rows())


# A parameter one platform cannot honour, with the named limit the README documents.
PLATFORM_LIMITS = {
    # DAT 1.1.0 Android RegistrationRequest.cancelRegistration() takes no returnToMetaAI choice.
    ("registration_cancel", "return_to_meta_ai"): "android",
}


class RouteParity(unittest.TestCase):
    """Every manifest route, parameter and event is handled on both platforms, and nothing else."""

    @classmethod
    def setUpClass(cls):
        cls.manifest = json.loads((ROOT / "manifest.json").read_text())
        cls.swift = "\n".join(p.read_text() for p in sorted(IOS.glob("*.swift")))
        cls.java = "\n".join(p.read_text() for p in sorted(JAVA.glob("*.java")))
        cls.routes = {r["name"]: r for r in cls.manifest["bridge"]["routes"]}
        cls.events = {e["name"] for e in cls.manifest["bridge"]["events"]}

    def test_routes_are_dispatched_on_both(self):
        swift_switch = self.swift.split("func route(_ call: NativeExtCall)", 1)[1].split("// MARK: - SDK and state", 1)[0]
        swift_routes = set(re.findall(r'"([a-z_]+)"', " ".join(re.findall(r"case ([^:]+):", swift_switch))))
        java_switch = self.java.split("public void handle(ExtCall call", 1)[1].split("private boolean ensureInit(", 1)[0]
        java_routes = set(re.findall(r'case "([a-z_]+)":', java_switch))
        self.assertEqual(set(self.routes), swift_routes)
        self.assertEqual(set(self.routes), java_routes)

    def test_params_are_read_on_both(self):
        for name, r in self.routes.items():
            for p in r.get("params", {}):
                with self.subTest(route=name, param=p):
                    limit = PLATFORM_LIMITS.get((name, p))
                    if limit != "ios":
                        self.assertIn(f'params["{p}"]', self.swift)
                    if limit != "android":
                        self.assertTrue(f'params.get("{p}")' in self.java or f'str(call, "{p}")' in self.java, p)

    def test_events_are_emitted_on_both(self):
        swift_events = set(re.findall(r'emit\("([a-z_]+)"', self.swift))
        java_events = set(re.findall(r'emit\("([a-z_]+)"', self.java))
        self.assertEqual(self.events, swift_events)
        self.assertEqual(self.events, java_events)


if __name__ == "__main__":
    unittest.main()

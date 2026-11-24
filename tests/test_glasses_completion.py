#!/usr/bin/env python3
"""Real macOS Network socket checks and explicitly fake Android codec failure checks.

DESPIA_NX_ANDROID_JAR overrides Android SDK discovery through ANDROID_HOME or ANDROID_SDK_ROOT.
All derived files use TMPDIR, which must point to the external task workspace.
"""
import os
from pathlib import Path
import platform
import shutil
import socket
import subprocess
import tempfile
import time
import unittest

HERE = Path(__file__).resolve().parent
NX = HERE.parent
PAYLOAD = NX / "fixtures/metaglasses/metaglasses"
if not PAYLOAD.exists():
    PAYLOAD = HERE.parent
SUPPORT = HERE / "metaglasses"
JAVA = PAYLOAD / "android/glasses/java/despia/ext/metaglasses"
IOS = PAYLOAD / "ios/app"
ANDROID_JAR = os.environ.get("DESPIA_NX_ANDROID_JAR", "")
if not ANDROID_JAR:
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if sdk:
        ANDROID_JAR = str(Path(sdk) / "platforms/android-36/android.jar")


def run(command, timeout=60):
    return subprocess.run([str(p) for p in command], text=True, capture_output=True, timeout=timeout)


class ReleaseMockGate(unittest.TestCase):
    def test_ios_release_gate_precedes_sdk_and_teardown(self):
        source = (IOS / "GlassesExtension.swift").read_text()
        handle = source.split("func handle(", 1)[1].split("func openURL(", 1)[0]
        self.assertIn("!GlassesController.mockAllowed", handle)
        self.assertLess(handle.index("!GlassesController.mockAllowed"), handle.index("controller()"))
        route = source.split("private func mockRoute", 1)[1]
        gate = route.index("guard Self.mockAllowed else")
        self.assertLess(gate, route.index("MockDeviceKit.shared"))
        self.assertLess(gate, route.index('teardown("mock_off")'))

    def test_android_release_gate_precedes_sdk_and_teardown(self):
        source = (JAVA / "GlassesExtension.java").read_text()
        handle = source.split("public void handle(", 1)[1].split("private boolean ensureInit(", 1)[0]
        self.assertIn("ApplicationInfo.FLAG_DEBUGGABLE", handle)
        self.assertLess(handle.index("ApplicationInfo.FLAG_DEBUGGABLE"), handle.index("ensureInit()"))
        self.assertLess(handle.index('"mock".equals(call.route)'), handle.index("switch (call.route)"))
        route = source.split("private void mock(", 1)[1]
        gate = route.index("ApplicationInfo.FLAG_DEBUGGABLE")
        self.assertLess(gate, route.index("MockDeviceKit.Companion.getInstance"))
        self.assertLess(gate, route.index('teardown("mock_off")'))


@unittest.skipUnless(ANDROID_JAR and Path(ANDROID_JAR).is_file(), "real Android jar unavailable: DESPIA_NX_ANDROID_JAR or ANDROID_HOME/ANDROID_SDK_ROOT required")
class AndroidCodecCompletion(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.work = Path(tempfile.mkdtemp(prefix="glasses-codec-"))
        cls.real = cls.work / "real-sdk-classes"
        cls.fake = cls.work / "fake-codec-classes"
        cls.real.mkdir()
        cls.fake.mkdir()
        compiled = run(["javac", "-cp", ANDROID_JAR, "-d", cls.real, JAVA / "GlassesMedia.java"])
        if compiled.returncode:
            raise AssertionError("Real Android SDK compile failed:\n" + compiled.stdout + compiled.stderr)
        compiled = run(["javac", "-cp", str(cls.real) + os.pathsep + ANDROID_JAR, "-d", cls.fake,
                        *sorted(SUPPORT.glob("*.java"))])
        if compiled.returncode:
            raise AssertionError("Fake codec harness compile failed:\n" + compiled.stdout + compiled.stderr)

    def test_completion_and_independent_cleanup(self):
        for mode in ("eos", "no_eos", "no_input", "empty_eos", "partial_open", "codec_release_error", "muxer_stop_error", "muxer_release_error"):
            with self.subTest(mode=mode):
                checked = run(["java", "-cp", os.pathsep.join(map(str, [self.fake, self.real, ANDROID_JAR])),
                               "despia.ext.metaglasses.CodecCompletionTest", mode, self.work / "clip.mp4"], timeout=5)
                self.assertEqual(checked.returncode, 0, checked.stdout + checked.stderr)
                print(checked.stdout.strip(), flush=True)


# GlassesServer bounds a request head with `queue.asyncAfter(deadline: .now() + 10, ...)`. libdispatch gives
# asyncAfter a leeway of up to 10% of the delay (_dispatch_after_leeway in libdispatch src/source.c), so the head
# deadline fires between 10 s and 11 s after accept, later still on a loaded host. The checks below wait for the
# expiry they assert instead of assuming it happened at a fixed instant.
HEAD_DEADLINE = 10.0
HEAD_DEADLINE_LATEST = HEAD_DEADLINE * 1.1
SCHEDULING_SLACK = 2.0


@unittest.skipUnless(platform.system() == "Darwin" and shutil.which("swiftc"), "real macOS Network.framework required")
class IosRequestLifetime(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.work = Path(tempfile.mkdtemp(prefix="glasses-network-"))
        main = cls.work / "main.swift"
        main.write_bytes((SUPPORT / "server_main.swift").read_bytes())
        cls.exe = cls.work / "server"
        compiled = run(["swiftc", "-D", "DESPIA_NX_ENABLED", "-module-cache-path", cls.work / "module-cache",
                        IOS / "GlassesCore.swift", IOS / "GlassesServer.swift", main, "-o", cls.exe])
        if compiled.returncode:
            raise AssertionError("Network.framework compile failed:\n" + compiled.stdout + compiled.stderr)

    def setUp(self):
        self.process = subprocess.Popen([str(self.exe), str(self.work)], stdin=subprocess.PIPE,
                                        stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        ready = self.process.stdout.readline().split()
        self.assertEqual(ready[0], "READY", ready)
        self.port, self.token = int(ready[1]), ready[2]
        self.peers = []

    def tearDown(self):
        for peer in self.peers:
            peer.close()
        self.process.communicate("quit\n", timeout=5)

    def connect(self):
        peer = socket.create_connection(("127.0.0.1", self.port), timeout=3)
        self.peers.append(peer)
        return peer

    def test_stalled_heads_expire_and_release_every_slot(self):
        started = time.monotonic()
        for _ in range(8):
            self.connect().sendall(b"GET /")
        stalled = self.peers[:8]
        time.sleep(0.15)
        refused = self.connect()
        refused.sendall(f"GET /{self.token}/frame.jpg HTTP/1.1\r\n\r\n".encode())
        self.assertIn(b" 503 ", refused.recv(4096))
        time.sleep(5)
        for peer in stalled:
            # Half way through the deadline every stalled head is still open; more bytes do not extend it.
            peer.setblocking(False)
            with self.assertRaises(BlockingIOError, msg="stalled request closed before its head deadline"):
                peer.recv(4096)
            peer.setblocking(True)
            peer.sendall(b"x")
        # The deadline item clears the slot before it cancels the connection, so EOF on all eight means
        # every slot is free.
        limit = started + HEAD_DEADLINE_LATEST + SCHEDULING_SLACK
        for peer in stalled:
            peer.settimeout(max(0.1, limit - time.monotonic()))
            self.assertEqual(peer.recv(4096), b"", "stalled request was not cancelled")
        valid = self.connect()
        valid.sendall(f"GET /{self.token}/frame.jpg HTTP/1.1\r\n\r\n".encode())
        self.assertIn(b" 200 ", valid.recv(4096))

    def test_complete_live_stream_outlives_header_deadline(self):
        live = self.connect()
        live.sendall(f"GET /{self.token}/live.mjpg HTTP/1.1\r\n\r\n".encode())
        received = live.recv(4096)
        self.assertIn(b" 200 ", received)
        # Past the latest instant the head deadline could fire, so a timer left running would have closed it.
        time.sleep(HEAD_DEADLINE_LATEST + 0.5)
        live.settimeout(0.1)
        try:
            while True:
                chunk = live.recv(4096)
                self.assertTrue(chunk, "valid live stream was closed by header deadline")
        except socket.timeout:
            pass
        self.process.stdin.write("frame\n")
        self.process.stdin.flush()
        live.settimeout(3)
        self.assertIn(b"--nxframe", live.recv(4096))


if __name__ == "__main__":
    unittest.main(verbosity=2)

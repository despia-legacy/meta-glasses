#!/usr/bin/env python3
"""The iOS clip writer records a real MP4 on macOS AVFoundation.

record_start ended the iOS app on its first frame (24 Nov 2026, simulator mock stream): GlassesClipWriter made its
pixel buffer adaptor after startWriting, and AVFoundation raises NSInvalidArgumentException for that. The writer
class is copied out of ios/app/GlassesMedia.swift (which also needs UIKit) and compiled for macOS with a driver that
appends decoded frames as the stream does. Derived files use TMPDIR.
"""
from pathlib import Path
import platform
import re
import shutil
import subprocess
import tempfile
import unittest

HERE = Path(__file__).resolve().parent
NX = HERE.parent
PAYLOAD = NX / "fixtures/metaglasses/metaglasses"
if not PAYLOAD.exists():
    PAYLOAD = HERE.parent
SUPPORT = HERE / "metaglasses"
IOS = PAYLOAD / "ios/app"


def run(command, timeout=120):
    return subprocess.run([str(p) for p in command], text=True, capture_output=True, timeout=timeout)


def clip_writer_source():
    text = (IOS / "GlassesMedia.swift").read_text()
    start = text.index("final class GlassesClipWriter")
    end = text.rindex("#endif")
    return "import AVFoundation\nimport CoreMedia\nimport CoreVideo\nimport Foundation\n\n" + text[start:end]


@unittest.skipUnless(platform.system() == "Darwin" and shutil.which("swiftc"), "macOS AVFoundation is required")
class IosClipWriter(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.work = Path(tempfile.mkdtemp(prefix="glasses-clip-"))
        (cls.work / "ClipWriter.swift").write_text(clip_writer_source())
        shutil.copy(SUPPORT / "clip_main.swift", cls.work / "main.swift")
        cls.exe = cls.work / "clip"
        built = run(["swiftc", "-module-cache-path", cls.work / "module-cache", cls.work / "ClipWriter.swift",
                     cls.work / "main.swift", "-o", cls.exe], timeout=300)
        if built.returncode:
            raise AssertionError("swiftc failed:\n" + built.stdout + built.stderr)

    def test_frames_become_a_finished_mp4(self):
        out = self.work / "clip.mp4"
        result = run([self.exe, out], timeout=60)
        self.assertEqual(result.returncode, 0, "the writer ended the process:\n" + result.stdout + result.stderr[-2000:])
        m = re.search(r"CLIP (\d+) (\d+) (true|false)", result.stdout)
        self.assertIsNotNone(m, result.stdout)
        frames, size, completed = int(m.group(1)), int(m.group(2)), m.group(3) == "true"
        self.assertEqual(frames, 12, result.stdout)
        self.assertTrue(completed, result.stdout)
        self.assertGreater(size, 0)
        self.assertEqual(out.read_bytes()[4:8], b"ftyp")


if __name__ == "__main__":
    unittest.main(verbosity=2)

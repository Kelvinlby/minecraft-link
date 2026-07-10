"""Cross-language wire-protocol golden test (Python half).

Decodes the committed ``protocol/golden/*.bin`` — the bytes the mod's reference encoder
(``BinaryCodec``) produced from ``cases.json`` — and asserts pylib's decoders reproduce the
fixture values, and that pylib's ``encode_instruction`` re-encodes each OCLI fixture to the
exact golden bytes. The Java half (``BinaryCodecGoldenTest``) checks the encode direction for
OCLO/OCLV and decode for OCLI, so together any offset/opcode/version drift on either side
fails a build. Pure stdlib (``unittest``/``json``), Python 3.9+.
"""

import json
import math
import struct
import unittest
from pathlib import Path

import ocl

REPO_ROOT = Path(__file__).resolve().parents[2]
GOLDEN_DIR = REPO_ROOT / "protocol" / "golden"
CASES = json.loads((GOLDEN_DIR / "cases.json").read_text(encoding="utf-8"))


def _read(name: str) -> bytes:
    return (GOLDEN_DIR / name).read_bytes()


def _f32(x):
    """Round a Python float to its float32 value so comparisons match the wire's f32 storage."""
    return struct.unpack("<f", struct.pack("<f", x))[0]


class OcloDecodeTest(unittest.TestCase):
    def test_decode_matches_fixtures(self):
        for case in CASES["oclo"]:
            with self.subTest(case=case["name"]):
                t = ocl.decode_telemetry(_read(case["bin"]))
                self.assertEqual(t.yaw, _f32(case["yaw"]))
                self.assertEqual(t.pitch, _f32(case["pitch"]))
                self.assertEqual(t.slot, case["slot"])
                self.assertEqual(t.health, _f32(case["health"]))
                self.assertEqual(t.food, case["food"])
                self.assertEqual(t.xp_level, case["xp_level"])

                groups = t.inventory.groups if t.inventory else ()
                self.assertEqual(len(groups), len(case["inventory"]))
                for got, want in zip(groups, case["inventory"]):
                    self.assertEqual(got.group, getattr(ocl, "G_" + want["group"]))
                    for gslot, wslot in zip(got.slots, want["slots"]):
                        self.assertEqual(gslot.item, wslot["item"])
                        self.assertEqual(gslot.count, wslot["count"])
                        self.assertEqual(gslot.enabled, wslot["enabled"])


class OclvDecodeTest(unittest.TestCase):
    def test_decode_matches_fixtures(self):
        for case in CASES["oclv"]:
            with self.subTest(case=case["name"]):
                f = ocl.decode_vision(_read(case["bin"]))
                self.assertEqual(f.w, case["w"])
                self.assertEqual(f.h, case["h"])
                self.assertEqual(f.near, _f32(case["near"]))
                self.assertEqual(f.far, _f32(case["far"]))
                self.assertEqual(list(f.rgb), [_f32(x) for x in case["rgb"]])
                self.assertEqual(list(f.depth), [_f32(x) for x in case["depth"]])


class OcliEncodeTest(unittest.TestCase):
    def test_encode_matches_golden(self):
        for case in CASES["ocli"]:
            with self.subTest(case=case["name"]):
                op = getattr(ocl, "OP_" + case["op"])
                slot_a = self._addr(case["a"])
                slot_b = self._addr(case["b"])
                slot_list = tuple(self._addr(s) for s in case["slots"]) if case["slots"] else None
                encoded = ocl.encode_instruction(
                    front=case["front"], back=case["back"], left=case["left"], right=case["right"],
                    jump=case["jump"], sprint=case["sprint"], sneak=case["sneak"],
                    slot=case["slot"], attack=case["attack"], interact=case["interact"],
                    yaw=self._angle(case["yaw"]), pitch=self._angle(case["pitch"]),
                    inv_op=op, slot_a=slot_a, slot_b=slot_b, slot_list=slot_list,
                )
                self.assertEqual(encoded, _read(case["bin"]),
                                 f"OCLI encoding drifted for {case['name']}")

    @staticmethod
    def _addr(obj):
        if obj is None:
            return None
        return (getattr(ocl, "G_" + obj["group"]), obj["index"])

    @staticmethod
    def _angle(v):
        return math.nan if v == "NaN" else v


if __name__ == "__main__":
    unittest.main()

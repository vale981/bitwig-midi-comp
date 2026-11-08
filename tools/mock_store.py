"""Writes sample saved comps for screenshots of the saved-comps list.

Usage: python3 tools/mock_store.py DIR

Pass DIR to the companion with ``--store-dir`` together with ``tools/mock_api.py``. The comps
belong to the mock's project and clip: two recording sets of "Keys (arranger)" (one named) with
several cuts, autosaves among them, and a comp of another clip.
"""
import json
import sys
import time
from pathlib import Path

PROJECT = "Demo song"
directory = Path(sys.argv[1])
directory.mkdir(parents=True, exist_ok=True)
base = int(time.mktime((2026, 11, 7, 17, 0, 0, 0, 0, -1)))


def take(i, count):
    return {"name": f"Pass {i + 1}", "source": "arranger", "length": 4096,
            "notes": [{"ch": 0, "x": i * 128 + n * 512, "key": 60 + (i + n) % 12, "vel": 0.8,
                       "rel": 0.5, "dur": 0.5} for n in range(count)]}


def takes_of(count):
    return [take(i, 6) for i in range(count)]


def key_of(value):
    """The companion's recordings key: 64-bit FNV-1a of the compact JSON text."""
    text = json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False)
    h = 14695981039346656037
    for byte in text.encode():
        h = ((h ^ byte) * 1099511628211) % (1 << 64)
    return f"{h:016x}"


def write(session, clip, takes, segments, minutes, name=""):
    comp = {"format": 1, "project": PROJECT, "clip": clip, "source": "arranger",
            "stepSize": 0.0078125, "fingerprint": "", "takes": takes, "segments": segments}
    doc = {"project": PROJECT, "clip": clip, "session": session, "takes": len(takes),
           "savedAt": base + minutes * 60, "fingerprint": "", "name": name, "comp": comp}
    safe = "".join(c if c.isalnum() or c in "-_" else "_" for c in clip)
    (directory / f"Demo_song__{safe}__{session}.json").write_text(json.dumps(doc))


evening = takes_of(6)
write("a1", "Keys (arranger)", evening, [[0, 1024, 0], [1024, 4096, 3]], 60, "Chorus lift")
write("a2", "Keys (arranger)", evening, [[0, 1024, 0], [1024, 4096, 3]], 62)  # autosave
write("a3", "Keys (arranger)", evening, [[0, 2048, 1], [2048, 3072, 5], [3072, 4096, 2]], 65,
      "Safe take")
write("a4", "Keys (arranger)", evening, [[0, 4096, 2]], 70)
first = takes_of(4)
write("b1", "Keys (arranger)", first, [[0, 4096, 1]], 10)
write("b2", "Keys (arranger)", first, [[0, 2048, 0], [2048, 4096, 3]], 15, "First idea")
write("c1", "Bass (arranger)", takes_of(3), [[0, 4096, 0]], 30, "Bass line")

(directory / "recordings.json").write_text(json.dumps({key_of(evening): "Evening session"}))

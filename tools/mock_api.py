"""Minimal stand-in for the extension's HTTP API with sample data, for UI screenshots.

Usage: python3 tools/mock_api.py PORT ENDPOINT_FILE [start|record|session]

``start`` serves the state before a comp session (a clip selected in Bitwig), ``record`` a session
before the first pass, and ``session`` (the default) a session with six recorded passes.
"""
import json
import random
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

port, endpoint_file = int(sys.argv[1]), sys.argv[2]
mode = sys.argv[3] if len(sys.argv) > 3 else "session"
SPB, BAR, LENGTH = 128, 512, 8 * 512


def take_notes(seed, base):
    rnd = random.Random(seed)
    notes = []
    for beat in range(LENGTH // SPB):
        if rnd.random() < 0.85:
            x = beat * SPB + rnd.randint(-6, 6) if beat else 0
            key = base + [0, 3, 5, 7, 10, 12][(beat + seed) % 6] + rnd.choice([0, 0, 2])
            notes.append([max(0, x), key, rnd.choice([48, 64, 96, 128]),
                          round(rnd.uniform(0.45, 1.0), 3), 0, -1])
        if rnd.random() < 0.4:
            notes.append([beat * SPB + 64, base - 12 + rnd.choice([0, 7]), 48,
                          round(rnd.uniform(0.4, 0.8), 3), 0, -1])
    return notes


takes = [{"id": i, "name": f"Pass {i}", "source": "arranger", "length": LENGTH,
          "notes": take_notes(i, 60 + (i % 3))} for i in range(1, 7)]
segments = [[0, 1024, 1], [1024, 2048, 4], [2048, 3072, 2], [3072, 3584, 6], [3584, 4096, 4]]
comp = [n[:5] + [tid] for f, t, tid in segments for n in takes[tid - 1]["notes"] if f <= n[0] < t]

if mode == "start":
    state = {"v": 1, "stepsPerBeat": SPB, "barSteps": BAR, "length": 0,
             "launcherSel": None, "arrangerSel": "Keys (arranger)", "canUndoEdit": False,
             "canRevert": False, "message": "", "session": None,
             "takes": [], "segments": [], "comp": []}
elif mode == "record":
    state = {"v": 1, "stepsPerBeat": SPB, "barSteps": BAR, "length": 0,
             "launcherSel": None, "arrangerSel": "Keys (arranger)", "canUndoEdit": False,
             "canRevert": False, "message": "Comping Keys (arranger)",
             "session": {"source": "arranger", "label": "Keys (arranger)", "available": True,
                         "recording": False, "overdub": True, "pending": False,
                         "truncated": False, "passes": 0},
             "takes": [], "segments": [], "comp": []}
else:
    state = {"v": 1, "stepsPerBeat": SPB, "barSteps": BAR, "length": LENGTH,
             "launcherSel": None, "arrangerSel": "Keys (arranger)", "canUndoEdit": True,
             "canRevert": True, "message": "Take 4: 3.1 to 5.1",
             "session": {"source": "arranger", "label": "Keys (arranger)", "available": True,
                         "recording": False, "overdub": True, "pending": False,
                         "truncated": False, "passes": 6},
             "takes": takes, "segments": segments, "comp": comp}
body = json.dumps(state).encode()


class Handler(BaseHTTPRequestHandler):
    def log_message(self, format, *args):  # noqa: A002 - signature of the base class
        del format, args

    def do_GET(self):
        if self.path.startswith("/api/v1/state") and "since=1" in self.path:
            self.send_response(204)
            self.end_headers()
            return
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_POST(self):
        reply = json.dumps({"ok": True, "message": "ok"}).encode()
        self.send_response(200)
        self.send_header("Content-Length", str(len(reply)))
        self.end_headers()
        self.wfile.write(reply)


with open(endpoint_file, "w") as f:
    json.dump({"url": f"http://127.0.0.1:{port}", "token": "t", "apiVersion": 1, "pid": 0}, f)
ThreadingHTTPServer(("127.0.0.1", port), Handler).serve_forever()

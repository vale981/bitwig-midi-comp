"""Tests for midi_copies.py: ``python3 -m unittest discover -s tools``."""

import unittest

from midi_copies import describe, parse_clients, plan

PROC = """Client info
  cur  clients : 7

Client   0 : "System" [Kernel Legacy]
  Port   0 : "Timer" (Rwe-) [In/Out]
    Connecting To: 142:0
Client  14 : "Midi Through" [Kernel Legacy]
  Port   0 : "Midi Through Port-0" (RWe-) [In/Out]
Client  20 : "USB Axiom 61" [Kernel Legacy]
  Port   0 : "USB Axiom 61 MIDI 1" (RWeX) [In/Out]
    Connecting To: 24:0, 128:0
  Port   1 : "USB Axiom 61 MIDI 2" (R-e-) [In]
Client  24 : "Virtual Raw MIDI 16-0" [Kernel Legacy]
  Port   0 : "VirMIDI 16-0" (RWeX) [In/Out]
    Connected From: 20:0
Client  25 : "Virtual Raw MIDI 16-1" [Kernel Legacy]
  Port   0 : "VirMIDI 16-1" (RWeX) [In/Out]
Client 128 : "MIDI Comp" [User Legacy]
  Port   0 : "Keyboard" (-We-) [Out]
    Connected From: 20:0
  Output pool :
    Pool size          : 500
"""

AXIOM_1 = "USB Axiom 61:USB Axiom 61 MIDI 1"
AXIOM_2 = "USB Axiom 61:USB Axiom 61 MIDI 2"


class MidiCopiesTest(unittest.TestCase):
    def test_parse(self):
        clients = {c.name: c for c in parse_clients(PROC)}
        axiom = clients["USB Axiom 61"]
        self.assertTrue(axiom.kernel)
        self.assertEqual([p.name for p in axiom.ports], ["USB Axiom 61 MIDI 1", "USB Axiom 61 MIDI 2"])
        self.assertEqual(axiom.ports[0].sends_to, {(24, 0), (128, 0)})
        self.assertFalse(axiom.ports[1].writable)
        self.assertFalse(clients["MIDI Comp"].kernel)

    def test_every_port_gets_a_copy_and_feedback(self):
        result = plan(parse_clients(PROC), {}, 100.0, {"Midi Through"})
        self.assertEqual(result.state[AXIOM_1]["copy"], "Virtual Raw MIDI 16-0")
        self.assertEqual(result.state[AXIOM_2]["copy"], "Virtual Raw MIDI 16-1")
        # 20:0 -> 24:0 exists; add feedback 24:0 -> 20:0 and the second port. Port 1 is
        # read-only, so no feedback; MIDI Comp's connection is left alone.
        self.assertCountEqual(result.connect, [((24, 0), (20, 0)), ((20, 1), (25, 0))])
        self.assertEqual(result.disconnect, [])

    def test_assignments_are_kept(self):
        state = {AXIOM_1: {"copy": "Virtual Raw MIDI 16-1", "seen": 1.0}}
        result = plan(parse_clients(PROC), state, 100.0, {"Midi Through"})
        self.assertEqual(result.state[AXIOM_1]["copy"], "Virtual Raw MIDI 16-1")
        self.assertEqual(result.state[AXIOM_2]["copy"], "Virtual Raw MIDI 16-0")
        # The Axiom moves from 16-0 to 16-1.
        self.assertIn(((20, 0), (24, 0)), result.disconnect)
        self.assertIn(((20, 0), (25, 0)), result.connect)

    def test_absent_devices_keep_their_copy_until_space_runs_out(self):
        state = {"Launchpad:Launchpad MIDI 1": {"copy": "Virtual Raw MIDI 16-1", "seen": 50.0}}
        result = plan(parse_clients(PROC), state, 100.0, {"Midi Through", "USB Axiom 61"})
        self.assertEqual(result.state["Launchpad:Launchpad MIDI 1"]["copy"],
                         "Virtual Raw MIDI 16-1")
        # Excluded: the Axiom's existing connection to a copy is removed.
        self.assertEqual(result.disconnect, [((20, 0), (24, 0))])

        result = plan(parse_clients(PROC), state, 100.0, {"Midi Through"})
        self.assertEqual(result.state[AXIOM_2]["copy"], "Virtual Raw MIDI 16-1")
        self.assertNotIn("Launchpad:Launchpad MIDI 1", result.state)
        self.assertTrue(any("taken over from Launchpad:Launchpad MIDI 1" in m
                            for m in result.messages))

    def test_the_companion_preview_port_gets_a_copy(self):
        proc = PROC.replace('''Client 128 : "MIDI Comp" [User Legacy]
  Port   0 : "Keyboard" (-We-) [Out]''', '''Client 128 : "MIDI Comp" [User Legacy]
  Port   0 : "Keyboard" (-We-) [Out]
Client 129 : "MIDI Comp" [User Legacy]
  Port   0 : "Preview" (R-e-) [In]''')
        result = plan(parse_clients(proc), {}, 100.0, {"Midi Through", "USB Axiom 61"})
        # The excluded Axiom frees the first copy, which the preview port gets.
        self.assertEqual(result.state["MIDI Comp:Preview"]["copy"], "Virtual Raw MIDI 16-0")
        self.assertIn(((129, 0), (24, 0)), result.connect)
        self.assertIn(((20, 0), (24, 0)), result.disconnect)
        # Output only: nothing goes back to it, and the keyboard input port gets no copy.
        self.assertNotIn(((24, 0), (129, 0)), result.connect)
        self.assertNotIn("MIDI Comp:Keyboard", result.state)

    def test_describe(self):
        lines = describe(parse_clients(PROC))
        self.assertEqual(lines[0].split(), ["VirMIDI", "16-0", "<-", "USB", "Axiom", "61:USB",
                                            "Axiom", "61", "MIDI", "1"])
        self.assertTrue(lines[1].endswith("(free)"))


if __name__ == "__main__":
    unittest.main()

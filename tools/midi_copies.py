#!/usr/bin/env python3
"""Give every MIDI device a copy on a virtual raw MIDI port (Linux, ``snd-virmidi``).

Bitwig opens raw MIDI devices exclusively, and once any program reads a device through the ALSA
sequencer, the kernel holds the device's raw port, so Bitwig reports it as busy. With a copy,
Bitwig uses the virtual port ("VirMIDI <card>-<device>") and other programs, such as the MIDI
Comp companion, read the device's sequencer port at the same time.

``run`` connects every readable port of every hardware MIDI device to a virtual port, and the
virtual port back to the device if the device accepts input (controller feedback such as LEDs).
Each device port keeps its virtual port across replugging and restarts (remembered in a state
file); when all virtual ports are taken, the one of the device seen least recently is reused.
``list`` shows the current copies.

The virtual ports come from the ``snd-virmidi`` kernel module, for example
``modprobe snd-virmidi index=16 midi_devs=4`` for four copies on card 16. The kernel names them;
they cannot be renamed.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import sys
import time
from dataclasses import dataclass, field
from pathlib import Path

CLIENTS_FILE = Path("/proc/asound/seq/clients")
VIRTUAL = re.compile(r"^Virtual Raw MIDI (\d+)-(\d+)$")

Address = tuple[int, int]


@dataclass
class Port:
    """A sequencer port.

    Args:
        client: client number.
        number: port number.
        name: port name.
        readable: whether other ports can subscribe to it (it sends).
        writable: whether it can be subscribed to (it receives).
        sends_to: ports it is connected to.
        receives_from: ports connected to it.
    """

    client: int
    number: int
    name: str
    readable: bool
    writable: bool
    sends_to: set[Address] = field(default_factory=set)
    receives_from: set[Address] = field(default_factory=set)

    @property
    def address(self) -> Address:
        return (self.client, self.number)


@dataclass
class Client:
    """A sequencer client.

    Args:
        number: client number.
        name: client name.
        kernel: whether the kernel provides it (hardware and virtual devices).
        ports: its ports.
    """

    number: int
    name: str
    kernel: bool
    ports: list[Port] = field(default_factory=list)


@dataclass
class Plan:
    """Changes to make.

    Args:
        state: the assignment to remember, ``{source key: {"copy": name, "seen": time}}``.
        connect: ``(sender, receiver)`` pairs to connect.
        disconnect: ``(sender, receiver)`` pairs to disconnect.
        messages: log lines for new assignments and problems.
    """

    state: dict[str, dict]
    connect: list[tuple[Address, Address]]
    disconnect: list[tuple[Address, Address]]
    messages: list[str]


def parse_clients(text: str) -> list[Client]:
    """Parses ``/proc/asound/seq/clients``.

    Args:
        text: the file's content.

    Returns:
        The clients with their ports and connections.
    """
    clients: list[Client] = []
    port: Port | None = None
    for line in text.splitlines():
        if m := re.match(r'^Client\s+(\d+)\s*:\s*"(.*)"\s*\[(\w+)', line):
            clients.append(Client(int(m[1]), m[2], m[3] == "Kernel"))
            port = None
        elif (m := re.match(r'^\s+Port\s+(\d+)\s*:\s*"(.*)"\s*\((.{4})\)', line)) and clients:
            flags = m[3]
            port = Port(clients[-1].number, int(m[1]), m[2], flags[0] == "R", flags[1] == "W")
            clients[-1].ports.append(port)
        elif port is not None and (m := re.match(r"^\s+(Connecting To|Connected From):(.*)", line)):
            peers = {(int(c), int(p)) for c, p in re.findall(r"(\d+):(\d+)", m[2])}
            (port.sends_to if m[1] == "Connecting To" else port.receives_from).update(peers)
    return clients


def source_key(client: Client, port: Port) -> str:
    """Returns a name for a device port that survives replugging (client numbers change)."""
    return f"{client.name}:{port.name}"


def copy_sort_key(name: str) -> tuple[int, int]:
    m = VIRTUAL.match(name)
    return (int(m[1]), int(m[2])) if m else (1 << 30, 0)


def plan(clients: list[Client], state: dict[str, dict], now: float,
         exclude: set[str]) -> Plan:
    """Works out the copies and the connections to change.

    Args:
        clients: the current clients, from :func:`parse_clients`.
        state: the remembered assignment.
        now: current time, recorded for devices that are present.
        exclude: client names of devices that get no copy.

    Returns:
        The new assignment and the connection changes.
    """
    copies = {c.name: c.ports[0] for c in clients
              if c.kernel and VIRTUAL.match(c.name) and c.ports}
    hardware = [c for c in clients if c.kernel and c.number != 0 and not VIRTUAL.match(c.name)]
    sources = {source_key(c, p): p for c in hardware if c.name not in exclude
               for p in c.ports if p.readable}
    # Connections between copies and any device, excluded ones too, are managed here.
    device_ports = {p.address for c in hardware for p in c.ports}

    state = {k: dict(v) for k, v in state.items()}
    messages: list[str] = []
    for key in sources:
        if key in state:
            state[key]["seen"] = now

    # Copies of present devices first, in a stable order; conflicts go to the later key.
    taken: dict[str, str] = {}
    unassigned = []
    for key in sorted(sources):
        copy = state.get(key, {}).get("copy")
        if copy in copies and copy not in taken:
            taken[copy] = key
        else:
            unassigned.append(key)
    for key in unassigned:
        absent = {v["copy"]: v.get("seen", 0) for k, v in state.items()
                  if k not in sources and v.get("copy") in copies}
        free = [c for c in sorted(copies, key=copy_sort_key)
                if c not in taken and c not in absent]
        reused_from = None
        if not free:
            # Reuse the copy of the device seen least recently.
            free = sorted((c for c in absent if c not in taken), key=lambda c: absent[c])
            if free:
                reused_from = next(k for k, v in state.items()
                                   if k not in sources and v.get("copy") == free[0])
        if not free:
            messages.append(f"No virtual port left for {key}; load snd-virmidi with more "
                            "devices")
            state.pop(key, None)
            continue
        copy = free[0]
        for k in [k for k, v in state.items() if v.get("copy") == copy]:
            del state[k]
        state[key] = {"copy": copy, "seen": now}
        taken[copy] = key
        messages.append(f"{key} -> {copy_label(copy)}" + (
            f" (taken over from {reused_from}, which is not connected; there are only "
            f"{len(copies)} virtual ports)" if reused_from else ""))

    connect: list[tuple[Address, Address]] = []
    disconnect: list[tuple[Address, Address]] = []
    for copy, port in copies.items():
        source = sources[taken[copy]] if copy in taken else None
        want = source.address if source else None
        # Device -> copy.
        for sender in port.receives_from:
            if sender != want and sender in device_ports:
                disconnect.append((sender, port.address))
        if want and want not in port.receives_from:
            connect.append((want, port.address))
        # Copy -> device, for feedback, if the device port accepts input.
        back = want if source and source.writable else None
        for receiver in port.sends_to:
            if receiver != back and receiver in device_ports:
                disconnect.append((port.address, receiver))
        if back and back not in port.sends_to:
            connect.append((port.address, back))
    return Plan(state, connect, disconnect, messages)


def copy_label(client_name: str) -> str:
    """Returns the name Bitwig and other programs show for a copy, e.g. "VirMIDI 16-0"."""
    m = VIRTUAL.match(client_name)
    return f"VirMIDI {m[1]}-{m[2]}" if m else client_name


def describe(clients: list[Client]) -> list[str]:
    """Returns one line per virtual port with the device port connected to it."""
    names = {(c.number, p.number): f"{c.name}:{p.name}" for c in clients for p in c.ports}
    lines = []
    for c in sorted((c for c in clients if VIRTUAL.match(c.name) and c.ports),
                    key=lambda c: copy_sort_key(c.name)):
        port = c.ports[0]
        senders = sorted(names.get(a, f"{a[0]}:{a[1]}") for a in port.receives_from)
        feedback = " (and back)" if port.sends_to & port.receives_from else ""
        lines.append(f"{copy_label(c.name):<16} <- {', '.join(senders) or '(free)'}{feedback}")
    return lines


def aconnect(sender: Address, receiver: Address, remove: bool) -> None:
    args = ["aconnect"] + (["-d"] if remove else []) + [
        f"{sender[0]}:{sender[1]}", f"{receiver[0]}:{receiver[1]}"]
    subprocess.run(args, check=False, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)


def load_state(path: Path) -> dict[str, dict]:
    try:
        data = json.loads(path.read_text())
        return data if isinstance(data, dict) else {}
    except (OSError, ValueError):
        return {}


def save_state(path: Path, state: dict[str, dict]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix(".tmp")
    tmp.write_text(json.dumps(state, indent=1, sort_keys=True))
    tmp.replace(path)


def default_state_path() -> Path:
    if directory := os.environ.get("STATE_DIRECTORY"):
        return Path(directory) / "copies.json"
    base = os.environ.get("XDG_STATE_HOME") or str(Path.home() / ".local" / "state")
    return Path(base) / "midi-copies.json"


def run(state_path: Path, exclude: set[str], interval: float) -> None:
    state = load_state(state_path)
    warned: set[str] = set()
    while True:
        try:
            clients = parse_clients(CLIENTS_FILE.read_text())
        except OSError as e:
            print(f"Cannot read {CLIENTS_FILE}: {e}", file=sys.stderr, flush=True)
            time.sleep(interval)
            continue
        if not any(VIRTUAL.match(c.name) for c in clients) and "virmidi" not in warned:
            print("No virtual MIDI ports; is snd-virmidi loaded?", flush=True)
            warned.add("virmidi")
        result = plan(clients, state, time.time(), exclude)
        for sender, receiver in result.disconnect:
            aconnect(sender, receiver, remove=True)
        for sender, receiver in result.connect:
            aconnect(sender, receiver, remove=False)
        for message in result.messages:
            if message not in warned:
                print(message, flush=True)
                if message.startswith("No virtual port"):
                    warned.add(message)
        if {k: v["copy"] for k, v in result.state.items()} != \
                {k: v.get("copy") for k, v in state.items()} or result.connect:
            try:
                save_state(state_path, result.state)
            except OSError as e:
                print(f"Cannot save {state_path}: {e}", file=sys.stderr, flush=True)
        state = result.state
        time.sleep(interval)


def main() -> int:
    parser = argparse.ArgumentParser(prog="midi-copies", description=(__doc__ or "").split("\n\n")[0])
    sub = parser.add_subparsers(dest="command")
    run_parser = sub.add_parser("run", help="keep every MIDI device connected to a copy")
    run_parser.add_argument("--state", type=Path, default=default_state_path(),
                            help="file that remembers which device has which copy")
    run_parser.add_argument("--exclude", action="append",
                            help="client name of a device without a copy (repeatable; "
                                 "default: Midi Through)")
    run_parser.add_argument("--interval", type=float, default=2.0,
                            help="seconds between checks")
    sub.add_parser("list", help="show the copies")
    args = parser.parse_args()
    if args.command == "run":
        run(args.state, set(args.exclude or ["Midi Through"]), args.interval)
        return 0
    for line in describe(parse_clients(CLIENTS_FILE.read_text())):
        print(line)
    return 0


if __name__ == "__main__":
    sys.exit(main())

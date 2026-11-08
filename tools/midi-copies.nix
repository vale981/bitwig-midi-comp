# NixOS module: every MIDI device also as a virtual raw MIDI device, for Bitwig.
#
# Bitwig opens raw MIDI devices exclusively, and once any program reads a device through the
# ALSA sequencer (such as the MIDI Comp companion), the kernel holds the device's raw port and
# Bitwig reports it as busy. This module loads snd-virmidi and runs midi_copies.py, which
# connects every hardware MIDI port to its own virtual port ("VirMIDI <card>-<n>") and back.
# Select the VirMIDI devices in Bitwig; `midi-copies list` shows which is which.
#
# Usage: imports = [ midi-comp.nixosModules.midi-copies ]; services.midi-copies.enable = true;
{
  config,
  lib,
  pkgs,
  ...
}:
let
  cfg = config.services.midi-copies;

  # snd-virmidi has up to four devices per card.
  cardCount = (cfg.copies + 3) / 4;
  cards = lib.genList (i: {
    index = cfg.firstCard + i;
    devices = lib.min 4 (cfg.copies - 4 * i);
  }) cardCount;
  joined = f: lib.concatMapStringsSep "," f cards;

  midiCopies = pkgs.writeShellApplication {
    name = "midi-copies";
    runtimeInputs = [
      pkgs.python3
      pkgs.alsa-utils
    ];
    text = ''exec python3 ${./midi_copies.py} "$@"'';
  };
in
{
  options.services.midi-copies = {
    enable = lib.mkEnableOption "a virtual raw MIDI copy of every MIDI device";

    copies = lib.mkOption {
      type = lib.types.ints.between 1 16;
      default = 4;
      description = ''
        Number of virtual ports. Each readable port of a device takes one (a keyboard with a
        second port for its controls takes two).
      '';
    };

    firstCard = lib.mkOption {
      type = lib.types.ints.between 0 28;
      default = 16;
      description = ''
        ALSA card number of the first virtual card. A fixed number keeps the names of the copies
        ("VirMIDI 16-0") the same across reboots, so Bitwig finds them again.
      '';
    };

    include = lib.mkOption {
      type = lib.types.listOf lib.types.str;
      default = [ "MIDI Comp" ];
      description = ''
        Sequencer client names of programs whose output ports get a copy too, such as the MIDI
        Comp companion's preview port.
      '';
    };

    exclude = lib.mkOption {
      type = lib.types.listOf lib.types.str;
      default = [ "Midi Through" ];
      description = "Sequencer client names of devices that get no copy (see `aconnect -l`).";
    };

    interval = lib.mkOption {
      type = lib.types.ints.positive;
      default = 2;
      description = "Seconds between checks for new devices.";
    };
  };

  config = lib.mkIf cfg.enable {
    boot.kernelModules = [ "snd-virmidi" ];
    boot.extraModprobeConfig = ''
      options snd-virmidi enable=${joined (_: "1")} index=${
        joined (c: toString c.index)
      } midi_devs=${joined (c: toString c.devices)}
    '';

    environment.systemPackages = [
      midiCopies
      pkgs.alsa-utils
    ];

    systemd.services.midi-copies = {
      description = "Virtual raw MIDI copies of MIDI devices";
      wantedBy = [ "multi-user.target" ];
      after = [ "sound.target" ];
      serviceConfig = {
        ExecStart = lib.concatStringsSep " " (
          [
            (lib.getExe midiCopies)
            "run"
            "--interval"
            (toString cfg.interval)
          ]
          ++ lib.concatMap (name: [
            "--exclude"
            (lib.escapeShellArg name)
          ]) cfg.exclude
          ++ lib.concatMap (name: [
            "--include"
            (lib.escapeShellArg name)
          ]) cfg.include
        );
        DynamicUser = true;
        # /dev/snd/seq belongs to the audio group.
        SupplementaryGroups = [ "audio" ];
        # Remembers which device has which copy.
        StateDirectory = "midi-copies";
        Restart = "always";
      };
    };
  };
}

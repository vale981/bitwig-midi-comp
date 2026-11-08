{
  description = "MIDI Comp - Bitwig Studio extension and companion app";

  inputs.nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";

  outputs = { nixpkgs, ... }:
    let
      forAllSystems = nixpkgs.lib.genAttrs [ "x86_64-linux" "aarch64-linux" ];
      fontFor = pkgs: "${pkgs.inter}/share/fonts/truetype/InterVariable.ttf";
      companionFor = pkgs: pkgs.stdenv.mkDerivation {
        pname = "midicomp-companion";
        version = "0.1.0-alpha.2";
        # Exclude local build output, which `path:` flakes would otherwise copy.
        src = pkgs.lib.cleanSourceWith {
          src = ./companion;
          filter = path: _type:
            !(builtins.elem (baseNameOf path) [ "build" "compile_commands.json" ".cache" ]);
        };
        nativeBuildInputs = [ pkgs.cmake pkgs.ninja ];
        buildInputs = [ pkgs.glfw pkgs.imgui pkgs.httplib pkgs.nlohmann_json pkgs.libGL pkgs.rtmidi pkgs.alsa-lib ];
        cmakeFlags = [ "-DMIDICOMP_DEFAULT_FONT=${fontFor pkgs}" ];
        doCheck = true;
        meta.license = pkgs.lib.licenses.mit;
      };
    in
    {
      nixosModules.midi-copies = import ./tools/midi-copies.nix;

      packages = forAllSystems (system:
        let pkgs = nixpkgs.legacyPackages.${system}; in {
          companion = companionFor pkgs;
          default = companionFor pkgs;
        });

      devShells = forAllSystems (system:
        let pkgs = nixpkgs.legacyPackages.${system}; in {
          default = pkgs.mkShell {
            inputsFrom = [ (companionFor pkgs) ];
            packages = [ pkgs.jdk21 pkgs.maven pkgs.clang-tools ];
            MIDICOMP_FONT = fontFor pkgs;
          };
        });
    };
}

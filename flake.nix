{
  description = "ajmx — agent-first JMX CLI";

  nixConfig = {
    extra-substituters = [ "https://ajmx.cachix.org" ];
    extra-trusted-public-keys = [
      "ajmx.cachix.org-1:GIHBWC7DAYdQHwsuTKF6ouPF0ziSXtxRcktMOr4xXMk="
    ];
  };

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixpkgs-unstable";
    flake-parts = {
      url = "github:hercules-ci/flake-parts";
      inputs.nixpkgs-lib.follows = "nixpkgs";
    };
    treefmt-nix = {
      url = "github:numtide/treefmt-nix";
      inputs.nixpkgs.follows = "nixpkgs";
    };
    git-hooks = {
      url = "github:cachix/git-hooks.nix";
      inputs.nixpkgs.follows = "nixpkgs";
    };
  };

  outputs =
    inputs:
    inputs.flake-parts.lib.mkFlake { inherit inputs; } {
      imports = [
        inputs.treefmt-nix.flakeModule
        inputs.git-hooks.flakeModule
      ];

      systems = [
        "aarch64-darwin"
        "aarch64-linux"
        "x86_64-linux"
      ];

      perSystem =
        { config, pkgs, ... }:
        let
          graalvm = pkgs.graalvmPackages.graalvm-ce;
        in
        {
          packages = rec {
            ajmx = pkgs.callPackage ./nix/ajmx.nix { };
            default = ajmx;
          };

          checks = {
            inherit (config.packages) ajmx;
          };

          treefmt = {
            projectRootFile = "flake.nix";
            programs.nixfmt.enable = true;
          };

          pre-commit.settings.hooks = {
            treefmt = {
              enable = true;
              package = config.treefmt.build.wrapper;
            };

            gitleaks = {
              enable = true;
              name = "gitleaks";
              entry = "${pkgs.writeShellScript "gitleaks-files" ''
                status=0
                for file in "$@"; do
                  ${pkgs.gitleaks}/bin/gitleaks dir --config .gitleaks.toml --redact --verbose --no-banner --log-level warn "$file" || status=1
                done
                exit $status
              ''}";
            };

            convco.enable = true;

            end-of-file-fixer.enable = true;
            trim-trailing-whitespace.enable = true;
            check-merge-conflicts = {
              enable = true;
              args = [ "--assume-in-merge" ];
            };
            check-added-large-files = {
              enable = true;
              args = [ "--enforce-all" ];
            };
            detect-private-keys.enable = true;

            typos = {
              enable = true;
              files = "\\.md$";
              settings.ignored-words = [
                "Cahce"
                "patern"
                "writeable"
              ];
            };
            markdownlint = {
              enable = true;
              settings.configuration = {
                default = true;
                MD013 = false;
                MD031.list_items = false;
                MD033 = false;
                MD060 = false;
              };
            };
          };

          devShells.default = pkgs.mkShell {
            packages = [
              graalvm
              pkgs.git
            ]
            ++ config.pre-commit.settings.enabledPackages;

            JAVA_HOME = graalvm.home;
            GRAALVM_HOME = graalvm.home;

            shellHook = config.pre-commit.installationScript;
          };

          devShells.docs = pkgs.mkShell {
            packages = [
              pkgs.nodejs_24
              pkgs.pnpm
              pkgs.git
            ]
            ++ config.pre-commit.settings.enabledPackages;

            shellHook = config.pre-commit.installationScript;
          };
        };
    };
}

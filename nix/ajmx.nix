{
  lib,
  stdenv,
  gradle-packages,
  graalvmPackages,
  buildGraalvmNativeImage,
}:

let
  version = "0.2.4";
  graalvm = graalvmPackages.graalvm-ce;
  wrapperProperties = lib.splitString "\n" (
    builtins.readFile ../gradle/wrapper/gradle-wrapper.properties
  );
  wrapperProperty =
    name:
    lib.removePrefix "${name}=" (
      lib.findFirst (lib.hasPrefix "${name}=") (throw "gradle-wrapper.properties has no ${name}")
        wrapperProperties
    );
  gradle =
    (gradle-packages.mkGradle {
      version = lib.head (builtins.match ".*/gradle-(.+)-bin\\.zip" (wrapperProperty "distributionUrl"));
      hash = builtins.convertHash {
        hash = wrapperProperty "distributionSha256Sum";
        hashAlgo = "sha256";
        toHashFormat = "sri";
      };
      defaultJava = graalvm;
    }).wrapped;
  fs = lib.fileset;

  jar = stdenv.mkDerivation (finalAttrs: {
    pname = "ajmx-cli";
    inherit version;
    src = fs.toSource {
      root = ../.;
      fileset = fs.unions [
        ../settings.gradle.kts
        ../build.gradle.kts
        ../gradle.properties
        ../gradle/libs.versions.toml
        ../modules/cli/build.gradle.kts
        ../modules/cli/src/main
        ../modules/it/build.gradle.kts
      ];
    };
    nativeBuildInputs = [ gradle ];
    mitmCache = gradle.fetchDeps {
      pkg = finalAttrs.finalPackage;
      data = ./deps.json;
    };
    __darwinAllowLocalNetworking = true;
    gradleBuildTask = ":cli:jar";
    installPhase = ''
      install -Dm644 modules/cli/build/libs/cli-${version}.jar $out/ajmx-cli.jar
    '';
  });
in
buildGraalvmNativeImage {
  pname = "ajmx";
  inherit version;
  src = "${jar}/ajmx-cli.jar";
  graalvmDrv = graalvm;
  extraNativeImageBuildArgs = [ "-Os" ];

  doInstallCheck = true;
  installCheckPhase = ''
    runHook preInstallCheck
    $out/bin/ajmx version | grep -F '"version":"${version}"'
    runHook postInstallCheck
  '';

  passthru = {
    inherit (jar) mitmCache;
  };

  meta = {
    description = "JMX CLI for AI agents";
    homepage = "https://github.com/yagipass/ajmx";
    license = lib.licenses.asl20;
    mainProgram = "ajmx";
  };
}

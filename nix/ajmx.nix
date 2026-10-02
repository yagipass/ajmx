{
  lib,
  maven,
  graalvmPackages,
  buildGraalvmNativeImage,
}:

let
  version = "0.1.0";
  graalvm = graalvmPackages.graalvm-ce;
  fs = lib.fileset;

  jar = maven.buildMavenPackage {
    pname = "ajmx-cli";
    inherit version;
    mvnJdk = graalvm;
    src = fs.toSource {
      root = ../.;
      fileset = fs.unions [
        ../pom.xml
        ../.mvn
        ../modules/cli/pom.xml
        ../modules/cli/src/main
        ../modules/it/pom.xml
      ];
    };
    mvnHash = "sha256-i8KuuAONVh5OdJs029TjWUt5WA8GWMDJeBS5cOf87s0=";
    mvnParameters = "-pl modules/cli -am";
    doCheck = false;
    installPhase = ''
      install -Dm644 modules/cli/target/ajmx-cli-${version}.jar $out/ajmx-cli.jar
    '';
  };
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

  meta = {
    description = "JMX CLI for AI agents";
    homepage = "https://github.com/yagipass/ajmx";
    license = lib.licenses.asl20;
    mainProgram = "ajmx";
  };
}

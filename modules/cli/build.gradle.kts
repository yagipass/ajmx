plugins {
    id("org.graalvm.buildtools.native")
}

val mainClassName = "io.github.yagipass.ajmx.Main"

dependencies {
    compileOnly(libs.errorprone.annotations)
    compileOnly(libs.jspecify)
    compileOnly(libs.graalvm.nativeimage)
    testCompileOnly(libs.errorprone.annotations)
    testCompileOnly(libs.jspecify)
    testImplementation(libs.graalvm.nativeimage)
}

tasks.processResources {
    val version = project.version.toString()
    inputs.property("version", version)
    filesMatching("io/github/yagipass/ajmx/cli/version.txt") {
        expand("project" to mapOf("version" to version))
    }
}

tasks.jar {
    manifest {
        attributes("Main-Class" to mainClassName)
    }
}

graalvmNative {
    testSupport = false
    binaries.named("main") {
        imageName = "ajmx"
        mainClass = mainClassName
        buildArgs.addAll("-march=compatibility", "-Os")
    }
}

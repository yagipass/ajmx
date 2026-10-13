import org.graalvm.buildtools.gradle.tasks.BuildNativeImageTask

description = "Integration tests of ajmx-cli; the target JVM they attach to runs on JDK 8 and later"

evaluationDependsOn(":cli")

dependencies {
    compileOnly(libs.errorprone.annotations)
    testCompileOnly(libs.errorprone.annotations)
    testImplementation(libs.jspecify)
    testImplementation(project(":cli"))
}

tasks.compileJava {
    options.release = 8
}

tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.add("-Xlint:-options")
}

sourceSets.test {
    compileClasspath -= sourceSets.main.get().output
    runtimeClasspath -= sourceSets.main.get().output
}

val native = providers.gradleProperty("native").isPresent

tasks.withType<Test>().configureEach {
    val targetClasses = sourceSets.main.get().output.classesDirs
    inputs.files(targetClasses).withPropertyName("targetClasses").withNormalizer(ClasspathNormalizer::class)
    jvmArgumentProviders.add(CommandLineArgumentProvider { listOf("-Dajmx.test.classpath=${targetClasses.asPath}") })

    providers.gradleProperty("ajmx.test.java").orNull?.let { systemProperty("ajmx.test.java", it) }

    if (native) {
        val binary = project(":cli").tasks.named<BuildNativeImageTask>("nativeCompile").flatMap { it.outputFile }
        inputs.file(binary).withPropertyName("ajmxBinary")
        jvmArgumentProviders.add(CommandLineArgumentProvider { listOf("-Dajmx.binary=${binary.get().asFile}") })
    }
}

tasks.test {
    useJUnitPlatform {
        excludeTags("perf")
    }
}

tasks.register<Test>("perfTest") {
    description = "Measures the memory and time of the native binary against fixed limits; needs -Pnative."
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform {
        includeTags("perf")
    }
    testLogging.showStandardStreams = true
    doFirst {
        check(native) { "perfTest measures the native binary; run it with -Pnative" }
    }
}

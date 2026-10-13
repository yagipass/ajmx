import com.diffplug.gradle.spotless.SpotlessExtension
import io.github.yagipass.memberorder.MemberOrderStep
import net.ltgt.gradle.errorprone.errorprone

buildscript {
    dependencies {
        classpath(libs.spotless.member.order)
    }
}

plugins {
    alias(libs.plugins.errorprone) apply false
    alias(libs.plugins.graalvm.native) apply false
    alias(libs.plugins.spotless) apply false
}

subprojects {
    apply(plugin = "java")
    apply(plugin = "net.ltgt.errorprone")
    apply(plugin = "com.diffplug.spotless")

    dependencies {
        "errorprone"(rootProject.libs.errorprone.core)
        "errorprone"(rootProject.libs.nullaway)
        "errorprone"(rootProject.libs.errorprone.tidy)
        "testImplementation"(platform(rootProject.libs.junit.bom))
        "testImplementation"(rootProject.libs.junit.jupiter)
        "testRuntimeOnly"(rootProject.libs.junit.platform.launcher)
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.release = 25
        options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
        options.errorprone {
            error(
                "DefaultLocale",
                "FinalClass",
                "InconsistentOverloads",
                "SuppressWarningsWithoutExplanation",
                "UngroupedOverloads",
                "UnusedException",
                "Var",
                "YodaCondition",
                "NullAway",
            )
            option("NullAway:OnlyNullMarked", true)
            option("NullAway:JSpecifyMode", true)
            errorproneArgs.addAll(
                providers.gradleProperty("errorprone.flags").map { it.split(' ').filter(String::isNotBlank) }.orElse(listOf())
            )
        }
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        systemProperty("ajmx.version", version.toString())
    }

    configure<SpotlessExtension> {
        java {
            addStep(
                MemberOrderStep.builder()
                    .order("T,SF,F,C,@BeforeSuite,@BeforeAll,@BeforeClass,@BeforeParameterizedClassInvocation,@BeforeEach,@Before,@AfterEach,@After,@AfterParameterizedClassInvocation,@AfterAll,@AfterClass,@AfterSuite,SM:B,M:B,SM:R,M:R,SM:D,M:D,SM:V,M:V")
                    .build()
            )
            googleJavaFormat().reorderImports(true)
            removeUnusedImports()
            trimTrailingWhitespace()
            endWithNewline()
        }
    }
}

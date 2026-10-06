rootProject.name = "ajmx"

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

include("cli", "it")
project(":cli").projectDir = file("modules/cli")
project(":it").projectDir = file("modules/it")

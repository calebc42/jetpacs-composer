pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        google()
    }
}

rootProject.name = "jetpacs-composer"

// Reproducible builds use the pinned Maven artifact. Fork development can
// substitute a local checkout without committing a machine-specific path:
//   gradlew test -PorgmodeKmpPath=/path/to/orgmode-kmp
val orgmodeKmpPath = providers.gradleProperty("orgmodeKmpPath")
    .orElse(providers.environmentVariable("ORGMODE_KMP_PATH"))
    .orNull
if (!orgmodeKmpPath.isNullOrBlank()) {
    val checkout = file(orgmodeKmpPath)
    require(checkout.resolve("settings.gradle.kts").isFile) {
        "orgmodeKmpPath does not point to an orgmode-kmp checkout: $checkout"
    }
    includeBuild(checkout) {
        dependencySubstitution {
            substitute(module("xyz.lepisma:orgmode")).using(project(":orgmode"))
        }
    }
}

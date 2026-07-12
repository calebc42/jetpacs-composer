plugins {
    kotlin("jvm") version "2.2.20"
    kotlin("plugin.serialization") version "2.2.20"
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.20"
    id("org.jetbrains.compose") version "1.9.3"
}

group = "com.calebc42.composer"
version = "0.1.0"

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    testImplementation(kotlin("test"))
}

compose.desktop {
    application {
        mainClass = "com.calebc42.composer.MainKt"
    }
}

// The elisp runtime ships inside the editor as resources: exporting an
// app bundle is template assembly over these exact sources, so the
// desktop app needs no Emacs at build or run time.
tasks.processResources {
    from(rootDir.resolve("elisp")) {
        into("runtime")
        include("jetpacs-crud.el", "jetpacs-crud-orgapp.el")
    }
}

tasks.test {
    useJUnitPlatform()
    // The contract tests parse the SAME fixture corpus the ERT suite
    // pins — one corpus, two consumers.
    systemProperty("fixtures.dir",
                   rootDir.resolve("elisp/test/fixtures").absolutePath)
    // Opt-in cross-check against the elisp reference builder's output:
    //   emacs -Q --batch -l elisp/build-app-bundle.el -- elisp/test/fixtures/pantry.org OUT
    //   gradlew test -PelispBundle=OUT/jetpacs-app-pantry.el
    systemProperty("elisp.bundle",
                   providers.gradleProperty("elispBundle").getOrElse(""))
}

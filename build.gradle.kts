import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm") version "2.2.20"
    kotlin("plugin.serialization") version "2.2.20"
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.20"
    id("org.jetbrains.compose") version "1.9.3"
}

group = "com.calebc42.composer"
version = "1.0.0"

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    // General org syntax/AST layer. Composer FORMAT semantics stay in the
    // adapter and legacy OrgCodec until shadow-parser parity is complete.
    // Upstream 0.4.1 is temporarily used to establish the dependency/API seam.
    // Its JVM artifact targets Java 21, so keep it off the packaged runtime
    // while establishing compile-time API containment. Promote to
    // implementation when the fork publishes a Java-17-compatible coordinate.
    compileOnly("xyz.lepisma:orgmode:0.4.1")
    testImplementation(kotlin("test"))
}

compose.desktop {
    application {
        mainClass = "com.calebc42.composer.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "jetpacs-composer"
            packageVersion = "1.0.0"

            val iconsRoot = project.file("src/main/resources/icons")
            windows {
                iconFile.set(iconsRoot.resolve("jetpacs-composer-logo.ico"))
            }
            macOS {
                iconFile.set(iconsRoot.resolve("jetpacs-composer-logo.icns"))
            }
            linux {
                iconFile.set(iconsRoot.resolve("jetpacs-composer-icon-forground.svg"))
            }
        }
    }
}

// The elisp runtime ships inside the editor as resources: exporting an
// app bundle is template assembly over these exact sources, so the
// desktop app needs no Emacs at build or run time.
tasks.processResources {
    from(rootDir.resolve("elisp")) {
        into("runtime")
        include("jetpacs-crud.el", "jetpacs-crud-vulpea.el", "jetpacs-crud-orgapp.el")
    }
    // The canonical kitchen-sink fixture doubles as the gallery's demo
    // template — one document, shared with the ERT suite verbatim.
    from(rootDir.resolve("elisp/test/fixtures")) {
        into("templates")
        include("hello-world.org")
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
    // Same cross-check for the pack-backed bundle (packdemo.org, built
    // beside its glasspane-pack.json):
    //   gradlew test -PelispPackBundle=OUT/jetpacs-app-packdemo.el
    systemProperty("elisp.pack.bundle",
                   providers.gradleProperty("elispPackBundle").getOrElse(""))
}

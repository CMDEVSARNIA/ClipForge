import org.jetbrains.compose.desktop.application.dsl.TargetFormat

// ClipForge for Windows (and later macOS): Compose Desktop UI on the shared :core logic,
// FFmpeg for decoding, preview and export.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.multiplatform)
}

dependencies {
    implementation(project(":core"))
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(libs.kotlinx.coroutines.swing)
    testImplementation(libs.junit)
    testImplementation(compose.desktop.uiTestJUnit4)
}

compose.desktop {
    application {
        mainClass = "com.clipforge.desktop.MainKt"
        nativeDistributions {
            // jdk.crypto.ec: TLS to the Worker; java.instrument/jdk.unsupported: suggested by suggestRuntimeModules.
            modules("java.instrument", "jdk.unsupported", "jdk.crypto.ec", "java.naming")
            // FFmpeg goes in appResources/<os>-<arch>/ffmpeg (downloaded by CI, not committed).
            appResourcesRootDir.set(layout.projectDirectory.dir("appResources"))
            targetFormats(TargetFormat.Msi, TargetFormat.Deb)
            packageName = "ClipForge"
            packageVersion = "0.10.0"
            description = "Long gameplay in. Vertical clips out."
            vendor = "ClipForge"
            windows {
                iconFile.set(project.file("icons/clipforge.ico"))
                menuGroup = "ClipForge"
                upgradeUuid = "6f1b7c52-3f0e-4d8e-9a57-2c7f4f4f2a10"
                shortcut = true
                dirChooser = true
            }
        }
    }
}

tasks.test {
    // ./gradlew :desktop:test -Pclipforge.shots=/some/dir saves UI screenshots from DesktopUiTest.
    providers.gradleProperty("clipforge.shots").orNull?.let { systemProperty("clipforge.shots", it) }
}

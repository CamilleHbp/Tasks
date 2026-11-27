import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.multiplatform)
}

kotlin { jvmToolchain(21) }

val windows = System.getProperty("os.name").startsWith("Windows")
val mac = System.getProperty("os.name").startsWith("Mac")
val helpers = layout.buildDirectory.dir("generated/resources/common")
val helperName = if (windows) "vervetwodo-helper.exe" else "vervetwodo-helper"

val buildHelper = tasks.register<Exec>("buildHelper") {
    inputs.files(fileTree("../desktop-helper") { include("**/*.go", "go.mod", "go.sum") })
    inputs.files(fileTree("../sync-engine") { include("**/*.go", "go.mod", "go.sum") })
    environment("GOWORK", "off")
    environment("GOPRIVATE", "github.com/CamilleHbp/sync-core")
    outputs.file(helpers.map { it.file(helperName) })
    workingDir("../desktop-helper")
    doFirst { helpers.get().asFile.mkdirs() }
    commandLine("go", "build", "-trimpath", "-ldflags=-s -w", "-o", helpers.get().file(helperName).asFile, ".")
}

val buildMacBridge = tasks.register<Exec>("buildMacBridge") {
    onlyIf { mac }
    inputs.files(fileTree("../native/macos/Sources/Bridge"))
    outputs.file(helpers.map { it.file("VerveNative") })
    doFirst { helpers.get().asFile.mkdirs() }
    commandLine("swiftc", "-parse-as-library", "-target",
        "${if (System.getProperty("os.arch") == "aarch64") "arm64" else "x86_64"}-apple-macos14.0",
        "-module-cache-path", layout.buildDirectory.dir("swift-cache").get().asFile,
        "../native/macos/Sources/Bridge/Bridge.swift", "-o", helpers.get().file("VerveNative").asFile)
}

val notificationJdk = javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) }
val buildMacNotifications = tasks.register<Exec>("buildMacNotifications") {
    onlyIf { mac }
    inputs.file("../native/macos/Sources/Notifications/Notifications.m")
    outputs.file(helpers.map { it.file("libVerveNotifications.dylib") })
    doFirst {
        helpers.get().asFile.mkdirs()
        val includes = notificationJdk.get().metadata.installationPath.dir("include").asFile
        commandLine("clang", "-fobjc-arc", "-dynamiclib", "-Wall", "-Werror", "-target",
            "${if (System.getProperty("os.arch") == "aarch64") "arm64" else "x86_64"}-apple-macos14.0",
            "-I${includes.absolutePath}", "-I${includes.resolve("darwin").absolutePath}",
            "-framework", "Foundation", "-framework", "UserNotifications", "-framework", "AppKit",
            "../native/macos/Sources/Notifications/Notifications.m", "-o", helpers.get().file("libVerveNotifications.dylib").asFile)
    }
}

dependencies {
    implementation(project(":shared"))
    implementation(project(":desktopIntegration"))
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(libs.markdown.renderer)
    implementation(libs.markdown.material3)
    testImplementation(compose.desktop.uiTestJUnit4)
    testImplementation(libs.junit)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:${libs.versions.kotlinCoroutines.get()}")
}

compose.desktop {
    application {
        mainClass = "studio.camille.vervetwodo.desktop.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "VerveTwoDo"
            packageVersion = "1.0.24"
            description = "VerveTwoDo task manager"
            vendor = "Camille"
            appResourcesRootDir.set(layout.buildDirectory.dir("generated/resources"))
            modules("java.desktop", "java.logging", "java.net.http", "jdk.unsupported", "jdk.localedata")
            macOS {
                bundleID = "studio.camille.vervetwodo.preview"
                minimumSystemVersion = "14.0"
                infoPlist {
                    extraKeysRawXml = """
                        <key>CFBundleURLTypes</key><array><dict>
                        <key>CFBundleURLName</key><string>VerveTwoDo</string>
                        <key>CFBundleURLSchemes</key><array><string>vervetwodo-preview</string></array>
                        </dict></array>
                    """.trimIndent()
                }
            }
            windows {
                menuGroup = "VerveTwoDo"
                upgradeUuid = "2d070fde-d40f-4a2b-9bd9-cb50fdb4dd89"
            }
        }
    }
}

tasks.matching { it.name in setOf("run", "createDistributable", "prepareAppResources") }.configureEach {
    dependsOn(buildHelper, buildMacBridge, buildMacNotifications)
}

tasks.matching { it.name == "createDistributable" }.configureEach {
    // Compose's packaging task otherwise remains up-to-date when only the Go
    // engine/native helpers change, shipping an older sync implementation.
    inputs.dir(layout.buildDirectory.dir("generated/resources"))
    doLast {
        if (mac) {
            val resources = layout.buildDirectory.dir("compose/binaries/main/app/VerveTwoDo.app/Contents/app/resources").get().asFile
            listOf("vervetwodo-helper", "VerveNative").forEach { name ->
                check(resources.resolve(name).setExecutable(true, false)) { "Could not make $name executable" }
            }
            // Cocoa services identify their caller through a proper Contents/MacOS bundle path.
            val bridge = resources.parentFile.parentFile.resolve("MacOS/VerveNative")
            resources.resolve("VerveNative").copyTo(bridge, overwrite = true)
            check(bridge.setExecutable(true, false))
        }
    }
}

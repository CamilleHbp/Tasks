plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin { jvmToolchain(21) }

dependencies {
    implementation(project(":shared"))
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
}

tasks.test {
    dependsOn("buildSyncTestServer")
    dependsOn(":desktopApp:buildHelper")
    val suffix = if (System.getProperty("os.name").startsWith("Windows")) ".exe" else ""
    systemProperty("vervetwodo.test.helper", rootProject.layout.projectDirectory.file("desktopApp/build/generated/resources/common/vervetwodo-helper$suffix").asFile.absolutePath)
    systemProperty("vervetwodo.test.server", layout.buildDirectory.file("sync-test/server$suffix").get().asFile.absolutePath)
}

tasks.register<Exec>("buildSyncTestServer") {
    val suffix = if (System.getProperty("os.name").startsWith("Windows")) ".exe" else ""
    val output = layout.buildDirectory.file("sync-test/server$suffix")
    inputs.files(fileTree("../sync-engine") { include("**/*.go", "go.mod", "go.sum") })
    outputs.file(output)
    workingDir("../sync-engine")
    environment("GOWORK", "off")
    environment("GOPRIVATE", "github.com/CamilleHbp/sync-core")
    doFirst { output.get().asFile.parentFile.mkdirs() }
    commandLine("go", "build", "-o", output.get().asFile.absolutePath, "./cmd/server")
}

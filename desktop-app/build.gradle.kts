plugins {
    kotlin("jvm")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
}

val stageJamalResources by tasks.registering(Copy::class) {
    dependsOn("buildRenderEngine")
    from(rootProject.file("render-engine/build/jamal-render-engine"))
    from(rootProject.file("models/modnet_photographic.onnx"))
    into(layout.buildDirectory.dir("jamal-resources/common"))
}

compose.desktop {
    application {
        mainClass = "com.jamal.app.MainKt"

        nativeDistributions {
            appResourcesRootDir.set(layout.buildDirectory.dir("jamal-resources"))
            targetFormats(
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Dmg,
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Msi,
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Deb,
            )
            packageName = "Jamal Video Compositor"
            packageVersion = "1.0.0"
        }
    }
}

tasks.register<Exec>("buildRenderEngine") {
    group = "build"
    description = "Configures and builds the native Jamal render engine with CMake."
    workingDir(rootProject.projectDir)
    commandLine("cmake", "--build", "render-engine/build", "--parallel")
}

tasks.matching { it.name == "prepareAppResources" }.configureEach {
    dependsOn(stageJamalResources)
}

tasks.register<Exec>("verifyRenderSmoke") {
    group = "verification"
    description = "Renders a short fixture and verifies the exported video and audio streams."
    dependsOn("buildRenderEngine")
    workingDir(rootProject.projectDir)
    commandLine("./tests/render-smoke.sh")
}

tasks.withType<JavaExec>().configureEach {
    workingDir(rootProject.projectDir)
}

plugins {
    kotlin("jvm")
    application
}

kotlin {
    jvmToolchain(26)
}

application {
    mainClass.set("com.jamal.web.MainKt")
}

tasks.withType<JavaExec>().configureEach {
    workingDir(rootProject.projectDir)
    System.getProperty("jamal.exports.dir")?.let { systemProperty("jamal.exports.dir", it) }
}

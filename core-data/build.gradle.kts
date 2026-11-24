import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Pure Kotlin/JVM so the offline log decoder can run on a PC
// (`./gradlew :core-data:run --args="decode file.jsonl.gz"`, added with the decoder).
// Vehicle profiles will live in src/main/resources/vehicles/<profile-id>/:
// JVM resources are packaged into the APK too, so the same files serve both.
plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

application {
    mainClass.set("com.hybridguard.data.DecodeToolKt")
}

// Resolve file arguments against the directory gradlew is run from (the repo root).
tasks.named<JavaExec>("run") {
    workingDir = rootProject.projectDir
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    api(project(":core-obd"))
    // JSON tree API only (no compiler plugin): OBDb files are parsed by hand
    // so every optional field and default matches the OBDb reference decoder.
    implementation(libs.kotlinx.serialization.json)

    testImplementation(kotlin("test-junit"))
    testImplementation(libs.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
}

tasks.test {
    useJUnit()
}

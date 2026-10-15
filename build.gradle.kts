@file:OptIn(
    org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class,
    org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation::class,
)

import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.targets.jvm.KotlinJvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.dokka)
    alias(libs.plugins.ktlint)
    `maven-publish`
}

group = "dev.skaldebane"
version = "0.1.0-dev.1"

kotlin {
    explicitApi()
    abiValidation()
    jvmToolchain(17)

    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    android {
        namespace = "dev.skaldebane.kttf"
        compileSdk = 36
        minSdk = 21

        withHostTest {}
    }

    js {
        browser()
        nodejs()
    }

    wasmJs {
        browser()
        nodejs()
    }

    wasmWasi {
        nodejs()
    }

    linuxX64()
    linuxArm64()
    mingwX64()

    macosArm64()

    iosArm64()
    iosSimulatorArm64()
    iosX64()

    tvosArm64()
    tvosSimulatorArm64()

    watchosArm32()
    watchosArm64()
    watchosSimulatorArm64()
    watchosDeviceArm64()

    androidNativeArm32()
    androidNativeArm64()
    androidNativeX86()
    androidNativeX64()

    sourceSets {
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

ktlint {
    version.set(libs.versions.ktlint.asProvider())
}

tasks.withType<Test>().configureEach {
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

publishing {
    repositories {
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/skaldebane/kttf")

            credentials {
                username = providers.environmentVariable("GITHUB_ACTOR").orNull
                password = providers.environmentVariable("GITHUB_TOKEN").orNull
            }
        }
    }

    publications.withType<MavenPublication>().configureEach {
        pom {
            name.set("kttf")
            description.set("A Kotlin-first, multiplatform OpenType font parser.")
            url.set("https://github.com/skaldebane/kttf")

            licenses {
                license {
                    name.set("The Apache License, Version 2.0")
                    url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                    distribution.set("repo")
                }
            }

            developers {
                developer {
                    id.set("skaldebane")
                    name.set("Houssam Elbadissi")
                    url.set("https://skaldebane.dev")
                }
            }

            scm {
                connection.set("scm:git:git://github.com/skaldebane/kttf.git")
                developerConnection.set("scm:git:ssh://git@github.com/skaldebane/kttf.git")
                url.set("https://github.com/skaldebane/kttf")
            }
        }
    }
}

val oracleDirectory = layout.projectDirectory.dir("tools/ttf-parser-oracle")
val jvmTestCompilation =
    kotlin.targets
        .getByName<KotlinJvmTarget>("jvm")
        .compilations
        .getByName("test")

tasks.register<Exec>("buildTtfParserOracle") {
    group = "verification"
    description = "Builds the pinned Rust ttf-parser differential oracle."
    workingDir(oracleDirectory)
    commandLine("cargo", "build", "--locked")
}

tasks.register<Exec>("verifyTtfParserOracle") {
    group = "verification"
    description = "Verifies checked cmap output against the pinned Rust implementation."
    dependsOn("buildTtfParserOracle")
    workingDir(oracleDirectory)
    commandLine(
        "cargo",
        "run",
        "--locked",
        "--quiet",
        "--",
        "fixtures/cmap-basic.hex",
        "--verify",
        "fixtures/cmap-basic.oracle",
    )
}

tasks.register<JavaExec>("verifyCmapDifferential") {
    group = "verification"
    description = "Compares kttf and the pinned Rust parser on checked and generated font fixtures."
    dependsOn("buildTtfParserOracle", "jvmTestClasses")
    classpath(jvmTestCompilation.output.allOutputs, jvmTestCompilation.runtimeDependencyFiles)
    mainClass.set("dev.skaldebane.kttf.CmapDifferentialKt")
    args(layout.projectDirectory.asFile.absolutePath)
}

import java.net.URI
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

kotlin {
    listOf(
        iosArm64(),
        iosSimulatorArm64()
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "Shared"
            isStatic = true
        }
    }

    android {
       namespace = "kc.ac.uc.speechbridge.shared"
       compileSdk = libs.versions.android.compileSdk.get().toInt()
       minSdk = libs.versions.android.minSdk.get().toInt()

       compilerOptions {
           jvmTarget = JvmTarget.JVM_11
       }
       androidResources {
           enable = true
       }
       withHostTest {
           isIncludeAndroidResources = true
       }
       withDeviceTestBuilder {
           sourceSetTreeName = "test"
       }.configure {
           instrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
       }
    }

    sourceSets {
        androidMain.dependencies {
            implementation("io.ktor:ktor-client-android:${libs.versions.ktor.get()}")
            implementation(libs.compose.uiToolingPreview)
            implementation(libs.compose.uiTooling)
        }
        iosMain.dependencies {
            implementation("io.ktor:ktor-client-darwin:${libs.versions.ktor.get()}")
        }
        commonMain.dependencies {
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:${libs.versions.serialization.get()}")
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:${libs.versions.coroutines.get()}")
            implementation("io.ktor:ktor-client-core:${libs.versions.ktor.get()}")
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.compose.ui)
            implementation(libs.compose.components.resources)
            implementation(libs.compose.uiToolingPreview)
            implementation(libs.androidx.lifecycle.viewmodelCompose)
            implementation(libs.androidx.lifecycle.runtimeCompose)
        }
        getByName("androidDeviceTest").dependencies {
            implementation(libs.androidx.testExt.junit)
            implementation("androidx.test:runner:1.7.0")
            implementation(libs.kotlin.test)
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:${libs.versions.coroutines.get()}")
        }
        commonTest.dependencies {
            implementation("io.ktor:ktor-client-mock:${libs.versions.ktor.get()}")
            implementation(libs.kotlin.test)
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:${libs.versions.coroutines.get()}")
        }
    }
}

dependencies {
    androidRuntimeClasspath(libs.compose.uiTooling)
}

// Public service address only; never put credentials in the app binary.
val serviceUrl = providers.gradleProperty("speechbridge.serverUrl")
    .orElse(providers.environmentVariable("SPEECHBRIDGE_SERVER_URL")).orElse("")
val serviceConfigDir = layout.buildDirectory.dir("generated/serviceConfig")
val generateServiceConfig by tasks.registering {
    inputs.property("serviceUrl", serviceUrl)
    outputs.dir(serviceConfigDir)
    val address = serviceUrl
    val output = serviceConfigDir
    doLast {
        val raw = address.get().trim().trimEnd('/')
        if (raw.isNotEmpty()) {
            val uri = URI(raw)
            require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null &&
                uri.query == null && uri.fragment == null && (uri.path.isNullOrEmpty() || uri.path == "/")) {
                "speechbridge.serverUrl must be an HTTPS origin, without credentials, path, query or fragment"
            }
        }
        val file = output.get().file("kc/ac/uc/speechbridge/ServiceConfig.kt").asFile
        file.parentFile.mkdirs()
        file.writeText("package kc.ac.uc.speechbridge\ninternal const val SERVICE_URL: String = \"${raw.replace("$", "\\$")}\"\n")
    }
}
kotlin.sourceSets.commonMain { kotlin.srcDir(files(serviceConfigDir).builtBy(generateServiceConfig)) }

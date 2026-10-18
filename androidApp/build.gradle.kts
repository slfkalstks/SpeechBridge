import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeCompiler)
}

abstract class GenerateDebugSecurity : DefaultTask() {
    @get:Input abstract val localTls: Property<Boolean>
    @get:InputFile @get:Optional abstract val certificate: RegularFileProperty
    @get:OutputDirectory abstract val outputDirectory: DirectoryProperty
    @TaskAction fun generate() {
        val root=outputDirectory.get().asFile
        val xml=root.resolve("xml/generated_debug_network_security.xml")
        xml.parentFile.mkdirs()
        val raw=root.resolve("raw/speechbridge_test_ca.der")
        if(localTls.get()) {raw.parentFile.mkdirs();raw.writeBytes(certificate.get().asFile.readBytes())} else raw.delete()
        val anchors=if(localTls.get()) "<debug-overrides><trust-anchors><certificates src=\"@raw/speechbridge_test_ca\" /></trust-anchors></debug-overrides>" else ""
        xml.writeText("""<?xml version="1.0" encoding="utf-8"?>
            <network-security-config>
                <base-config cleartextTrafficPermitted="false" />
                <domain-config cleartextTrafficPermitted="true"><domain>localhost</domain><domain>127.0.0.1</domain><domain>10.0.2.2</domain></domain-config>
                $anchors
            </network-security-config>
        """.trimIndent())
    }
}

val generateDebugSecurity=tasks.register<GenerateDebugSecurity>("generateDebugSecurity") {
    localTls.set(providers.gradleProperty("speechbridge.localTls").map {it.toBooleanStrict()}.orElse(false))
    if(localTls.get()) certificate.set(rootProject.layout.projectDirectory.file(".runtime/tls/ca.der"))
    outputDirectory.set(layout.buildDirectory.dir("generated/res/debugSecurity"))
}
androidComponents.onVariants(androidComponents.selector().withBuildType("debug")) { variant ->
    variant.sources.res?.addGeneratedSourceDirectory(generateDebugSecurity,GenerateDebugSecurity::outputDirectory)
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_11
    }
}
dependencies {
    implementation(project(":shared"))

    implementation(libs.androidx.activity.compose)

    implementation(libs.compose.uiToolingPreview)
    debugImplementation(libs.compose.uiTooling)
}

android {
    namespace = "kc.ac.uc.speechbridge"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "kc.ac.uc.speechbridge"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 1
        versionName = "1.0"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
}

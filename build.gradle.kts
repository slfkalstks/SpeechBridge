plugins {
    // this is necessary to avoid the plugins to be loaded multiple times
    // in each subproject's classloader
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.androidMultiplatformLibrary) apply false
    alias(libs.plugins.composeMultiplatform) apply false
    alias(libs.plugins.composeCompiler) apply false
    alias(libs.plugins.kotlinMultiplatform) apply false
}

subprojects {
    val localTls=providers.gradleProperty("speechbridge.localTls").map { it.toBooleanStrict() }.orElse(false)
    val verifyProductionTransport=tasks.register("verifyProductionTransport") {
        doLast { check(!localTls.get()) { "Local TLS trust is for Debug demonstrations only; disable speechbridge.localTls for Release." } }
    }
    tasks.configureEach { if(name.contains("Release",ignoreCase=true)) dependsOn(verifyProductionTransport) }
}

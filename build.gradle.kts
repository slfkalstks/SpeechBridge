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
    val productionOrigin=providers.gradleProperty("speechbridge.serverUrl")
        .orElse(providers.environmentVariable("SPEECHBRIDGE_SERVER_URL")).orElse("")
    val verifyProductionTransport=tasks.register("verifyProductionTransport") {
        doLast {
            check(!localTls.get()) { "Local TLS trust is for Debug demonstrations only; disable speechbridge.localTls for Release." }
            val uri=runCatching { java.net.URI(productionOrigin.get()) }.getOrNull()
            check(uri?.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.query == null && uri.fragment == null && (uri.path.isNullOrEmpty() || uri.path == "/")) {
                "Release requires speechbridge.serverUrl to be an explicit HTTPS origin without credentials."
            }
        }
    }
    tasks.configureEach { if(name.contains("Release",ignoreCase=true)) dependsOn(verifyProductionTransport) }
}

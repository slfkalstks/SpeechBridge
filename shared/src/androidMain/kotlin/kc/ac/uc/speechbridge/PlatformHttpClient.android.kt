package kc.ac.uc.speechbridge

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig

// Android's Debug-only Network Security Configuration owns test CA trust.
internal actual fun platformHttpClient(config:HttpClientConfig<*>.()->Unit):HttpClient = HttpClient(config)

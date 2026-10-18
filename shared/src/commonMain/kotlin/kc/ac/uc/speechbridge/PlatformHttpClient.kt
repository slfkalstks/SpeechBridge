package kc.ac.uc.speechbridge

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig

internal expect fun platformHttpClient(config:HttpClientConfig<*>.()->Unit):HttpClient

@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class,kotlin.experimental.ExperimentalNativeApi::class)
package kc.ac.uc.speechbridge

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.darwin.Darwin
import io.ktor.http.Url
import kotlinx.cinterop.*
import platform.CoreFoundation.*
import platform.Foundation.*
import platform.Security.*
import kotlin.io.encoding.Base64

internal actual fun platformHttpClient(config:HttpClientConfig<*>.()->Unit):HttpClient = HttpClient(Darwin) {
    config(this)
    if(kotlin.native.Platform.isDebugBinary && LOCAL_TLS_CA_BASE64.isNotEmpty()) {
        val host=Url(SERVICE_URL).host
        val ca=Base64.decode(LOCAL_TLS_CA_BASE64)
        engine {
            handleChallenge { _,_,challenge,complete ->
                val space=challenge.protectionSpace
                if(space.authenticationMethod != NSURLAuthenticationMethodServerTrust || space.host != host) {
                    complete(NSURLSessionAuthChallengePerformDefaultHandling,null)
                } else {
                    val trust=space.serverTrust
                    if(trust != null && evaluateLocalTlsTrust(trust,host,ca)) complete(NSURLSessionAuthChallengeUseCredential,NSURLCredential.create(trust))
                    else complete(NSURLSessionAuthChallengeCancelAuthenticationChallenge,null)
                }
            }
        }
    }
}

/** Adds this app's test CA without bypassing the hostname, signature or expiry policy. */
internal fun evaluateLocalTlsTrust(trust:SecTrustRef,host:String,caBytes:ByteArray):Boolean {
    if(caBytes.isEmpty() || host.isEmpty())return false
    val data=caBytes.usePinned {CFDataCreate(null,it.addressOf(0).reinterpret(),caBytes.size.toLong())} ?: return false
    val certificate=SecCertificateCreateWithData(null,data)
    CFRelease(data)
    if(certificate == null)return false
    val roots=CFArrayCreateMutable(null,0,kCFTypeArrayCallBacks.ptr)!!
    val name=CFStringCreateWithCString(null,host,kCFStringEncodingUTF8)!!
    val policy=SecPolicyCreateSSL(true,name)!!
    try {
        CFArrayAppendValue(roots,certificate)
        if(SecTrustSetPolicies(trust,policy)!=errSecSuccess || SecTrustSetAnchorCertificates(trust,roots)!=errSecSuccess ||
            SecTrustSetAnchorCertificatesOnly(trust,true)!=errSecSuccess || SecTrustSetNetworkFetchAllowed(trust,false)!=errSecSuccess)return false
        return SecTrustEvaluateWithError(trust,null)
    }finally{CFRelease(policy);CFRelease(name);CFRelease(roots);CFRelease(certificate)}
}

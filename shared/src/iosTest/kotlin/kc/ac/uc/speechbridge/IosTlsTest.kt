@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package kc.ac.uc.speechbridge

import kotlinx.cinterop.*
import platform.CoreFoundation.*
import platform.Security.*
import kotlin.io.encoding.Base64
import kotlin.test.*

class IosTlsTest {
    private fun trust(date:Double=TlsTestCertificates.DATE, block:(SecTrustRef)->Unit)=memScoped {
        val bytes=Base64.decode(TlsTestCertificates.LEAF)
        val data=bytes.usePinned { CFDataCreate(null,it.addressOf(0).reinterpret(),bytes.size.toLong()) }!!
        val leaf=SecCertificateCreateWithData(null,data)!!
        val output=alloc<SecTrustRefVar>();output.value=null
        val policy=SecPolicyCreateBasicX509()!!
        try {
            assertEquals(errSecSuccess,SecTrustCreateWithCertificates(leaf,policy,output.ptr))
            val verifyDate=CFDateCreate(null,date-978307200.0)!!
            try {SecTrustSetVerifyDate(output.value,verifyDate);block(output.value!!)} finally {CFRelease(verifyDate)}
        }finally{output.value?.let{CFRelease(it)};CFRelease(policy);CFRelease(leaf);CFRelease(data)}
    }
    @Test fun projectCaStillRequiresValidHostAndDates() {
        trust { assertTrue(evaluateLocalTlsTrust(it,"127.0.0.1",Base64.decode(TlsTestCertificates.CA))) }
        trust { assertFalse(evaluateLocalTlsTrust(it,"unrelated.invalid",Base64.decode(TlsTestCertificates.CA))) }
        trust(TlsTestCertificates.DATE+400*86400) { assertFalse(evaluateLocalTlsTrust(it,"127.0.0.1",Base64.decode(TlsTestCertificates.CA))) }
        trust { assertFalse(evaluateLocalTlsTrust(it,"127.0.0.1",byteArrayOf(1,2,3))) }
    }
}

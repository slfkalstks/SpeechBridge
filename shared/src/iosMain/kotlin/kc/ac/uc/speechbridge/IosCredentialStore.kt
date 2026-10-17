@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package kc.ac.uc.speechbridge

import kotlinx.cinterop.*
import platform.CoreFoundation.*
import platform.Security.*

/** Foreground-only, device-bound Keychain item; never part of the conversation archive. */
class IosCredentialStore(private val serviceName: String = "kc.ac.uc.speechbridge.account") : CredentialStore {
    private fun <T> query(block: (CFMutableDictionaryRef) -> T): T {
        val dictionary = CFDictionaryCreateMutable(null, 0, kCFTypeDictionaryKeyCallBacks.ptr, kCFTypeDictionaryValueCallBacks.ptr)!!
        val service = CFStringCreateWithCString(null, serviceName, kCFStringEncodingUTF8)!!
        val account = CFStringCreateWithCString(null, "session-v1", kCFStringEncodingUTF8)!!
        try {
            CFDictionarySetValue(dictionary, kSecClass, kSecClassGenericPassword)
            CFDictionarySetValue(dictionary, kSecAttrService, service)
            CFDictionarySetValue(dictionary, kSecAttrAccount, account)
            return block(dictionary)
        } finally { CFRelease(dictionary); CFRelease(service); CFRelease(account) }
    }
    override suspend fun read(): String? = query { dictionary -> memScoped {
        CFDictionarySetValue(dictionary, kSecReturnData, kCFBooleanTrue)
        CFDictionarySetValue(dictionary, kSecMatchLimit, kSecMatchLimitOne)
        val result = alloc<CFTypeRefVar>()
        result.value = null
        val status = SecItemCopyMatching(dictionary, result.ptr)
        if (status == errSecItemNotFound) return@memScoped null
        check(status == errSecSuccess) { "Keychain read failed" }
        val data: CFDataRef = result.value!!.reinterpret()
        try { CFDataGetBytePtr(data)!!.readBytes(CFDataGetLength(data).toInt()).decodeToString() }
        finally { CFRelease(data) }
    } }
    override suspend fun write(value: String?) {
        query { dictionary ->
            if (value == null) {
                val status = SecItemDelete(dictionary)
                check(status == errSecSuccess || status == errSecItemNotFound) { "Keychain deletion failed" }
            } else {
                val bytes = value.encodeToByteArray()
                val data = bytes.usePinned { CFDataCreate(null, it.addressOf(0).reinterpret(), bytes.size.toLong()) }!!
                val attributes = CFDictionaryCreateMutable(null, 0, kCFTypeDictionaryKeyCallBacks.ptr, kCFTypeDictionaryValueCallBacks.ptr)!!
                try {
                    CFDictionarySetValue(attributes, kSecValueData, data)
                    CFDictionarySetValue(attributes, kSecAttrAccessible, kSecAttrAccessibleWhenUnlockedThisDeviceOnly)
                    var status = SecItemUpdate(dictionary, attributes)
                    if (status == errSecItemNotFound) {
                        CFDictionarySetValue(dictionary, kSecValueData, data)
                        CFDictionarySetValue(dictionary, kSecAttrAccessible, kSecAttrAccessibleWhenUnlockedThisDeviceOnly)
                        status = SecItemAdd(dictionary, null)
                    }
                    check(status == errSecSuccess) { "Keychain write failed" }
                } finally { CFRelease(attributes); CFRelease(data) }
            }
        }
    }
}

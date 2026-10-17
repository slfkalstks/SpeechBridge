package kc.ac.uc.speechbridge

import kotlinx.coroutines.test.runTest
import kotlin.test.*

class ProductionConnectionTest {
    @Test fun productionDoesNotAskForDeveloperCredentialsOrContactLocalhost() = runTest {
        val api = HttpBridgeApi()
        try {
            val error = assertFailsWith<BridgeRequestException> {
                api.translate(Connection(), TranslationRequest("hello", "en", "ko", 1))
            }
            assertEquals("service_pending", error.code)
            assertFalse(error.message!!.contains("토큰"))
            assertFalse(error.message!!.contains("Mac"))
        } finally { api.close() }
    }
}

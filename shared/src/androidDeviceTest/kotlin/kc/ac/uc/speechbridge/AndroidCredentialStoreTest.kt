package kc.ac.uc.speechbridge

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyStore
import kotlin.test.*

@RunWith(AndroidJUnit4::class)
class AndroidCredentialStoreTest {
    @Test fun encryptedStorageReopensRejectsTamperingAndClears() = runTest {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "credential_test_${System.nanoTime()}"
        val preferences = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        val store = AndroidCredentialStore(context, name)
        val synthetic = "synthetic-session-for-keystore-test"
        try {
            assertNull(store.read())
            store.write(synthetic)
            val encrypted = preferences.getString("session", null)!!
            assertFalse(encrypted.contains(synthetic))
            assertEquals(synthetic, AndroidCredentialStore(context, name).read())
            preferences.edit().putString("session", encrypted.dropLast(4) + "AAAA").commit()
            assertFails { store.read() }
            store.write(null)
            assertNull(store.read())
        } finally {
            context.deleteSharedPreferences(name)
            KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry("speechbridge.account.v1.$name") }
        }
    }
}

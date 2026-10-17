@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package kc.ac.uc.speechbridge

import kotlinx.coroutines.test.runTest
import platform.Foundation.*
import kotlin.test.*

class IosStorageTest {
    @Test fun foundationStoreWritesReadsAndReopensWithoutTouchingRealArchive() = runTest {
        val name = "SpeechBridgeTest-${newId()}"
        val store = IosSessionStore(name)
        try {
            assertNull(store.read())
            val c = Conversation(title = "가상 검증 日本語", translate = false, record = true, first = Language.KO, second = Language.JA,
                utterances = listOf(Utterance(speaker = "검증", language = Language.KO, originalText = "금요일까지 확인해 주세요.")))
            store.write(ArchiveCodec.encode(listOf(c)))
            val reopened = IosSessionStore(name)
            val loaded = ArchiveCodec.decode(reopened.read()!!).single()
            assertEquals(c.title, loaded.title)
            assertEquals(c.utterances, loaded.utterances)
            assertTrue(loaded.ended)
            reopened.write(ArchiveCodec.encode(emptyList()))
            assertTrue(ArchiveCodec.decode(store.read()!!).isEmpty())
        } finally {
            val root = NSFileManager.defaultManager.URLForDirectory(NSApplicationSupportDirectory, NSUserDomainMask, null, false, null)
            root?.URLByAppendingPathComponent(name, true)?.let { NSFileManager.defaultManager.removeItemAtURL(it, null) }
        }
    }
}

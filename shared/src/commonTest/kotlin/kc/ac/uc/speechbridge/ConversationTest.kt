package kc.ac.uc.speechbridge

import kotlin.test.*

class ConversationTest {
    private val u = Utterance(id = "u", speaker = "민수", language = Language.KO, originalText = "내일 보고서를 보내 주세요.", translation = "Send the report tomorrow.", translationRevision = 1)
    private fun conversation(record: Boolean = true) = Conversation(id = "c", title = "회의", translate = true, record = record,
        first = Language.KO, second = Language.EN, utterances = listOf(u), items = listOf(ActionItem(id = "i", sourceId = "u", sourceRevision = 1, kind = ItemKind.REQUEST, text = "보고서 보내기", reviewed = true)))

    @Test fun correctingSourceKeepsOriginalAndInvalidatesTranslationAndReview() {
        val edited = conversation().editUtterance("u", "모레 보고서를 보내 주세요.")
        assertEquals(u.originalText, edited.utterances.single().originalText)
        assertEquals(2, edited.utterances.single().revision)
        assertNull(edited.utterances.single().translation)
        assertTrue(edited.needsReview(edited.items.single()))
        assertEquals(1, edited.items.single().sourceRevision)
        assertFailsWith<IllegalArgumentException> { edited.setTaskStatus("i", TaskStatus.TODO) }
        val reviewed = edited.reviewItem("i").setTaskStatus("i", TaskStatus.TODO)
        assertEquals(2, reviewed.items.single().sourceRevision)
        assertEquals(TaskStatus.TODO, reviewed.items.single().status)
    }
    @Test fun editedCompletedItemNeedsReviewAndCannotBeCompletedAgain() {
        val done = conversation().setTaskStatus("i", TaskStatus.DONE)
        val edited = done.editItem("i", "초안 먼저 보내기", "민수", "내일")
        assertTrue(edited.needsReview(edited.items.single()))
        assertEquals(TaskStatus.DONE, edited.items.single().status)
        assertFailsWith<IllegalArgumentException> { edited.setTaskStatus("i", TaskStatus.DONE) }
    }
    @Test fun staleTranslationCannotReplaceCorrectedText() {
        val c = conversation().editUtterance("u", "수정한 내용")
        assertNull(c.translateResult("u", 1, "old").utterances.single().translation)
        assertEquals("new", c.translateResult("u", 2, "new").utterances.single().translation)
    }
    @Test fun recordingOffExcludesWholeSessionAndCannotBecomePermanentTask() {
        val off = conversation(false).copy(id = "private", title = "NEVER_PERSIST")
        val encoded = ArchiveCodec.encode(listOf(conversation(), off))
        assertFalse(encoded.contains("NEVER_PERSIST"))
        assertEquals(1, ArchiveCodec.decode(encoded).size)
        assertFailsWith<IllegalArgumentException> { off.setTaskStatus("i", TaskStatus.TODO) }
    }
    @Test fun archiveRoundTripClosesInterruptedSessionAndPreservesEvidence() {
        val loaded = ArchiveCodec.decode(ArchiveCodec.encode(listOf(conversation()))).single()
        assertTrue(loaded.ended)
        assertEquals(conversation().utterances, loaded.utterances)
        assertEquals(conversation().items, loaded.items)
    }
    @Test fun malformedAndFutureArchivesFailInsteadOfReturningEmpty() {
        assertFails { ArchiveCodec.decode("not json") }
        assertFails { ArchiveCodec.decode("{}") }
        assertFails { ArchiveCodec.decode("""{"version":999,"conversations":[]}""") }
        val broken = conversation().copy(items = listOf(conversation().items.single().copy(sourceId = "missing")))
        assertFails { ArchiveCodec.decode(ArchiveCodec.encode(listOf(broken))) }
    }
    @Test fun urlValidationRejectsCredentialLeakAndRemoteHttp() {
        assertEquals("http://localhost:8787", validatedBaseUrl("http://localhost:8787/"))
        assertEquals("https://example.com", validatedBaseUrl("https://example.com"))
        listOf("http://example.com", "https://name:pass@example.com", "file:///tmp/key", "https://example.com?token=secret", "https://example.com#fragment").forEach { assertFails { validatedBaseUrl(it) } }
    }
    @Test fun evidenceQuoteSurvivesStorageAndIsRemovedWhenReviewingChangedSource() {
        val original = conversation().let { c -> c.copy(items = c.items.map { it.copy(evidenceQuote = u.text) }) }
        val restored = ArchiveCodec.decode(ArchiveCodec.encode(listOf(original))).single()
        assertEquals(u.text, restored.items.single().evidenceQuote)
        val changed = restored.editUtterance("u", "전송하지 마세요.")
        assertEquals(u.text, changed.items.single().evidenceQuote)
        assertTrue(changed.needsReview(changed.items.single()))
        assertNull(changed.reviewItem("i").items.single().evidenceQuote)
        // Version 1 files written before evidenceQuote existed still decode.
        val legacy = ArchiveCodec.encode(listOf(original)).replace("\"evidenceQuote\":\"${u.text}\"", "\"legacyField\":null")
        assertNull(ArchiveCodec.decode(legacy).single().items.single().evidenceQuote)
    }
}

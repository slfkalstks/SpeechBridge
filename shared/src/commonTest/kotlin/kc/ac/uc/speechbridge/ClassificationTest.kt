package kc.ac.uc.speechbridge

import kotlin.test.*

class ClassificationTest {
    private fun conversation(category: Category? = null, type: WorkType? = null, folder: String? = null) =
        Conversation(title = "기록", translate = true, record = true, first = Language.KO, second = Language.EN,
            category = category, workType = type, folderId = folder)
    @Test fun categoryTypeAndFolderIntersectWithoutHidingUnclassifiedFromAll() {
        val work = conversation(Category.WORK, WorkType.MEETING, "a")
        assertTrue(CategoryFilter().matches(conversation()))
        assertFalse(CategoryFilter(Category.DAILY).matches(work))
        assertTrue(CategoryFilter(Category.WORK, WorkType.MEETING, "a").matches(work))
        assertFalse(CategoryFilter(Category.WORK, WorkType.TASK, "a").matches(work))
        assertFalse(CategoryFilter(Category.WORK, WorkType.MEETING, "b").matches(work))
    }
    @Test fun oldArchiveWithoutClassificationOrFoldersStillLoads() {
        val legacy = """{"version":1,"conversations":[{"id":"old","title":"기존 기록","createdAt":"2026-01-01T00:00:00Z","translate":true,"record":true,"first":"KO","second":"EN"}]}"""
        val archive = ArchiveCodec.decodeArchive(legacy)
        assertEquals("기존 기록", archive.conversations.single().title)
        assertNull(archive.conversations.single().category)
        assertTrue(archive.folders.isEmpty())
        assertTrue(archive.conversations.single().ended)
    }
}

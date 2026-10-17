package kc.ac.uc.speechbridge

import kotlin.test.*

class WorkspaceUiStateTest {
    private fun conversation(record: Boolean = true): Conversation {
        val u = Utterance(id = "u", speaker = "유키", language = Language.JA, originalText = "会議の日程を確認してください。", translation = "회의 일정을 확인해 주세요.", translationRevision = 1)
        return Conversation(id = "c", title = "Project Alpha", translate = true, record = record, first = Language.KO, second = Language.JA,
            utterances = listOf(u), items = listOf(ActionItem(id = "i", sourceId = u.id, sourceRevision = 1, kind = ItemKind.REQUEST,
                text = "일정 확인", owner = "유키", dueText = "목요일", reviewed = true, status = TaskStatus.TODO)))
    }
    @Test fun historySearchCoversTitleOriginalTranslationAndExcludesPrivateSessions() {
        val c = conversation()
        for(q in listOf("alpha", "会議", "일정", "")) assertEquals(listOf(c), searchHistory(listOf(c), q))
        assertTrue(searchHistory(listOf(c.copy(record = false)), "alpha").isEmpty())
        assertTrue(searchHistory(listOf(c), "존재하지 않음").isEmpty())
    }
    @Test fun tasksSearchByOwnerDueAndContentAndReviewOverridesDone() {
        val c = conversation()
        for(q in listOf("유키", "목요일", "일정", "ALPHA")) assertEquals(1, searchTasks(listOf(c), q, TaskFilter.TODO).size)
        val changed = c.editUtterance("u", "요청은 취소합니다.").copy(items = c.items.map { it.copy(status = TaskStatus.DONE, reviewed = false) })
        assertTrue(searchTasks(listOf(changed), "", TaskFilter.DONE).isEmpty())
        assertEquals(1, searchTasks(listOf(changed), "", TaskFilter.REVIEW).size)
        assertTrue(searchTasks(listOf(c.copy(record = false)), "", TaskFilter.ALL).isEmpty())
    }
    @Test fun reviewedCandidatesStayInReviewQueueUntilRegistered() {
        val c = conversation().let { it.copy(items = it.items.map { i -> i.copy(status = TaskStatus.CANDIDATE) }) }
        assertEquals(1, searchTasks(listOf(c), "", TaskFilter.REVIEW).size)
        assertEquals(0, searchTasks(listOf(c), "", TaskFilter.TODO).size)
    }
}

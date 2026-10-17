package kc.ac.uc.speechbridge

data class StartDraft(val title: String = "", val translate: Boolean = true, val record: Boolean = true,
    val first: Language = Language.KO, val second: Language = Language.EN)
data class UtteranceDraft(val language: Language = Language.KO, val text: String = "")
enum class TaskFilter(val label: String) { ALL("전체"), REVIEW("검토 필요"), TODO("진행 중"), DONE("완료") }

fun searchHistory(conversations: List<Conversation>, query: String): List<Conversation> {
    val q = query.trim()
    return conversations.asReversed().filter { c -> c.record && (q.isEmpty() || c.title.contains(q, true) ||
        c.utterances.any { it.text.contains(q, true) || it.translation?.contains(q, true) == true }) }
}

fun searchTasks(conversations: List<Conversation>, query: String, filter: TaskFilter): List<Pair<Conversation, ActionItem>> {
    val q = query.trim()
    return conversations.filter { it.record }.flatMap { c -> c.items.map { c to it } }
        .filter { (c, i) ->
            val matchesStatus = when(filter) {
                TaskFilter.ALL -> true
                TaskFilter.REVIEW -> c.needsReview(i) || i.status == TaskStatus.CANDIDATE
                TaskFilter.TODO -> !c.needsReview(i) && i.status == TaskStatus.TODO
                TaskFilter.DONE -> !c.needsReview(i) && i.status == TaskStatus.DONE
            }
            matchesStatus && (q.isEmpty() || listOfNotNull(c.title, i.text, i.owner, i.dueText).any { it.contains(q, true) })
        }.sortedBy { (c, i) -> if(c.needsReview(i) || i.status == TaskStatus.CANDIDATE) 0 else if(i.status == TaskStatus.DONE) 2 else 1 }
}

expect fun displayDate(iso: String): String

/** One classification contract shared by records and tasks; unknown stays visible in All. */
data class CategoryFilter(val category: Category? = null, val workType: WorkType? = null, val folderId: String? = null) {
    fun matches(c: Conversation) = (category == null || c.category == category) &&
        (workType == null || c.workType == workType) && (folderId == null || c.folderId == folderId)
}
fun Conversation.classificationLabel(folders: List<WorkFolder>): String = listOfNotNull(
    category?.label ?: "미분류", workType?.label.takeIf { category == Category.WORK },
    folders.find { it.id == folderId }?.name.takeIf { category == Category.WORK }
).joinToString(" · ")

/** Choosing the opposite side exchanges the pair, so neither language is silently lost. */
internal fun selectLanguagePair(first: Language, second: Language, input: Boolean, selected: Language): Pair<Language, Language> =
    if ((input && selected == second) || (!input && selected == first)) second to first
    else if (input) selected to second else first to selected

package kc.ac.uc.speechbridge

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.Clock
import kotlin.random.Random

@Serializable enum class Language(val label: String, val code: String) {
    KO("한국어", "ko"), EN("English", "en"), JA("日本語", "ja")
}
@Serializable enum class ItemKind(val label: String) { REQUEST("요청"), DECISION("결정"), NOTE("메모") }
@Serializable enum class TaskStatus { CANDIDATE, TODO, DONE }
@Serializable enum class Appearance(val label: String) { SYSTEM("기기 설정 따르기"), LIGHT("라이트"), DARK("다크") }
@Serializable enum class Category(val label: String) { DAILY("일상"), WORK("업무") }
@Serializable enum class WorkType(val label: String) { MEETING("회의"), TASK("할 일"), IDEA("아이디어"), OTHER("기타") }
@Serializable data class WorkFolder(val id: String = newId(), val name: String)
@Serializable enum class Origin { MANUAL, SERVICE }

fun newId(): String = "${Clock.System.now().toEpochMilliseconds()}-${Random.nextLong().toULong()}"

@Serializable data class Utterance(
    val id: String = newId(),
    val speaker: String,
    val language: Language,
    val originalText: String,
    val text: String = originalText,
    val revision: Int = 1,
    val translation: String? = null,
    val translationRevision: Int? = null,
    val audioDurationMs: Int? = null,
)

@Serializable data class ActionItem(
    val id: String = newId(),
    val sourceId: String,
    val sourceRevision: Int,
    val kind: ItemKind,
    val text: String,
    val owner: String? = null,
    val dueText: String? = null,
    val reviewed: Boolean = false,
    val status: TaskStatus = TaskStatus.CANDIDATE,
    val origin: Origin = Origin.MANUAL,
    val evidenceQuote: String? = null,
    val contextRevisions: Map<String, Int> = emptyMap(),
    val omittedByLatestExtraction: Boolean = false,
)

@Serializable data class Conversation(
    val id: String = newId(),
    val title: String,
    val createdAt: String = Clock.System.now().toString(),
    val translate: Boolean,
    val record: Boolean,
    val first: Language,
    val second: Language,
    val ended: Boolean = false,
    val utterances: List<Utterance> = emptyList(),
    val items: List<ActionItem> = emptyList(),
    val category: Category? = null,
    val workType: WorkType? = null,
    val folderId: String? = null,
    val metadataRevision: Int = 0,
    val organized: Boolean = false,
    val organizationAttempted: Boolean = false,
    val summary: String? = null,
    val organizationSources: Map<String, Int> = emptyMap(),
) {
    fun target(source: Language) = if (source == first) second else first
    fun editUtterance(id: String, text: String): Conversation {
        require(text.isNotBlank())
        val old = utterances.firstOrNull { it.id == id } ?: return this
        if (old.text == text.trim()) return this
        return copy(
            utterances = utterances.map {
                if (it.id == id) it.copy(text = text.trim(), revision = it.revision + 1,
                    translation = null, translationRevision = null) else it
            },
            items = items.map { if (it.sourceId == id || it.origin == Origin.SERVICE) it.copy(reviewed = false) else it },
        )
    }
    fun translateResult(id: String, revision: Int, text: String): Conversation = copy(
        utterances = utterances.map {
            if (it.id == id && it.revision == revision) it.copy(translation = text, translationRevision = revision) else it
        }
    )
    fun contextRevisions(): Map<String, Int> = utterances.associate { it.id to it.revision }
    fun contextChanged(item: ActionItem): Boolean = item.origin == Origin.SERVICE && item.contextRevisions != contextRevisions()
    fun needsReview(item: ActionItem): Boolean = !item.reviewed || contextChanged(item) ||
        utterances.firstOrNull { it.id == item.sourceId }?.revision != item.sourceRevision

    fun editItem(id: String, text: String, owner: String, due: String): Conversation {
        require(text.isNotBlank())
        return copy(items = items.map {
            if (it.id == id) it.copy(text = text.trim(), owner = owner.trim().ifBlank { null },
                dueText = due.trim().ifBlank { null }, reviewed = false) else it
        })
    }
    fun reviewItem(id: String): Conversation = copy(items = items.map { item ->
        val source = utterances.firstOrNull { it.id == item.sourceId }
        if (item.id == id && source != null) item.copy(reviewed = true, sourceRevision = source.revision,
            evidenceQuote = item.evidenceQuote?.takeIf { source.text.contains(it) },
            contextRevisions = if (item.origin == Origin.SERVICE) contextRevisions() else item.contextRevisions) else item
    })
    fun setTaskStatus(id: String, status: TaskStatus): Conversation = copy(items = items.map {
        if (it.id == id) {
            require(record) { "기록을 켠 대화에서만 할 일을 저장할 수 있습니다." }
            require(!needsReview(it)) { "근거와 내용을 먼저 검토해 주세요." }
            it.copy(status = status)
        } else it
    })
}

@Serializable data class Archive(val version: Int, val conversations: List<Conversation>, val folders: List<WorkFolder> = emptyList(), val appearance: Appearance = Appearance.SYSTEM, val autoReadTranslation: Boolean = true)

object ArchiveCodec {
    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    fun encode(conversations: List<Conversation>, folders: List<WorkFolder> = emptyList(), appearance: Appearance = Appearance.SYSTEM, autoReadTranslation: Boolean = true): String = json.encodeToString(
        Archive(version = 1, conversations = conversations.filter { it.record }, folders = folders, appearance = appearance, autoReadTranslation = autoReadTranslation))
    fun decode(text: String): List<Conversation> = decodeArchive(text).conversations
    fun decodeArchive(text: String): Archive {
        val archive = json.decodeFromString<Archive>(text)
        require(archive.version == 1) { "지원하지 않는 저장 형식입니다." }
        require(archive.conversations.all { c ->
            c.record && c.id.isNotBlank() && c.utterances.map { it.id }.distinct().size == c.utterances.size &&
                c.items.map { it.id }.distinct().size == c.items.size &&
                c.utterances.all { it.id.isNotBlank() && it.revision > 0 && it.text.isNotBlank() } &&
                c.items.all { item -> item.id.isNotBlank() && item.text.isNotBlank() && item.sourceRevision > 0 &&
                    c.utterances.any { it.id == item.sourceId && item.sourceRevision <= it.revision } }
        })
        require(archive.conversations.map { it.id }.distinct().size == archive.conversations.size)
        // A process restart closes a previously active session; its recorded data remains reviewable.
        require(archive.folders.map { it.id }.distinct().size == archive.folders.size)
        require(archive.folders.all { it.id.isNotBlank() && it.name.isNotBlank() })
        return archive.copy(conversations = archive.conversations.map { it.copy(ended = true) })
    }
}

interface SessionStore {
    /** null means no file; failures throw and must never be treated as an empty archive. */
    suspend fun read(): String?
    suspend fun write(text: String)
}

class MemorySessionStore : SessionStore {
    private var data: String? = null
    override suspend fun read() = data
    override suspend fun write(text: String) { data = text }
}

/** Future native adapters emit partial text for display only; only Final enters the domain. */
sealed interface SpeechEvent {
    data class Partial(val text: String) : SpeechEvent
    data class Final(val text: String, val language: Language, val segmentId: String) : SpeechEvent
    data object Stopped : SpeechEvent
    data class Failed(val code: String) : SpeechEvent
}
interface SpeechInput {
    val events: kotlinx.coroutines.flow.Flow<SpeechEvent>
    suspend fun start(language: Language)
    suspend fun stop()
    fun cancel()
}
interface SpeechOutput {
    suspend fun speak(text: String, language: Language)
    fun stop()
}

/** Only measured audio contributes; old and keyboard-only records have no duration. */
val Conversation.audioDurationMs: Long?
    get() = utterances.mapNotNull { it.audioDurationMs }.takeIf { it.isNotEmpty() }?.sumOf { it.toLong() }

fun formatRecordingDuration(seconds: Long): String {
    val total = seconds.coerceAtLeast(0)
    val hours = total / 3600
    val minutes = total % 3600 / 60
    val remainder = total % 60
    return when {
        hours > 0 -> "${hours}시간 ${minutes}분 ${remainder}초"
        minutes > 0 -> "${minutes}분 ${remainder}초"
        else -> "${remainder}초"
    }
}
fun formatAudioDuration(milliseconds: Long): String = formatRecordingDuration((milliseconds.coerceAtLeast(0) + 999) / 1000)

/** A newly detected source keeps the current target, or swaps when the target speaks. */
fun detectedLanguagePair(first: Language, second: Language, detected: Language): Pair<Language, Language> =
    detected to if (detected == second) first else second

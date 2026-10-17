package kc.ac.uc.speechbridge

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable internal fun MoreMenu(label: String, content: @Composable ColumnScope.(() -> Unit) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }, modifier = Modifier.semantics { contentDescription = label }) {
            AppSymbol(Symbol.MORE)
        }
        DropdownMenu(open, { open = false }) { content { open = false } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun LanguageBar(first: Language, second: Language, onChange: (Language, Language) -> Unit) {
    var choosingInput by remember { mutableStateOf<Boolean?>(null) }
    Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            LanguageButton("입력 언어", first, Modifier.weight(1f)) { choosingInput = true }
            IconButton(onClick = { onChange(second, first) }, modifier = Modifier.size(48.dp).semantics {
                contentDescription = "언어 맞바꾸기: ${second.label} 입력, ${first.label} 번역"
            }) {
                CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.primary) { AppSymbol(Symbol.SWAP) }
            }
            LanguageButton("번역 언어", second, Modifier.weight(1f)) { choosingInput = false }
        }
    }
    choosingInput?.let { input ->
        val selected = if(input) first else second
        ModalBottomSheet(onDismissRequest = { choosingInput = null }, containerColor = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(if(input) "입력 언어" else "번역 언어", Modifier.padding(12.dp), style = MaterialTheme.typography.titleLarge)
                Language.entries.forEach { language ->
                    Surface(onClick = {
                        val pair = selectLanguagePair(first, second, input, language)
                        onChange(pair.first, pair.second); choosingInput = null
                    }, modifier = Modifier.fillMaxWidth().semantics { this.selected = language == selected },
                        shape = RoundedCornerShape(16.dp),
                        color = if(language == selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface) {
                        Row(Modifier.heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(language.label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                            if(language == selected) Text("선택됨", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
        }
    }
}

@Composable private fun LanguageButton(label: String, language: Language, modifier: Modifier, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = modifier.heightIn(min = 56.dp).semantics { contentDescription = "$label: ${language.label}. 변경" },
        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp)) {
        Text(language.label, Modifier.weight(1f, fill = false), style = MaterialTheme.typography.titleSmall, textAlign = TextAlign.Center)
        Spacer(Modifier.width(4.dp))
        AppSymbol(Symbol.DOWN, Modifier.size(12.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun VoiceControl(compact: Boolean = false, onKeyboard: () -> Unit) {
    var help by remember { mutableStateOf(false) }
    val microphone: @Composable () -> Unit = {
        FilledIconButton(onClick = { help = true }, modifier = Modifier.size(if(compact) 64.dp else 136.dp)
            .semantics { contentDescription = "음성 입력 준비 안내" }, shape = RoundedCornerShape(if(compact) 24.dp else 44.dp)) {
            AppSymbol(Symbol.MIC, Modifier.size(if(compact) 28.dp else 48.dp))
        }
    }
    if(compact) Surface(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
        shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface) {
        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            microphone()
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("음성 입력", style = MaterialTheme.typography.titleMedium)
                Text("마이크 연결 준비 중", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = onKeyboard, modifier = Modifier.size(48.dp).semantics { contentDescription = "키보드로 입력" }) { AppSymbol(Symbol.KEYBOARD) }
        }
    } else Column(Modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        microphone()
        Spacer(Modifier.height(28.dp))
        Text("음성으로 대화", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text("마이크 연결 준비 중", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(24.dp))
        TextButton(onClick = onKeyboard, modifier = Modifier.heightIn(min = 48.dp)) {
            AppSymbol(Symbol.KEYBOARD, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text("키보드로 입력")
        }
    }
    if(help) ModalBottomSheet(onDismissRequest = { help = false }, containerColor = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Heading("음성 입력 준비 중")
            Text("마이크 연결은 추후 제공됩니다. 연결되면 말하는 언어를 감지해 번역하고 대화를 기록합니다.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = { help = false; onKeyboard() }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("키보드로 입력") }
            TextButton(onClick = { help = false }, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("닫기") }
        }
    }
}

@Composable private fun Composer(draft: UtteranceDraft, onChange: (UtteranceDraft) -> Unit, onSend: (UtteranceDraft) -> Unit, onClose: () -> Unit) {
    var text by remember { mutableStateOf(draft.text) }
    val local = draft.copy(text = text)
    val inputFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { inputFocus.requestFocus() }
    fun update(next: UtteranceDraft) { text = next.text; onChange(next) }
    Surface(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${draft.language.label} 입력", Modifier.padding(start = 4.dp), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.weight(1f)); IconButton(onClick = onClose, modifier = Modifier.semantics { contentDescription = "키보드 닫기" }) { AppSymbol(Symbol.CLOSE) }
            }
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(local.text, { update(local.copy(text = it)) }, placeholder = { Text("대화 내용 입력") },
                    modifier = Modifier.weight(1f).focusRequester(inputFocus).semantics { contentDescription = "대화 내용 입력" }, shape = RoundedCornerShape(16.dp), maxLines = 4)
                FilledIconButton(onClick = { val sent = local; update(local.copy(text = "")); onSend(sent) }, enabled = local.text.isNotBlank(),
                    modifier = Modifier.size(48.dp).semantics { contentDescription = "입력한 문장 기록" }) { AppSymbol(Symbol.SEND) }
            }
        }
    }

}

@Composable private fun StartConversation(state: BridgeState, vm: BridgeViewModel) {
    var keyboard by remember { mutableStateOf(false) }
    val draft = state.idleDraft
    BoxWithConstraints(Modifier.fillMaxSize().wrapContentWidth(Alignment.CenterHorizontally).widthIn(max = 760.dp)) {
    val compact = maxHeight < 460.dp || LocalDensity.current.fontScale > 1.5f
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp), contentAlignment = Alignment.Center) {
            LanguageBar(state.startDraft.first, state.startDraft.second) { a, b -> vm.updateStartDraft(state.startDraft.copy(first = a, second = b)) }
        }
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(vertical = if(compact) 0.dp else 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            if(!keyboard) VoiceControl(compact = compact, onKeyboard = { keyboard = true })
        }
        if(keyboard) Composer(draft, { vm.updateIdleDraft(it) }, { sent ->
            vm.startConversation(); vm.state.value.activeId?.let { vm.addUtterance(it, sent.language, sent.text) }
        }, { keyboard = false })
    }
    }
}

private data class ItemEditor(val sourceId: String, val existing: ActionItem? = null)

@Composable internal fun ConversationScreen(state: BridgeState, vm: BridgeViewModel) {
    val c = state.selected ?: state.conversations.find { it.id == state.activeId }
    if(c == null) { StartConversation(state, vm); return }
    var section by remember(c.id) { mutableIntStateOf(if(c.ended) 1 else 0) }
    LaunchedEffect(c.ended) { if(c.ended) section = 1 }
    var summaryEdit by remember(c.id) { mutableStateOf(false) }
    var keyboard by remember(c.id) { mutableStateOf(false) }
    var sourceEdit by remember(c.id) { mutableStateOf<Utterance?>(null) }
    var itemEdit by remember(c.id) { mutableStateOf<ItemEditor?>(null) }
    var review by remember(c.id) { mutableStateOf<ActionItem?>(null) }
    var classify by remember(c.id) { mutableStateOf(false) }
    var finish by remember(c.id) { mutableStateOf(false) }
    val scroll = remember(c.id, section) { LazyListState() }
    val draft = state.inputDrafts[c.id] ?: UtteranceDraft(language = c.first)
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                if(c.ended) TextButton(onClick = { classify = true }) { Text(c.classificationLabel(state.folders), maxLines = 2); Spacer(Modifier.width(8.dp)); AppSymbol(Symbol.DOWN, Modifier.size(16.dp)) }
                else LanguageBar(c.first, c.second) { a, b -> vm.setLanguages(c.id, a, b) }
            }
            if(!c.ended) FilledTonalButton(onClick = { finish = true }, modifier = Modifier.heightIn(min = 48.dp)) { Text("종료") }
            else MoreMenu("기록 더보기") { close ->
                DropdownMenuItem(text = { Text("제목·분류 수정") }, onClick = { close(); classify = true })
                DropdownMenuItem(text = { Text(if(state.activeId == null) "새 대화" else "진행 중인 대화") }, onClick = { close(); vm.select(state.activeId ?: "") })
            }
        }
        if(c.ended) {
            val organizing = "organize:${c.id}" in state.busy
            if(organizing) LinearProgressIndicator(Modifier.fillMaxWidth().semantics { contentDescription = "제목, 분류와 요약 작성 중" })
            PrimaryTabRow(section, containerColor = MaterialTheme.colorScheme.background, divider = {}) {
                Tab(section == 0, { section = 0 }, text = { Text("대화") })
                Tab(section == 1, { section = 1 }, text = { Text("요약") })
            }
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = scroll, contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if(section == 0) {
                if(c.utterances.isEmpty()) item { EmptyState("대화를 기다리고 있어요", "입력한 내용은 자동으로 저장됩니다.") }
                items(c.utterances, key = { it.id }) { u -> UtteranceCard(c, u, state, { sourceEdit = u }, { vm.translate(c.id, u.id) },
                    { vm.cancelRequest("translate:${u.id}") }, { itemEdit = ItemEditor(u.id) }) }
            } else {
                item {
                    Panel {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("대화 요약", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                            TextButton(onClick = { summaryEdit = true }) { Text(if(c.summary == null) "직접 작성" else "수정") }
                        }
                        SelectionContainer { Text(c.summary ?: if("organize:${c.id}" in state.busy) "대화를 요약하고 있어요…" else "아직 요약이 없어요. 직접 작성할 수 있습니다.") }
                        if(state.requestErrors.containsKey("organize:${c.id}")) Text("자동 정리를 완료하지 못했어요. 제목·분류·요약을 직접 편집해 주세요.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("할 일과 결정", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                        val busy = "extract:${c.id}" in state.busy
                        TextButton(onClick = { if(busy) vm.cancelRequest("extract:${c.id}") else vm.extract(c.id) }, enabled = c.utterances.isNotEmpty()) {
                            Text(if(busy) "정리 취소" else if(c.items.isEmpty()) "AI 정리" else "다시 정리")
                        }
                    }
                    if("extract:${c.id}" in state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    state.requestErrors["extract:${c.id}"]?.let { InlineError(it) }
                }
                if(c.items.isEmpty()) item { EmptyState("아직 정리된 항목이 없어요", "AI 정리로 대화에서 할 일과 결정을 찾을 수 있어요.") }
                items(c.items, key = { it.id }) { item -> ActionCard(c, item, { itemEdit = ItemEditor(item.sourceId, item) }, { review = item },
                    { vm.task(c.id, item.id, it) }, { vm.deleteItem(c.id, item.id) }) }
            }
        }
        if(!c.ended) {
            if(keyboard) key(c.id) { Composer(draft, { vm.updateInputDraft(c.id, it) }, { vm.addUtterance(c.id, it.language, it.text) }, { keyboard = false }) }
            else VoiceControl(compact = true, onKeyboard = { keyboard = true })
        }
    }
    if(summaryEdit) SummaryEditDialog(c.summary ?: "", { summaryEdit = false }) { vm.editSummary(c.id, it); summaryEdit = false }
    sourceEdit?.let { u -> TextEditDialog("원문 수정", u.text, { sourceEdit = null }) { vm.editUtterance(c.id, u.id, it); sourceEdit = null } }
    itemEdit?.let { editor -> ItemEditDialog(editor.existing, { itemEdit = null }) { kind, text, owner, due ->
        if(editor.existing == null) vm.addItem(c.id, editor.sourceId, kind, text, owner, due)
        else vm.editItem(c.id, editor.existing, text, owner, due)
        itemEdit = null; if(c.ended) section = 1
    } }
    review?.let { item -> ReviewDialog(c, item, { review = null }) { vm.review(c.id, item.id); review = null } }
    if(classify) ClassificationDialog(c, state.folders, { classify = false }) { title, category, type, folder ->
        vm.classify(c.id, title, category, type, folder); classify = false
    }
    if(finish) ConfirmActionDialog("대화를 마칠까요?", "기록을 저장하고 AI가 제목·분류·요약을 한 번 작성합니다. 이후에는 직접 수정할 수 있습니다." +
        if(draft.text.isNotBlank()) " 작성 중인 문장도 함께 기록합니다." else "", "종료", false, { finish = false }) {
        if(draft.text.isNotBlank()) vm.addUtterance(c.id, draft.language, draft.text)
        vm.end(c.id); finish = false
    }
}

@Composable private fun UtteranceCard(c: Conversation, u: Utterance, state: BridgeState, edit: () -> Unit,
    translate: () -> Unit, cancel: () -> Unit, addItem: () -> Unit) {
    val busy = "translate:${u.id}" in state.busy
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface) {
        Column {
            Column(Modifier.padding(start = 20.dp, end = 12.dp, top = 4.dp, bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${u.language.label}${if(u.revision > 1) " · 수정됨" else ""}", Modifier.weight(1f),
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    MoreMenu("발언 더보기: ${u.text.take(40)}") { close ->
                        DropdownMenuItem(text = { Text("원문 수정") }, onClick = { close(); edit() })
                        if(c.translate) DropdownMenuItem(text = { Text(if(busy) "번역 취소" else "다시 번역") }, onClick = { close(); if(busy) cancel() else translate() })
                        DropdownMenuItem(text = { Text("정리 항목 추가") }, onClick = { close(); addItem() })
                    }
                }
                SelectionContainer { Text(u.text, Modifier.padding(end = 8.dp), style = MaterialTheme.typography.bodyLarge) }
            }
            if(c.translate) Surface(color = MaterialTheme.colorScheme.secondaryContainer) {
                Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val result = u.translation.takeIf { u.translationRevision == u.revision }
                    Text("번역", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    if(result != null) SelectionContainer { Text(result, style = MaterialTheme.typography.bodyLarge) }
                    else if(busy) Text("번역 중…", style = MaterialTheme.typography.bodyMedium)
                    else TextButton(onClick = translate, contentPadding = PaddingValues(0.dp)) { Text("번역 재시도") }
                    state.requestErrors["translate:${u.id}"]?.let { InlineError(it) }
                }
            }
        }
    }
}

@Composable internal fun ActionCard(c: Conversation, item: ActionItem, onEdit: () -> Unit, onReview: () -> Unit,
    onTask: (TaskStatus) -> Unit, onDelete: () -> Unit) {
    val needsReview = c.needsReview(item)
    var delete by remember(item.id) { mutableStateOf(false) }
    Column {
        Row(verticalAlignment = Alignment.Top) {
            Checkbox(checked = item.status == TaskStatus.DONE && !needsReview, onCheckedChange = {
                if(needsReview) onReview() else onTask(if(it) TaskStatus.DONE else TaskStatus.TODO)
            }, modifier = Modifier.semantics { contentDescription = if(needsReview) "근거 검토 후 완료" else "할 일 완료 상태" })
            Column(Modifier.weight(1f).padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(item.text, style = MaterialTheme.typography.bodyLarge,
                    textDecoration = if(item.status == TaskStatus.DONE && !needsReview) TextDecoration.LineThrough else null,
                    color = if(item.status == TaskStatus.DONE && !needsReview) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
                val details = listOfNotNull(item.owner, item.dueText).joinToString(" · ")
                if(details.isNotBlank()) Text(details, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if(needsReview) TextButton(onClick = onReview, contentPadding = PaddingValues(0.dp)) { Text("근거 확인 필요", color = MaterialTheme.colorScheme.tertiary) }
                else if(item.status == TaskStatus.CANDIDATE) TextButton(onClick = { onTask(TaskStatus.TODO) }, contentPadding = PaddingValues(0.dp)) { Text("할 일로 등록") }
                if(item.omittedByLatestExtraction) Text("최근 정리에서 제외된 항목", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
            }
            MoreMenu("${item.text.take(40)} 더보기") { close ->
                DropdownMenuItem(text = { Text("근거 확인") }, onClick = { close(); onReview() })
                DropdownMenuItem(text = { Text("수정") }, onClick = { close(); onEdit() })
                DropdownMenuItem(text = { Text("삭제", color = MaterialTheme.colorScheme.error) }, onClick = { close(); delete = true })
            }
        }
    }
    if(delete) ConfirmActionDialog("항목을 삭제할까요?", "‘${item.text}’ 항목이 삭제됩니다. 원래 대화는 유지되며 삭제한 항목은 복구할 수 없습니다.",
        "삭제", true, { delete = false }) { onDelete(); delete = false }
}

@Composable internal fun EmptyState(title: String, description: String, action: String? = null, onAction: () -> Unit = {}) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 48.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        if(action != null) TextButton(onClick = onAction) { Text(action) }
    }
}

@Composable private fun SearchField(query: String, label: String, onQuery: (String) -> Unit) {
    var input by remember { mutableStateOf(TextFieldValue(query)) }; val focus = remember { FocusRequester() }
    LaunchedEffect(query) { if(input.composition == null && input.text != query) input = TextFieldValue(query) }
    OutlinedTextField(input, { input = it; if(it.composition == null) onQuery(it.text) }, singleLine = true,
        placeholder = { Text(label) }, shape = RoundedCornerShape(16.dp), leadingIcon = { AppSymbol(Symbol.SEARCH) }, modifier = Modifier.fillMaxWidth().focusRequester(focus),
        trailingIcon = { if(input.text.isNotEmpty()) TextButton(onClick = { input = TextFieldValue(""); onQuery(""); focus.requestFocus() },
            modifier = Modifier.semantics { contentDescription = "검색어 지우기" }) { Text("지우기") } })
}

@Composable internal fun HistoryScreen(state: BridgeState, vm: BridgeViewModel, scroll: LazyListState) {
    var delete by remember { mutableStateOf<Conversation?>(null) }
    var classify by remember { mutableStateOf<Conversation?>(null) }
    var search by remember { mutableStateOf(state.historyQuery.isNotBlank()) }
    val history = searchHistory(state.conversations, state.historyQuery).filter(state.categoryFilter::matches)
    LazyColumn(Modifier.fillMaxSize(), state = scroll, contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { LibraryFilter(state, vm, search, { search = !search; if(!search) vm.searchHistory("") }) }
        if(search) item { SearchField(state.historyQuery, "기록 검색", vm::searchHistory); Spacer(Modifier.height(16.dp)) }
        if(history.isEmpty()) item { EmptyState("표시할 기록이 없어요", if(state.conversations.isEmpty()) "대화가 끝나면 여기에 모입니다." else "분류나 검색어를 바꿔 보세요.") }
        history.groupBy { displayDate(it.createdAt) }.forEach { (date, conversations) ->
            item(key = "date:$date") {
                Text(date, Modifier.padding(start = 4.dp, top = 20.dp, bottom = 8.dp).semantics { heading() },
                    style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items(conversations, key = { it.id }) { c ->
                Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f).clickable(onClickLabel = "대화 기록 열기") { vm.select(c.id) }.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(c.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            c.utterances.lastOrNull()?.let { Text(it.text, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            Text(c.classificationLabel(state.folders) + if(!c.ended) " · 진행 중" else "",
                                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        }
                        MoreMenu("${c.title} 더보기") { close ->
                            DropdownMenuItem(text = { Text("제목·분류 수정") }, onClick = { close(); classify = c })
                            DropdownMenuItem(text = { Text("삭제", color = MaterialTheme.colorScheme.error) }, onClick = { close(); delete = c })
                        }
                    }
                }
            }
        }
    }
    classify?.let { c -> ClassificationDialog(c, state.folders, { classify = null }) { title, category, type, folder -> vm.classify(c.id, title, category, type, folder); classify = null } }
    delete?.let { c -> ConfirmActionDialog("기록을 삭제할까요?", "‘${c.title}’의 대화와 연결된 할 일이 삭제됩니다. 삭제 후 복구할 수 없습니다.", "삭제", true,
        { delete = null }) { vm.delete(c.id); delete = null } }
}

@Composable internal fun TasksScreen(state: BridgeState, vm: BridgeViewModel, scroll: LazyListState) {
    var edit by remember { mutableStateOf<Pair<Conversation, ActionItem>?>(null) }
    var review by remember { mutableStateOf<Pair<Conversation, ActionItem>?>(null) }
    var search by remember { mutableStateOf(state.taskQuery.isNotBlank()) }
    val entries = searchTasks(state.conversations, state.taskQuery, state.taskFilter).filter { state.categoryFilter.matches(it.first) }
    LazyColumn(Modifier.fillMaxSize(), state = scroll, contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { LibraryFilter(state, vm, search, { search = !search; if(!search) vm.searchTasks("") }) }
        if(search) item { SearchField(state.taskQuery, "할 일 검색", vm::searchTasks) }
        if(entries.isEmpty()) item { EmptyState("표시할 할 일이 없어요", "대화의 정리 항목을 확인하거나 분류를 바꿔 보세요.") }
        items(entries, key = { "${it.first.id}:${it.second.id}" }) { (c, item) ->
            Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface) {
                Column(Modifier.padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 4.dp)) {
                    ActionCard(c, item, { edit = c to item }, { review = c to item }, { vm.task(c.id, item.id, it) }, { vm.deleteItem(c.id, item.id) })
                    TextButton(onClick = { vm.select(c.id) }, modifier = Modifier.padding(start = 40.dp), contentPadding = PaddingValues(horizontal = 8.dp)) {
                        AppSymbol(Symbol.RECORDS, Modifier.size(16.dp)); Spacer(Modifier.width(8.dp))
                        Text(c.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
    edit?.let { (c, item) -> ItemEditDialog(item, { edit = null }) { _, text, owner, due -> vm.editItem(c.id, item, text, owner, due); edit = null } }
    review?.let { (c, item) -> ReviewDialog(c, item, { review = null }) { vm.review(c.id, item.id); review = null } }
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun SettingsScreen(state: BridgeState, vm: BridgeViewModel, developerTools: Boolean) {
    var folders by remember { mutableStateOf(false) }
    var developerOpen by remember { mutableStateOf(false) }
    if(folders) FolderManager(state.folders, vm) { folders = false }
    if(developerTools && developerOpen) ModalBottomSheet(onDismissRequest = { developerOpen = false }, containerColor = MaterialTheme.colorScheme.background) {
        DeveloperConnectionScreen(state, vm)
    }
    LazyColumn(Modifier.fillMaxSize().wrapContentWidth(Alignment.CenterHorizontally).widthIn(max = 760.dp), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { Panel {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("화면 모드", style = MaterialTheme.typography.titleMedium)
                    Text(state.appearance.label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                val next = Appearance.entries[(state.appearance.ordinal + 1) % Appearance.entries.size]
                FilledTonalIconButton(onClick = { vm.setAppearance(next) }, modifier = Modifier.size(56.dp).semantics {
                    contentDescription = "화면 모드: ${state.appearance.label}. ${next.label}로 전환"
                }) {
                    AppSymbol(when(state.appearance) { Appearance.LIGHT -> Symbol.SUN; Appearance.DARK -> Symbol.MOON; Appearance.SYSTEM -> Symbol.SYSTEM }, Modifier.size(28.dp))
                }
                if(developerTools) MoreMenu("설정 더보기") { close ->
                    DropdownMenuItem(text = { Text("개발자 연결 설정") }, onClick = { close(); developerOpen = true })
                }
            }
        } }
        item { Panel {
            TextButton(onClick = { folders = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                AppSymbol(Symbol.FOLDER); Spacer(Modifier.width(12.dp)); Text("업무 폴더 관리", Modifier.weight(1f)); Text("${state.folders.size}개")
            }
        } }
        item { Panel {
            Text("내 기록", style = MaterialTheme.typography.titleMedium)
            Text("대화 ${state.conversations.count { it.record }}개 · ${if(state.unsaved) "저장 대기 중" else "저장 완료"}", style = MaterialTheme.typography.bodyMedium)
            if(state.unsaved) TextButton(onClick = vm::retrySave) { Text("저장 재시도") }
            Text("대화는 자동으로 기기에 저장됩니다. AI 번역·요약을 사용할 때는 대화 내용이 AI 서버로 전송됩니다.", style = MaterialTheme.typography.bodySmall)
        } }
    }
}

@Composable private fun DeveloperConnectionScreen(state: BridgeState, vm: BridgeViewModel) {
    var draft by remember { mutableStateOf(state.connectionDraft) }
    fun update(next: Connection) { draft = next; vm.updateConnectionDraft(next) }
    val urlFocus = remember { FocusRequester() }
    val tokenFocus = remember { FocusRequester() }
    var reveal by remember { mutableStateOf(false) }
    var submitted by remember { mutableStateOf(false) }
    val urlError = if(submitted && runCatching { validatedBaseUrl(draft.url) }.isFailure) "HTTPS 주소 또는 로컬 개발 주소를 입력해 주세요." else null
    val tokenError = if(submitted && draft.token.trim().length < 32) "서버에 설정한 32자 이상의 접속 토큰을 입력해 주세요." else null
    val changed = draft != state.connection
    LazyColumn(Modifier.fillMaxWidth().fillMaxHeight(0.85f), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { Heading("개발자 연결 설정", "개발 빌드에서만 사용할 수 있습니다.") }
        item { Panel {
            OutlinedTextField(draft.url, { update(draft.copy(url = it)) }, label = { Text("서버 주소") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth().focusRequester(urlFocus), isError = urlError != null,
                supportingText = { Text(urlError ?: "예: http://localhost:8787 (시뮬레이터)") })
            OutlinedTextField(draft.token, { update(draft.copy(token = it)) }, label = { Text("서버 접속 토큰") }, singleLine = true,
                visualTransformation = if(reveal) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth().focusRequester(tokenFocus), isError = tokenError != null,
                trailingIcon = { TextButton(onClick = { reveal = !reveal }, modifier = Modifier.semantics { contentDescription = if(reveal) "접속 토큰 숨기기" else "접속 토큰 보기" }) { Text(if(reveal) "숨기기" else "보기") } },
                supportingText = { Text(tokenError ?: "토큰은 이번 앱 실행 동안만 보관합니다.") })
            if(changed) StatusBadge("아직 적용하지 않은 설정", warning = true)
            Button(onClick = { submitted = true; when {
                    runCatching { validatedBaseUrl(draft.url) }.isFailure -> urlFocus.requestFocus()
                    draft.token.trim().length < 32 -> tokenFocus.requestFocus()
                    else -> { vm.configure(draft.url, draft.token); draft = vm.state.value.connection; reveal = false }
                } },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("설정 적용") }
            OutlinedButton(onClick = vm::checkHealth, enabled = "health" !in state.busy && !changed, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(if("health" in state.busy) "서버 확인 중…" else "서버 상태 확인") }
            if(changed) Text("변경한 설정을 먼저 적용해 주세요.", style = MaterialTheme.typography.bodySmall)
            state.requestErrors["health"]?.let { InlineError(it) }
        } }
        state.health?.let { health -> item { Panel {
            Text("연결 상태", style = MaterialTheme.typography.titleMedium)
            if(health.runtime == "offline") InlineError("API에 도달했지만 Mac AI 실행 서버는 꺼져 있습니다.")
            listOf("translation" to "텍스트 번역", "extraction" to "요약·분류·할 일 정리", "auth" to "접속 인증").forEach { (key, label) ->
                val description = when {
                    key == "auth" -> if(health.configured[key] == true) "서버 설정됨" else "설정 필요"
                    health.available[key] == true -> "모델 준비됨"
                    health.configured[key] == true -> "모델 연결 확인 필요"
                    else -> "연결 전"
                }
                Text("$label · $description", style = MaterialTheme.typography.bodyMedium)
            }
            Text("서버 상태 확인은 토큰 일치나 실제 번역 성공을 검증하지 않습니다.", style = MaterialTheme.typography.bodySmall)
        } } }
    }
}

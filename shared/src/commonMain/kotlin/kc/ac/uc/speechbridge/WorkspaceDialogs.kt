package kc.ac.uc.speechbridge

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable internal fun ConfirmActionDialog(title: String, message: String, action: String, danger: Boolean,
    onDismiss: () -> Unit, onConfirm: () -> Unit) {
    val cancelFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { cancelFocus.requestFocus() }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = {
        Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) { Text(message) }
    }, confirmButton = {
        TextButton(onClick = onConfirm, colors = ButtonDefaults.textButtonColors(contentColor = if(danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)) { Text(action) }
    }, dismissButton = { TextButton(onClick = onDismiss, modifier = Modifier.focusRequester(cancelFocus)) { Text("취소") } })
}

@Composable private fun EditDialog(title: String, dirty: Boolean, valid: Boolean, action: String,
    onDismiss: () -> Unit, onSave: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    var discard by remember { mutableStateOf(false) }
    fun close() { if(dirty) discard = true else onDismiss() }
    if(discard) ConfirmActionDialog("변경을 버릴까요?", "저장하지 않은 내용이 사라집니다.", "변경 버리기", true,
        { discard = false }, onDismiss)
    else AlertDialog(onDismissRequest = ::close, title = { Text(title) }, text = {
        Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }, confirmButton = { TextButton(onClick = onSave, enabled = valid) { Text(action) } },
        dismissButton = { TextButton(onClick = ::close) { Text("취소") } })
}

@Composable internal fun TextEditDialog(title: String, initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    EditDialog(title, text != initial, text.isNotBlank(), "수정 저장", onDismiss, { onSave(text) }) {
        Text("원문을 바꾸면 관련 번역과 정리 항목을 다시 확인해야 합니다.", style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(text, { text = it }, label = { Text("원문") }, modifier = Modifier.fillMaxWidth(), minLines = 3, maxLines = 8)
    }
}

@Composable internal fun SummaryEditDialog(initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    EditDialog("요약 수정", text != initial, text.isNotBlank() && text.length <= 2000, "저장", onDismiss, { onSave(text) }) {
        OutlinedTextField(text, { text = it }, label = { Text("대화 요약") }, modifier = Modifier.fillMaxWidth(),
            minLines = 4, maxLines = 10, isError = text.length > 2000,
            supportingText = { Text("${text.length}/2000") })
    }
}

@Composable internal fun ItemEditDialog(item: ActionItem?, onDismiss: () -> Unit,
    onSave: (ItemKind, String, String, String) -> Unit) {
    var kind by remember { mutableStateOf(item?.kind ?: ItemKind.REQUEST) }
    var text by remember { mutableStateOf(item?.text ?: "") }
    var owner by remember { mutableStateOf(item?.owner ?: "") }
    var due by remember { mutableStateOf(item?.dueText ?: "") }
    val dirty = text != (item?.text ?: "") || owner != (item?.owner ?: "") || due != (item?.dueText ?: "") || kind != (item?.kind ?: ItemKind.REQUEST)
    EditDialog(if(item == null) "정리 항목 추가" else "정리 항목 수정", dirty, text.isNotBlank(), "저장", onDismiss, { onSave(kind, text, owner, due) }) {
        if(item == null) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ItemKind.entries.forEach { k -> FilterChip(kind == k, { kind = k }, modifier = Modifier.heightIn(min = 48.dp), label = { Text(k.label) }) }
        }
        OutlinedTextField(text, { text = it }, label = { Text("내용") }, minLines = 2, maxLines = 8, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(owner, { owner = it }, label = { Text("담당자") }, supportingText = { Text("미정이면 비워 두세요.") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(due, { due = it }, label = { Text("기한") }, supportingText = { Text("예: 금요일까지 · 발언에 나온 표현을 유지하세요.") }, modifier = Modifier.fillMaxWidth())
        Text("저장 후 근거를 확인해야 할 일로 등록할 수 있습니다.", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable internal fun ReviewDialog(c: Conversation, item: ActionItem, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    val source = c.utterances.find { it.id == item.sourceId }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("근거와 내용 확인") }, text = {
        Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("현재 원문", fontWeight = FontWeight.Bold)
            SelectionContainer { Text(source?.text ?: "근거를 찾을 수 없습니다.") }
            item.evidenceQuote?.let { quote ->
                Text(if(source?.revision == item.sourceRevision) "AI가 참조한 원문" else "생성 당시 인용 · 현재 원문과 비교하세요", fontWeight = FontWeight.Bold)
                SelectionContainer { Text(quote, style = MaterialTheme.typography.bodyMedium) }
            }
            if(c.contextChanged(item) || item.omittedByLatestExtraction) {
                Text("이후 대화도 확인해 주세요", fontWeight = FontWeight.Bold)
                if(item.omittedByLatestExtraction) Text("최근 AI 정리에는 이 항목이 없습니다. 취소·정정 내용을 확인하고 유지할 때만 검토를 완료하세요.", color = MaterialTheme.colorScheme.tertiary)
                c.utterances.forEach { Text("${it.language.label} · ${it.text}", style = MaterialTheme.typography.bodySmall) }
            }
            HorizontalDivider()
            Text(item.text, fontWeight = FontWeight.Medium)
            Text("담당: ${item.owner ?: "미정"}\n기한: ${item.dueText ?: "미정"}")
            Text("내가 내용을 검토했다는 표시입니다. 상대방의 동의나 업무 수락을 뜻하지 않습니다.", style = MaterialTheme.typography.bodySmall)
        }
    }, confirmButton = { TextButton(onClick = onConfirm, enabled = source != null) { Text("내용 확인 완료") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("닫기") } })
}

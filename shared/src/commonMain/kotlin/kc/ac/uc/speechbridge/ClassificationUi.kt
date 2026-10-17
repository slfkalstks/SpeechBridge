package kc.ac.uc.speechbridge

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

@Composable internal fun <T> ChoiceMenu(label: String, value: String, choices: List<Pair<T, String>>, onSelect: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }, modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "$label: $value" }) {
            Text(value); Spacer(Modifier.width(8.dp)); AppSymbol(Symbol.DOWN, Modifier.size(16.dp))
        }
        DropdownMenu(open, { open = false }) {
            choices.forEach { (id, title) -> DropdownMenuItem(text = { Text(title) }, onClick = { onSelect(id); open = false }) }
        }
    }
}

@Composable internal fun LibraryFilter(state: BridgeState, vm: BridgeViewModel, searching: Boolean, onSearch: () -> Unit) {
    var folders by remember { mutableStateOf(false) }
    var details by remember { mutableStateOf(false) }
    val filter = state.categoryFilter
    Column {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            FlowRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(null to "전체", Category.DAILY to "일상", Category.WORK to "업무").forEach { (category, title) ->
                    FilterChip(filter.category == category, { vm.filterCategory(CategoryFilter(category)) },
                        colors = FilterChipDefaults.filterChipColors(containerColor = MaterialTheme.colorScheme.surface,
                            selectedContainerColor = MaterialTheme.colorScheme.primary, selectedLabelColor = MaterialTheme.colorScheme.onPrimary),
                        border = null, shape = RoundedCornerShape(16.dp),
                        label = { Text(title, Modifier.padding(horizontal = 8.dp, vertical = 5.dp)) }, modifier = Modifier.heightIn(min = 48.dp))
                }
            }
            FilledTonalIconButton(onClick = onSearch, modifier = Modifier.size(48.dp).semantics { contentDescription = if(searching) "검색 닫기" else "검색" },
                colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = MaterialTheme.colorScheme.surface, contentColor = MaterialTheme.colorScheme.onSurface)) {
                AppSymbol(if(searching) Symbol.CLOSE else Symbol.SEARCH)
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if(filter.category == Category.WORK) TextButton(onClick = { details = true }) {
                val label = listOfNotNull(filter.workType?.label, state.folders.find { it.id == filter.folderId }?.name).joinToString(" · ")
                AppSymbol(Symbol.FOLDER, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
                Text(label.ifBlank { "업무 분류" }); Spacer(Modifier.width(8.dp)); AppSymbol(Symbol.DOWN, Modifier.size(16.dp))
            }
            if(state.page == Page.TASKS) ChoiceMenu("상태", if(state.taskFilter == TaskFilter.ALL) "모든 상태" else state.taskFilter.label, TaskFilter.entries.map { it to it.label }) { vm.filterTasks(it) }
        }
    }
    if(folders) FolderManager(state.folders, vm, { folders = false })
    else if(details) AlertDialog(onDismissRequest = { details = false }, title = { Text("업무 분류") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            ChoiceMenu("업무 유형", filter.workType?.label ?: "모든 유형", listOf(null to "모든 유형") + WorkType.entries.map { it to it.label }) { vm.filterCategory(filter.copy(workType = it)) }
            ChoiceMenu("업무 폴더", state.folders.find { it.id == filter.folderId }?.name ?: "모든 폴더",
                listOf<String?>(null).map { it to "모든 폴더" } + state.folders.map { it.id to it.name }) { vm.filterCategory(filter.copy(folderId = it)) }
            TextButton(onClick = { folders = true; details = false }) { Text("폴더 관리") }
        }
    }, confirmButton = { TextButton(onClick = { details = false }) { Text("완료") } },
        dismissButton = { TextButton(onClick = { vm.filterCategory(CategoryFilter(Category.WORK)) }) { Text("초기화") } })
}

@Composable internal fun ClassificationDialog(c: Conversation, folders: List<WorkFolder>, dismiss: () -> Unit,
    save: (String, Category, WorkType?, String?) -> Unit) {
    var title by remember(c.id) { mutableStateOf(c.title) }
    var category by remember(c.id) { mutableStateOf(c.category) }
    var type by remember(c.id) { mutableStateOf(c.workType ?: WorkType.OTHER) }
    var folder by remember(c.id) { mutableStateOf(c.folderId) }
    var discard by remember { mutableStateOf(false) }
    fun close() {
        if(title != c.title || category != c.category || (category == Category.WORK && (type != c.workType || folder != c.folderId))) discard = true else dismiss()
    }
    if(discard) {
        ConfirmActionDialog("변경을 버릴까요?", "아직 저장하지 않은 제목과 분류가 사라집니다.", "변경 버리기", true, { discard = false }, dismiss)
    } else AlertDialog(onDismissRequest = ::close, title = { Text("제목과 분류") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(title, { title = it }, label = { Text("제목") }, modifier = Modifier.fillMaxWidth(), maxLines = 3)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Category.entries.forEach { value -> FilterChip(category == value, { category = value }, label = { Text(value.label) }, modifier = Modifier.heightIn(min = 48.dp)) }
            }
            if(category == Category.WORK) {
                ChoiceMenu("업무 유형", type.label, WorkType.entries.map { it to it.label }) { type = it }
                ChoiceMenu("업무 폴더", folders.find { it.id == folder }?.name ?: "폴더 없음",
                    listOf<String?>(null).map { it to "폴더 없음" } + folders.map { it.id to it.name }) { folder = it }
                if(folders.isEmpty()) Text("기록 › 업무 › 업무 분류에서 폴더를 만들 수 있어요.", style = MaterialTheme.typography.bodySmall)
            }
        }
    }, confirmButton = { TextButton(onClick = { category?.let { save(title, it, type, folder) } }, enabled = title.isNotBlank() && category != null) { Text("저장") } },
        dismissButton = { TextButton(onClick = ::close) { Text("취소") } })
}

@Composable internal fun FolderManager(folders: List<WorkFolder>, vm: BridgeViewModel, dismiss: () -> Unit) {
    var edit by remember { mutableStateOf<WorkFolder?>(null) }
    var name by remember { mutableStateOf("") }
    var delete by remember { mutableStateOf<WorkFolder?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var discard by remember { mutableStateOf(false) }
    fun close() { if(name.isNotBlank() && name != edit?.name) discard = true else dismiss() }
    if(discard) {
        ConfirmActionDialog("입력을 버릴까요?", "저장하지 않은 폴더 이름이 사라집니다.", "버리기", true, { discard = false }, dismiss)
        return
    }
    delete?.let { f ->
        ConfirmActionDialog("폴더를 삭제할까요?", "‘${f.name}’ 폴더만 삭제합니다. 안의 기록과 할 일은 업무에 그대로 남습니다.", "폴더 삭제", true,
            { delete = null }) { vm.deleteFolder(f.id); if(edit?.id == f.id) { edit = null; name = "" }; delete = null }
        return
    }
    AlertDialog(onDismissRequest = ::close, title = { Text("업무 폴더") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(name, { name = it; error = null }, label = { Text(if(edit == null) "새 폴더 이름" else "폴더 이름 수정") }, singleLine = true,
                modifier = Modifier.fillMaxWidth(), isError = error != null || name.length > 200,
                supportingText = { if(name.length > 200) Text("폴더 이름은 200자 이내로 입력해 주세요.") else error?.let { Text(it) } })
            Row {
                TextButton(onClick = {
                    if(vm.saveFolder(name, edit?.id)) { name = ""; edit = null; error = null } else error = "다른 폴더 이름을 입력해 주세요."
                }, enabled = name.isNotBlank() && name.length <= 200) { Text(if(edit == null) "폴더 만들기" else "이름 저장") }
                if(edit != null) TextButton(onClick = { edit = null; name = ""; error = null }) { Text("수정 취소") }
            }
            folders.forEach { f ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(f.name, Modifier.weight(1f))
                    MoreMenu("${f.name} 폴더 더보기") { closeMenu ->
                        DropdownMenuItem(text = { Text("이름 변경") }, onClick = { closeMenu(); edit = f; name = f.name })
                        DropdownMenuItem(text = { Text("폴더 삭제", color = MaterialTheme.colorScheme.error) }, onClick = { closeMenu(); delete = f })
                    }
                }
            }
        }
    }, confirmButton = { TextButton(onClick = ::close) { Text("완료") } })
}

package kc.ac.uc.speechbridge

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable internal fun CloudStoragePanel(state: BridgeState, vm: BridgeViewModel) {
    var importing by remember { mutableStateOf(false) }
    var resolving by remember { mutableStateOf(false) }
    val enabled = state.loaded && !state.accountRequired && !state.workspaceBusy
    Panel {
        Text("내 기록", style = MaterialTheme.typography.titleMedium)
        Text("대화 ${state.conversations.size}개 · " + when {
            state.unsaved -> "기기 저장 필요"
            state.workspaceBusy -> "기록 확인 중"
            state.syncConflict -> "다른 기기의 변경 확인 필요"
            state.syncBusy -> "서버에 저장 중"
            state.syncPending > 0 -> "${state.syncPending}건 전송 대기"
            else -> "서버 저장 완료"
        }, style = MaterialTheme.typography.bodyMedium)
        Text("대화는 내 계정에 자동 저장됩니다. 연결이 끊기면 전송 대기 내용만 이 기기에 암호화해 보관합니다.", style = MaterialTheme.typography.bodySmall)
        if(state.workspaceBusy || state.syncBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
        state.syncProblem?.let { InlineError(it) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            when {
                state.unsaved -> TextButton(onClick = vm::retrySave, enabled = enabled) { Text("기기 저장 재시도") }
                state.syncConflict -> TextButton(onClick = { resolving = true }, enabled = enabled) { Text("변경 내용 보관") }
                state.syncPending > 0 -> TextButton(onClick = vm::retrySync, enabled = enabled && !state.syncBusy) { Text("다시 전송") }
            }
            TextButton(onClick = vm::refreshRecords, enabled = enabled && !state.unsaved && state.activeId == null) { Text("새로고침") }
        }
        if(state.legacyCount > 0 || state.legacyFolders > 0) TextButton(onClick = { importing = true }, enabled = enabled && !state.unsaved) {
            Text("기존 기기 기록 가져오기 · ${state.legacyCount}개")
        }
    }
    if(importing) AlertDialog(onDismissRequest = { importing = false },
        title = { Text("이 계정으로 가져올까요?") },
        text = { Text("${vm.account?.state?.value?.user?.username.orEmpty()} 계정에 대화 ${state.legacyCount}개와 폴더 ${state.legacyFolders}개를 가져옵니다. 서버에 저장된 내용을 확인한 뒤 기기 원본을 비웁니다.") },
        confirmButton = { TextButton(onClick = { importing = false; vm.importLegacyRecords() }) { Text("가져오기") } },
        dismissButton = { TextButton(onClick = { importing = false }) { Text("취소") } })
    if(resolving) AlertDialog(onDismissRequest = { resolving = false },
        title = { Text("두 내용을 모두 보관할까요?") },
        text = { Text("서버 기록을 유지하고 이 기기의 변경 내용은 별도 대화로 저장합니다. 삭제 대기 중인 서버 기록도 남깁니다. 화면 모드와 작성 중인 초안은 이 기기의 선택을 유지합니다.") },
        confirmButton = { TextButton(onClick = { resolving = false; vm.resolveSyncConflict() }) { Text("모두 보관") } },
        dismissButton = { TextButton(onClick = { resolving = false }) { Text("취소") } })
}

package kc.ac.uc.speechbridge

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun AccountPanel(account: AccountManager, changing: suspend () -> Boolean, deleting: suspend (String) -> Unit, retryDeletion: () -> Unit) {
    val state by account.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var open by remember { mutableStateOf(false) }
    var confirmLogout by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var deletePassword by remember { mutableStateOf("") }
    LaunchedEffect(state.deletion, state.user) {
        if (state.deletion == AccountDeletion.CONFIRMED || state.user == null) {
            confirmDelete = false; deletePassword = ""
        }
    }
    Panel {
        Text(state.user?.username ?: "계정", style = MaterialTheme.typography.titleMedium)
        Text(when(state.deletion) {
            AccountDeletion.REQUESTED -> "삭제 결과를 확인 중입니다. 확인이 끝날 때까지 계정 사용을 잠시 중지합니다."
            AccountDeletion.CONFIRMED -> "계정이 삭제되었습니다. 이 기기의 전송 대기 내용을 정리합니다."
            AccountDeletion.NONE -> if(state.user != null) "로그인됨 · 기록은 내 계정에 저장됩니다."
                else if(account.available) "로그인하면 번역·요약·분류·할 일 추출을 사용할 수 있습니다."
                else "서비스 연결을 준비 중입니다."
        }, style = MaterialTheme.typography.bodyMedium)
        if(state.busy || !state.ready) LinearProgressIndicator(Modifier.fillMaxWidth())
        state.error?.let { InlineError(it) }
        when(state.deletion) {
            AccountDeletion.REQUESTED -> {
                Button(onClick = { scope.launch { account.recheckDeletion() } }, enabled = !state.busy) { Text("삭제 결과 확인") }
                TextButton(onClick = { confirmDelete = true }, enabled = !state.busy) { Text("삭제 다시 요청") }
            }
            AccountDeletion.CONFIRMED -> TextButton(onClick = retryDeletion, enabled = !state.busy) { Text("정리 재시도") }
            AccountDeletion.NONE -> if(state.user == null) {
                Button(onClick = { open = true }, enabled = account.available && state.ready && !state.busy) { Text("로그인 / 회원가입") }
            } else Row {
                TextButton(onClick = { confirmLogout = true }, enabled = !state.busy) { Text("로그아웃") }
                TextButton(onClick = { confirmDelete = true }, enabled = !state.busy) { Text("계정 삭제", color = MaterialTheme.colorScheme.error) }
            }
        }
    }
    if(confirmDelete) AlertDialog(onDismissRequest = { if(!state.busy) { confirmDelete = false; deletePassword = "" } },
        title = { Text("계정과 기록을 삭제할까요?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("${state.user?.username.orEmpty()} 계정의 대화·할 일·폴더·설정과 이 기기의 전송 대기 내용을 삭제합니다. 되돌릴 수 없습니다.")
                Text("서버 백업은 생성 후 30일 보관 정책으로 정리하며, 삭제한 계정은 복원에서 제외합니다. 서버가 꺼져 있으면 다음 실행 때 정리합니다.", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(deletePassword, { deletePassword = it }, label = { Text("현재 비밀번호") },
                    singleLine = true, enabled = !state.busy, visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                state.error?.let { InlineError(it) }
            }
        },
        confirmButton = { TextButton(onClick = { scope.launch { val password = deletePassword; deletePassword = ""; deleting(password) } },
            enabled = !state.busy && deletePassword.length in 12..128) { Text("계정 영구 삭제", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { confirmDelete = false; deletePassword = "" }, enabled = !state.busy) { Text("취소") } })
    if(confirmLogout) AlertDialog(onDismissRequest = { confirmLogout = false }, title = { Text("로그아웃할까요?") },
        text = { Text("서버 기록은 내 계정에 보관됩니다. 전송 대기 내용은 같은 계정으로 다시 로그인하면 이어서 저장됩니다. 이 기기의 AI 요청은 취소되며, 저장한 대화의 자동 정리는 계속됩니다.") },
        confirmButton = { TextButton(onClick = { confirmLogout = false; scope.launch { if(changing()) account.logout() } }) { Text("로그아웃") } },
        dismissButton = { TextButton(onClick = { confirmLogout = false }) { Text("취소") } })
    if(open) {
        var register by remember { mutableStateOf(false) }
        var username by remember { mutableStateOf("") }
        var password by remember { mutableStateOf("") }
        var confirmation by remember { mutableStateOf("") }
        var mismatch by remember { mutableStateOf(false) }
        ModalBottomSheet(onDismissRequest = { if(!state.busy) open = false }, containerColor = MaterialTheme.colorScheme.background, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            Column(Modifier.fillMaxWidth().fillMaxHeight(0.9f).verticalScroll(rememberScrollState()).imePadding().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(if(register) "회원가입" else "로그인", style = MaterialTheme.typography.headlineSmall)
                    TextButton(onClick = { register = !register; password = ""; confirmation = ""; mismatch = false }, enabled = !state.busy) {
                        Text(if(register) "로그인으로" else "회원가입")
                    }
                }
                OutlinedTextField(username, { username = it }, label = { Text("아이디") }, singleLine = true,
                    supportingText = { Text("영문·숫자·밑줄 3~32자") }, enabled = !state.busy,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, autoCorrectEnabled = false), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(password, { password = it; mismatch = false }, label = { Text("비밀번호") }, singleLine = true,
                    supportingText = { Text("12~128자 · 비밀번호 재설정은 아직 지원하지 않습니다.") }, enabled = !state.busy,
                    visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth())
                if(register) OutlinedTextField(confirmation, { confirmation = it; mismatch = false }, label = { Text("비밀번호 확인") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    enabled = !state.busy, isError = mismatch, modifier = Modifier.fillMaxWidth())
                if(mismatch) InlineError("비밀번호가 일치하지 않습니다.")
                state.error?.let { InlineError(it) }
                Button(onClick = {
                    if(register && password != confirmation) mismatch = true
                    else {
                        scope.launch {
                            if(!changing()) return@launch
                            account.authenticate(username, password, register)
                            password = ""; confirmation = ""
                            if(account.state.value.user != null) open = false
                        }
                    }
                }, enabled = !state.busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text(if(state.busy) "확인 중…" else if(register) "계정 만들기" else "로그인")
                }
            }
        }
    }
}

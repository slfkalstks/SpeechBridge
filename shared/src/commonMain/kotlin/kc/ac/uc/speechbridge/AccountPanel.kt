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
@Composable internal fun AccountPanel(account: AccountManager, changing: suspend () -> Boolean) {
    val state by account.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var open by remember { mutableStateOf(false) }
    var confirmLogout by remember { mutableStateOf(false) }
    Panel {
        Text(state.user?.username ?: "계정", style = MaterialTheme.typography.titleMedium)
        Text(if(state.user != null) "로그인됨 · AI 번역과 대화 정리를 사용할 수 있습니다."
            else if(account.available) "로그인하면 번역·요약·분류·할 일 추출을 사용할 수 있습니다."
            else "서비스 연결을 준비 중입니다. 연결이 준비되면 로그인할 수 있습니다.", style = MaterialTheme.typography.bodyMedium)
        Text("기록은 로그인한 내 계정에 저장됩니다.", style = MaterialTheme.typography.bodySmall)
        if(state.busy || !state.ready) LinearProgressIndicator(Modifier.fillMaxWidth())
        state.error?.let { InlineError(it) }
        if(state.user == null) Button(onClick = { open = true }, enabled = account.available && state.ready && !state.busy) { Text("로그인 / 회원가입") }
        else TextButton(onClick = { confirmLogout = true }, enabled = !state.busy) { Text("로그아웃") }
    }
    if(confirmLogout) AlertDialog(onDismissRequest = { confirmLogout = false }, title = { Text("로그아웃할까요?") },
        text = { Text("서버 기록은 내 계정에 보관됩니다. 전송 대기 내용은 같은 계정으로 다시 로그인하면 이어서 저장됩니다. 진행 중인 AI 요청은 취소됩니다.") },
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

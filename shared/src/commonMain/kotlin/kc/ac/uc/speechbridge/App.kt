package kc.ac.uc.speechbridge

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

@Composable
fun App(store: SessionStore? = null, developerTools: Boolean = false, credentialStore: CredentialStore = MemoryCredentialStore(), developerUrl: String = "http://localhost:8787") {
    val actualStore = remember(store) { store ?: MemorySessionStore() }
    val vm = viewModel {
        val account = AccountManager(SERVICE_URL.ifBlank { if(developerTools) developerUrl else "" }, credentialStore, developerTools)
        BridgeViewModel(actualStore, HttpBridgeApi(developerMode = developerTools, account = account), account)
    }
    LaunchedEffect(vm) { vm.account?.restore() }
    val state by vm.state.collectAsStateWithLifecycle()
    val historyScroll = remember { LazyListState() }
    val taskScroll = remember { LazyListState() }
    var previousPage by remember { mutableStateOf(Page.TALK) }
    val tabs = listOf(Page.TALK, Page.HISTORY, Page.TASKS)
    val selectedTab = if(state.page == Page.TALK && state.selected?.ended == true) Page.HISTORY else state.page
    fun navigate(page: Page) { if(page == Page.TALK) vm.select(state.activeId ?: "") else vm.navigate(page) }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.message) {
        snackbar.currentSnackbarData?.dismiss()
        state.message?.let {
            snackbar.showSnackbar(it, actionLabel = "닫기", duration = SnackbarDuration.Short)
            vm.dismissMessage()
        }
    }
    BridgeTheme(state.appearance) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            BoxWithConstraints(Modifier.safeDrawingPadding().imePadding()) {
                val wide = (maxWidth >= 840.dp || (maxWidth >= 600.dp && maxHeight < 500.dp)) && LocalDensity.current.fontScale < 1.5f
                val compactHeight = maxHeight < 500.dp
                Row(Modifier.fillMaxSize()) {
                    if (wide) NavigationRail(containerColor = MaterialTheme.colorScheme.background) {
                        Spacer(Modifier.height(12.dp))
                        tabs.forEach { page -> NavigationRailItem(selected = selectedTab == page,
                            onClick = { navigate(page) }, icon = { BridgeIcon(page) }, label = { Text(page.label) }) }
                    }
                    Scaffold(modifier = Modifier.weight(1f), containerColor = MaterialTheme.colorScheme.background,
                        contentWindowInsets = WindowInsets(0, 0, 0, 0),
                        topBar = { WorkspaceHeader(state, vm, compactHeight, {
                            if(state.page == Page.SETTINGS) vm.navigate(previousPage)
                            else { previousPage = state.page; vm.navigate(Page.SETTINGS) }
                        }) },
                        bottomBar = {
                            if (!wide) Surface(Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                                shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surface) {
                                NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp,
                                    windowInsets = WindowInsets(0, 0, 0, 0)) {
                                    tabs.forEach { page -> NavigationBarItem(selected = selectedTab == page,
                                        colors = NavigationBarItemDefaults.colors(indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                                            selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                            selectedTextColor = MaterialTheme.colorScheme.primary),
                                        onClick = { navigate(page) }, icon = { BridgeIcon(page) }, label = { Text(page.label) }) }
                                }
                            }
                        },
                        snackbarHost = { SnackbarHost(snackbar, Modifier.semantics { liveRegion = LiveRegionMode.Polite }) },
                    ) { padding ->
                        Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                            Box(Modifier.widthIn(max = 1200.dp).fillMaxSize()) {
                                when {
                                    !state.loaded -> Column(Modifier.fillMaxSize().padding(24.dp),
                                        verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                        if (state.loadFailed) {
                                            Heading("기록을 안전하게 보관 중입니다", "기존 파일을 읽기 전까지 새 내용으로 덮어쓰지 않습니다.")
                                            Button(onClick = vm::reload) { Text("다시 읽기") }
                                        } else { CircularProgressIndicator(); Text("기록을 불러오고 있습니다.") }
                                    }
                                    state.page == Page.TALK -> ConversationScreen(state, vm)
                                    state.page == Page.HISTORY -> HistoryScreen(state, vm, historyScroll)
                                    state.page == Page.TASKS -> TasksScreen(state, vm, taskScroll)
                                    else -> SettingsScreen(state, vm, developerTools)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable private fun WorkspaceHeader(state: BridgeState, vm: BridgeViewModel, compact: Boolean, settings: () -> Unit) {
    val detail = state.page == Page.TALK && state.selected?.ended == true
    Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 20.dp, top = if(compact) 0.dp else 12.dp, bottom = if(compact) 8.dp else 16.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if(state.page == Page.SETTINGS || detail) IconButton(onClick = { if(detail) vm.navigate(Page.HISTORY) else settings() },
            modifier = Modifier.semantics { contentDescription = if(detail) "기록 목록으로" else "뒤로" }) { AppSymbol(Symbol.BACK) }
        Text(if(state.page == Page.TALK) state.selected?.takeIf { it.ended }?.title ?: "대화" else state.page.label,
            Modifier.weight(1f).semantics { heading() }, style = if(detail || compact) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineLarge, maxLines = if(compact) 1 else 2,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        if(state.unsaved) TextButton(onClick = vm::retrySave) { Text("저장 재시도") }
        if(state.page != Page.SETTINGS) FilledTonalIconButton(onClick = settings,
            colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = MaterialTheme.colorScheme.surface, contentColor = MaterialTheme.colorScheme.onSurface),
            modifier = Modifier.size(48.dp).semantics { contentDescription = "설정" }) { BridgeIcon(Page.SETTINGS) }
    }
}

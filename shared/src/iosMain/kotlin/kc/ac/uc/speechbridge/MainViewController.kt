package kc.ac.uc.speechbridge

import androidx.compose.ui.window.ComposeUIViewController

@OptIn(kotlin.experimental.ExperimentalNativeApi::class)
fun MainViewController() = ComposeUIViewController { App(IosSessionStore(), credentialStore = IosCredentialStore(), pendingStore = IosCredentialStore("kc.ac.uc.speechbridge.pending"), developerTools = kotlin.native.Platform.isDebugBinary) }

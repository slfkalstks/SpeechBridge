package kc.ac.uc.speechbridge

import androidx.compose.ui.window.ComposeUIViewController
import androidx.compose.runtime.remember

@OptIn(kotlin.experimental.ExperimentalNativeApi::class)
fun MainViewController() = ComposeUIViewController { App(IosSessionStore(), audioDevice = remember { IosAudioDevice() }, credentialStore = IosCredentialStore(), pendingStore = IosCredentialStore("kc.ac.uc.speechbridge.pending"), developerTools = kotlin.native.Platform.isDebugBinary) }

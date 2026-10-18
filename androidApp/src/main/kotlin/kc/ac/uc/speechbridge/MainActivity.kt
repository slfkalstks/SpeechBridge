package kc.ac.uc.speechbridge

import android.os.Bundle
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.coroutines.CompletableDeferred
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview

class MainActivity : ComponentActivity() {
    private var permissionResult: CompletableDeferred<Boolean>? = null
    private val microphonePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permissionResult?.complete(granted); permissionResult = null
    }
    private val audio by lazy { AndroidAudioDevice(applicationContext) {
        if(checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) true
        else {
            val result = permissionResult ?: CompletableDeferred<Boolean>().also {
                permissionResult = it; microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
            }
            result.await()
        }
    } }
    override fun onDestroy() {
        audio.close(); permissionResult?.cancel(); permissionResult = null
        super.onDestroy()
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        setContent {
            App(AndroidSessionStore(applicationContext), audioDevice = audio, credentialStore = AndroidCredentialStore(applicationContext), pendingStore = AndroidCredentialStore(applicationContext, "pending_sync"), developerUrl = "http://10.0.2.2:8787", developerTools = applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0)
        }
    }
}

@Preview
@Composable
fun AppAndroidPreview() {
    App()
}
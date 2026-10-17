package kc.ac.uc.speechbridge

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        setContent {
            App(AndroidSessionStore(applicationContext), credentialStore = AndroidCredentialStore(applicationContext), developerUrl = "http://10.0.2.2:8787", developerTools = applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0)
        }
    }
}

@Preview
@Composable
fun AppAndroidPreview() {
    App()
}
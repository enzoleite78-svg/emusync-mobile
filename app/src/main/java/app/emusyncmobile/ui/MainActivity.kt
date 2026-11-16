package app.emusyncmobile.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val colors = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()
            MaterialTheme(colorScheme = colors) {
                val state by viewModel.state.collectAsState()

                val consentLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.StartIntentSenderForResult()
                ) { result -> viewModel.onConsentResult(result.data) }

                LaunchedEffect(state.consentRequest) {
                    state.consentRequest?.let {
                        consentLauncher.launch(IntentSenderRequest.Builder(it).build())
                    }
                }

                var pickingFor by remember { mutableStateOf<String?>(null) }
                val folderPicker = rememberLauncherForActivityResult(
                    ActivityResultContracts.OpenDocumentTree()
                ) { uri ->
                    val gameId = pickingFor
                    pickingFor = null
                    if (uri != null && gameId != null) viewModel.onFolderPicked(gameId, uri)
                }

                MainScreen(
                    state = state,
                    onLogin = viewModel::login,
                    onRefresh = viewModel::refresh,
                    onSyncAll = viewModel::syncAll,
                    onPickFolder = { gameId ->
                        pickingFor = gameId
                        folderPicker.launch(null)
                    },
                    onSync = viewModel::sync,
                    onDismissMessages = viewModel::dismissMessages,
                )
            }
        }
    }
}

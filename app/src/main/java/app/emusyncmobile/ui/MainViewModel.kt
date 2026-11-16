package app.emusyncmobile.ui

import android.app.Application
import android.content.Intent
import android.content.IntentSender
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.emusyncmobile.auth.GoogleAuth
import app.emusyncmobile.auth.NotAuthorizedException
import app.emusyncmobile.core.SyncAction
import app.emusyncmobile.drive.DriveClient
import app.emusyncmobile.drive.DriveException
import app.emusyncmobile.local.LocalStore
import app.emusyncmobile.sync.BackupManager
import app.emusyncmobile.sync.GameStatus
import app.emusyncmobile.sync.Remote
import app.emusyncmobile.sync.SyncEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

data class UiState(
    val deviceName: String = "",
    val loading: Boolean = false,
    val needsLogin: Boolean = false,
    val consentRequest: IntentSender? = null,
    val games: List<GameStatus> = emptyList(),
    val busyGameId: String? = null,
    val message: String? = null,
    val error: String? = null,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val auth = GoogleAuth(app)
    private val store = LocalStore(File(app.filesDir, "state.json"))
    private val engine = SyncEngine(
        context = app,
        drive = DriveClient(auth),
        store = store,
        backups = BackupManager(File(app.filesDir, "game-backups")),
    )
    private var remote: Remote? = null

    private val _state = MutableStateFlow(UiState(deviceName = store.load().deviceName))
    val state: StateFlow<UiState> = _state

    init {
        connect()
    }

    fun login() {
        viewModelScope.launch {
            runCatching { auth.authorize() }
                .onSuccess { result ->
                    when (result) {
                        is GoogleAuth.Result.Authorized -> connect()
                        is GoogleAuth.Result.NeedsConsent ->
                            _state.update { it.copy(consentRequest = result.intentSender) }
                    }
                }
                .onFailure { e -> showError(e) }
        }
    }

    fun onConsentResult(data: Intent?) {
        _state.update { it.copy(consentRequest = null) }
        if (auth.handleConsentResult(data)) connect()
        else _state.update { it.copy(needsLogin = true, error = "Login cancelado ou recusado.") }
    }

    fun connect() = launchWork {
        val r = engine.connect()
        remote = r
        _state.update { it.copy(needsLogin = false, games = engine.statuses(r)) }
    }

    fun refresh() = connect()

    fun onFolderPicked(gameId: String, uri: Uri) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        getApplication<Application>().contentResolver.takePersistableUriPermission(uri, flags)
        launchWork(gameId) {
            val r = engine.setFolder(requireRemote(), gameId, uri)
            remote = r
            _state.update { it.copy(games = engine.statuses(r), message = "Pasta salva.") }
        }
    }

    fun sync(gameId: String, force: SyncAction? = null) = launchWork(gameId) {
        val r = engine.sync(requireRemote(), gameId, force)
        remote = r
        _state.update { it.copy(games = engine.statuses(r), message = "Sincronizado.") }
    }

    fun syncAll() = launchWork {
        val pending = _state.value.games.filter {
            it.action == SyncAction.UPLOAD || it.action == SyncAction.DOWNLOAD
        }
        var r = requireRemote()
        val failures = mutableListOf<String>()
        for (status in pending) {
            _state.update { it.copy(busyGameId = status.game.id) }
            runCatching { r = engine.sync(r, status.game.id) }
                .onFailure { failures += "${status.game.name}: ${it.message}" }
        }
        remote = r
        _state.update {
            it.copy(
                busyGameId = null,
                games = engine.statuses(r),
                message = if (pending.isEmpty()) "Nada para sincronizar." else "${pending.size - failures.size} de ${pending.size} sincronizados.",
                error = failures.takeIf { f -> f.isNotEmpty() }?.joinToString("\n"),
            )
        }
    }

    fun dismissMessages() = _state.update { it.copy(message = null, error = null) }

    private fun requireRemote(): Remote = remote ?: error("Ainda não conectado ao Google Drive.")

    private fun launchWork(gameId: String? = null, block: suspend () -> Unit) {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, busyGameId = gameId, error = null, message = null) }
            try {
                block()
            } catch (e: Exception) {
                showError(e)
            } finally {
                _state.update { it.copy(loading = false, busyGameId = null) }
            }
        }
    }

    private fun showError(e: Throwable) {
        if (e is NotAuthorizedException) {
            _state.update { it.copy(needsLogin = true) }
            return
        }
        val text = when {
            e is DriveException && e.code == 403 ->
                "O Google Drive recusou o acesso (403). Confira se a API do Drive está ativada no seu projeto do Google Cloud.\n${e.message}"
            else -> e.message ?: e.toString()
        }
        _state.update { it.copy(error = text) }
    }
}

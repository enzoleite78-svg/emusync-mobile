package app.emusyncmobile.ui

import android.app.Application
import android.content.Intent
import android.content.IntentSender
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.emusyncmobile.AppServices
import app.emusyncmobile.auth.GoogleAuth
import app.emusyncmobile.auth.NotAuthorizedException
import app.emusyncmobile.background.SyncWorker
import app.emusyncmobile.core.SyncAction
import app.emusyncmobile.drive.DriveException
import app.emusyncmobile.sync.BackupInfo
import app.emusyncmobile.sync.GameStatus
import app.emusyncmobile.sync.Remote
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class UiState(
    val deviceName: String = "",
    val autoSyncEnabled: Boolean = false,
    val loading: Boolean = false,
    val needsLogin: Boolean = false,
    val consentRequest: IntentSender? = null,
    val games: List<GameStatus> = emptyList(),
    val busyGameId: String? = null,
    val message: String? = null,
    val error: String? = null,
    val backupDialog: BackupDialog? = null,
)

data class BackupDialog(val gameId: String, val gameName: String, val backups: List<BackupInfo>)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val services = AppServices(app)
    private val auth = services.auth
    private val store = services.store
    private val engine = services.engine
    private var remote: Remote? = null

    private val _state = MutableStateFlow(
        store.load().let { UiState(deviceName = it.deviceName, autoSyncEnabled = it.autoSyncEnabled) }
    )
    val state: StateFlow<UiState> = _state

    private var lastOpenSync = 0L

    init {
        if (store.load().autoSyncEnabled) SyncWorker.schedule(app)
        // A primeira sincronização acontece em onAppForeground (chamado quando a tela aparece)
    }

    /**
     * Chamado sempre que o app volta para a tela: conecta e já envia/baixa o que for óbvio.
     * Conflitos nunca são resolvidos sozinhos. Ignora se acabou de rodar (menos de 30 s).
     */
    fun onAppForeground(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastOpenSync < OPEN_SYNC_COOLDOWN_MS) return
        if (_state.value.loading) return
        lastOpenSync = now
        launchWork {
            val r = engine.connect()
            remote = r
            val result = engine.autoSync(r)
            val text = buildList {
                if (result.synced.isNotEmpty()) add("Sincronizado ao abrir: ${result.synced.joinToString(", ")}.")
                if (result.conflicts.isNotEmpty()) add("Conflito em: ${result.conflicts.joinToString(", ")}. Escolha qual save manter.")
            }.joinToString("\n").ifEmpty { null }
            _state.update {
                it.copy(
                    needsLogin = false,
                    games = engine.statuses(r),
                    message = text,
                    error = result.failures.takeIf { f -> f.isNotEmpty() }?.joinToString("\n"),
                )
            }
        }
    }

    fun setAutoSyncEnabled(enabled: Boolean) {
        store.update { it.copy(autoSyncEnabled = enabled) }
        if (enabled) SyncWorker.schedule(getApplication()) else SyncWorker.cancel(getApplication())
        _state.update {
            it.copy(
                autoSyncEnabled = enabled,
                message = if (enabled) "Sync automático ligado: o celular verifica os saves a cada ~15 minutos."
                else "Sync automático desligado.",
            )
        }
    }

    fun setGameAutoSync(gameId: String, enabled: Boolean) {
        store.updateGame(gameId) { it.copy(autoSync = enabled) }
        remote?.let { r -> launchWork { _state.update { it.copy(games = engine.statuses(r)) } } }
    }

    fun login() {
        viewModelScope.launch {
            runCatching { auth.authorize() }
                .onSuccess { result ->
                    when (result) {
                        is GoogleAuth.Result.Authorized -> onAppForeground(force = true)
                        is GoogleAuth.Result.NeedsConsent ->
                            _state.update { it.copy(consentRequest = result.intentSender) }
                    }
                }
                .onFailure { e -> showError(e) }
        }
    }

    fun onConsentResult(data: Intent?) {
        _state.update { it.copy(consentRequest = null) }
        if (auth.handleConsentResult(data)) onAppForeground(force = true)
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

    fun openBackups(gameId: String) {
        val game = _state.value.games.firstOrNull { it.game.id == gameId } ?: return
        viewModelScope.launch {
            val list = withContext(Dispatchers.IO) { engine.backups(gameId) }
            _state.update { it.copy(backupDialog = BackupDialog(gameId, game.game.name, list)) }
        }
    }

    fun closeBackups() = _state.update { it.copy(backupDialog = null) }

    fun restoreBackup(gameId: String, backup: BackupInfo) {
        _state.update { it.copy(backupDialog = null) }
        launchWork(gameId) {
            val r = engine.restoreBackup(requireRemote(), gameId, backup.file)
            remote = r
            _state.update {
                it.copy(
                    games = engine.statuses(r),
                    message = "Backup restaurado. O save anterior foi guardado como um novo backup.",
                )
            }
        }
    }

    fun dismissMessages() = _state.update { it.copy(message = null, error = null) }

    private fun requireRemote(): Remote = remote ?: error("Ainda não conectado ao Google Drive.")

    private companion object {
        const val OPEN_SYNC_COOLDOWN_MS = 30_000L
    }

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

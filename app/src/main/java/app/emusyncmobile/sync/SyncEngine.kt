package app.emusyncmobile.sync

import android.content.Context
import android.net.Uri
import app.emusyncmobile.core.CloudGame
import app.emusyncmobile.core.CloudNames
import app.emusyncmobile.core.DotNetTime
import app.emusyncmobile.core.GameListFile
import app.emusyncmobile.core.LocalScan
import app.emusyncmobile.core.SyncAction
import app.emusyncmobile.core.SyncDecider
import app.emusyncmobile.core.SyncSourcesFile
import app.emusyncmobile.drive.DriveClient
import app.emusyncmobile.drive.DriveFile
import app.emusyncmobile.local.LocalGame
import app.emusyncmobile.local.LocalStore
import app.emusyncmobile.local.SafFolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

class SyncException(message: String) : Exception(message)

/** Resultado de uma rodada de sync automático. */
data class AutoSyncResult(
    val synced: List<String> = emptyList(),
    val conflicts: List<String> = emptyList(),
    val failures: List<String> = emptyList(),
)

data class GameStatus(
    val game: CloudGame,
    val local: LocalGame?,
    val action: SyncAction,
    val scan: LocalScan?,
    val error: String? = null,
    val backupCount: Int = 0,
)

/** Estado da pasta EmuSync_Data no Drive. */
data class Remote(
    val folderId: String,
    val files: Map<String, DriveFile>,
    val gameList: GameListFile,
) {
    fun zipFor(gameId: String): DriveFile? = files[CloudNames.gameZip(gameId)]
    val gameListId: String get() = files.getValue(CloudNames.GAME_LIST).id
}

class SyncEngine(
    private val context: Context,
    private val drive: DriveClient,
    private val store: LocalStore,
    private val backups: BackupManager,
) {
    private val tempDir get() = File(context.cacheDir, "temp-zips")

    /** Encontra a pasta do EmuSync, registra este celular e carrega a lista de jogos. */
    suspend fun connect(): Remote {
        val start = System.currentTimeMillis()
        try {
            return connectInner()
        } finally {
            android.util.Log.i("EmuSyncEngine", "connect levou ${System.currentTimeMillis() - start} ms")
        }
    }

    private suspend fun connectInner(): Remote {
        val candidates = drive.findFolders(CloudNames.DATA_FOLDER).sortedBy { it.createdTime ?: "" }
        if (candidates.isEmpty()) {
            throw SyncException("Pasta ${CloudNames.DATA_FOLDER} não encontrada no Drive. Configure o EmuSync no PC primeiro.")
        }

        for (folder in candidates) {
            val children = drive.listChildren(folder.id)
            // Se houver nomes repetidos, fica o mais antigo (o original criado pelo PC)
            val byName = children.sortedBy { it.createdTime ?: "" }
                .groupBy { it.name }
                .mapValues { it.value.first() }
            val listFile = byName[CloudNames.GAME_LIST] ?: continue

            // As duas coisas ao mesmo tempo: economiza uma ida e volta ao Drive
            val gameList = coroutineScope {
                val register = async { registerDevice(byName) }
                val list = async { GameListFile.parse(drive.downloadText(listFile.id)) }
                register.await()
                list.await()
            }
            return Remote(folder.id, byName, gameList)
        }
        throw SyncException("Nenhuma pasta ${CloudNames.DATA_FOLDER} tem o arquivo ${CloudNames.GAME_LIST}. Adicione um jogo no EmuSync do PC primeiro.")
    }

    private suspend fun registerDevice(files: Map<String, DriveFile>) {
        val sourcesFile = files[CloudNames.SYNC_SOURCES]
            ?: throw SyncException("${CloudNames.SYNC_SOURCES} não existe no Drive. Configure o EmuSync no PC primeiro.")
        val state = store.load()
        val current = SyncSourcesFile.parse(drive.downloadText(sourcesFile.id))
        val updated = current.upsertDevice(state.deviceId, state.deviceName) ?: return
        drive.updateContent(sourcesFile.id, "application/json", updated.encode().toByteArray())
    }

    suspend fun statuses(remote: Remote): List<GameStatus> = withContext(Dispatchers.IO) {
        val state = store.load()
        val start = System.currentTimeMillis()
        remote.gameList.games.map { game -> statusOf(remote, game, state.games[game.id]) }
            .sortedBy { it.game.name.lowercase() }
            .also {
                val files = it.sumOf { s -> s.scan?.fileCount ?: 0 }
                android.util.Log.i("EmuSyncEngine", "status de ${it.size} jogos ($files arquivos) em ${System.currentTimeMillis() - start} ms")
            }
    }

    private fun statusOf(remote: Remote, game: CloudGame, local: LocalGame?): GameStatus {
        if (local == null) return GameStatus(game, null, SyncAction.NO_FOLDER, null)
        val backupCount = backups.count(game.id)
        return try {
            val scan = SafFolder(context, Uri.parse(local.folderUri)).scan()
            val action = SyncDecider.decide(game, remote.zipFor(game.id) != null, scan, local.lastSync)
            GameStatus(game, local, action, scan, backupCount = backupCount)
        } catch (e: Exception) {
            GameStatus(game, local, SyncAction.NO_FOLDER, null, e.message ?: e.toString(), backupCount)
        }
    }

    /** Associa uma pasta do celular a um jogo e mostra o caminho no EmuSync do PC. */
    suspend fun setFolder(remote: Remote, gameId: String, treeUri: Uri): Remote {
        val displayPath = SafFolder.displayPath(treeUri)
        store.update { state ->
            state.copy(games = state.games + (gameId to LocalGame(treeUri.toString(), displayPath)))
        }
        val deviceId = store.load().deviceId
        val list = modifyGame(remote, gameId) { it.withLocation(deviceId, displayPath) }
        return remote.copy(gameList = list)
    }

    /**
     * Sincroniza um jogo. [force] resolve conflitos: UPLOAD mantém o save do celular,
     * DOWNLOAD mantém o da nuvem.
     */
    /**
     * Sync automático: só jogos com pasta definida e autoSync ligado, e só quando a ação
     * é óbvia (enviar ou baixar). Conflitos nunca são resolvidos sozinhos.
     */
    suspend fun autoSync(remote: Remote): AutoSyncResult {
        var current = remote
        val synced = mutableListOf<String>()
        val conflicts = mutableListOf<String>()
        val failures = mutableListOf<String>()
        val localGames = store.load().games

        for (status in statuses(current)) {
            val local = localGames[status.game.id] ?: continue
            if (!local.autoSync) continue
            when (status.action) {
                SyncAction.UPLOAD, SyncAction.DOWNLOAD -> try {
                    current = sync(current, status.game.id)
                    synced += status.game.name
                } catch (e: Exception) {
                    failures += "${status.game.name}: ${e.message}"
                }
                SyncAction.CONFLICT -> conflicts += status.game.name
                else -> {}
            }
            status.error?.let { failures += "${status.game.name}: $it" }
        }
        return AutoSyncResult(synced, conflicts, failures)
    }

    suspend fun sync(remote: Remote, gameId: String, force: SyncAction? = null): Remote = lock.withLock {
        val start = System.currentTimeMillis()
        try {
            syncLocked(remote, gameId, force)
        } finally {
            android.util.Log.i("EmuSyncEngine", "sync $gameId levou ${System.currentTimeMillis() - start} ms")
        }
    }
    private suspend fun syncLocked(remote: Remote, gameId: String, force: SyncAction?): Remote = withContext(Dispatchers.IO) {
        val local = store.load().games[gameId] ?: throw SyncException("Escolha a pasta do jogo primeiro.")
        val fresh = GameListFile.parse(drive.downloadText(remote.gameListId))
        val game = fresh.find(gameId) ?: throw SyncException("Este jogo foi removido no EmuSync do PC.")
        val current = remote.copy(gameList = fresh)

        val folder = SafFolder(context, Uri.parse(local.folderUri))
        val action = force ?: SyncDecider.decide(game, current.zipFor(gameId) != null, folder.scan(), local.lastSync)

        when (action) {
            SyncAction.UPLOAD -> upload(current, game, folder)
            SyncAction.DOWNLOAD -> download(current, game, folder)
            SyncAction.CONFLICT -> throw SyncException("Conflito: o save mudou no celular e na nuvem. Escolha qual manter.")
            SyncAction.NEEDS_PC_FIRST -> throw SyncException(
                "Este jogo ainda não tem save na nuvem. No EmuSync do PC, aponte o jogo para uma pasta (pode ser vazia) e sincronize uma vez."
            )
            else -> current
        }
    }

    private suspend fun upload(remote: Remote, game: CloudGame, folder: SafFolder): Remote {
        val zipId = remote.zipFor(game.id)?.id ?: throw SyncException(
            "Este jogo ainda não tem save na nuvem. Sincronize uma vez pelo EmuSync do PC."
        )
        val temp = File(tempDir, "${game.id}-upload.zip")
        try {
            val scan = folder.zipTo(temp)
            drive.updateContent(zipId, "application/zip", temp)

            // Grava "agora" em lwt: assim o PC sempre vê a nuvem como mais nova e baixa
            val timestamp = DotNetTime.now()
            val deviceId = store.load().deviceId
            val list = modifyGame(remote, game.id) { it.afterUpload(deviceId, timestamp, scan.totalBytes) }

            store.updateGame(game.id) { it.copy(cloudLatestWrite = timestamp, localFingerprint = scan.fingerprint) }
            return remote.copy(gameList = list)
        } finally {
            temp.delete()
        }
    }

    private suspend fun download(remote: Remote, game: CloudGame, folder: SafFolder): Remote {
        val zipId = remote.zipFor(game.id)?.id ?: throw SyncException("Não há save deste jogo na nuvem.")
        val temp = File(tempDir, "${game.id}-download.zip")
        try {
            drive.downloadToFile(zipId, temp)
            backups.create(game.id, folder, store.load().maxBackups)
            folder.replaceContentsWith(temp)

            val scan = folder.scan()
            store.updateGame(game.id) {
                it.copy(cloudLatestWrite = game.latestWriteTime, localFingerprint = scan.fingerprint)
            }
            return remote
        } finally {
            temp.delete()
        }
    }

    fun backups(gameId: String): List<BackupInfo> = backups.details(gameId)

    fun backupCount(gameId: String): Int = backups.count(gameId)

    /**
     * Restaura um backup na pasta do celular e envia para a nuvem (igual ao EmuSync de PC).
     * Antes, guarda um backup do save atual, para a restauração também poder ser desfeita.
     */
    suspend fun restoreBackup(remote: Remote, gameId: String, backup: File): Remote = lock.withLock {
        withContext(Dispatchers.IO) {
            val local = store.load().games[gameId] ?: throw SyncException("Escolha a pasta do jogo primeiro.")
            if (!backup.exists()) throw SyncException("Esse backup não existe mais.")
            val folder = SafFolder(context, Uri.parse(local.folderUri))

            // Copia antes: criar o backup do save atual pode apagar o mais antigo (que pode ser este)
            val temp = File(tempDir, "$gameId-restore.zip")
            temp.parentFile?.mkdirs()
            backup.copyTo(temp, overwrite = true)
            try {
                backups.create(gameId, folder, store.load().maxBackups)
                folder.replaceContentsWith(temp)
            } finally {
                temp.delete()
            }
        }

        // Envia o save restaurado, se o jogo já existir na nuvem
        val fresh = remote.copy(gameList = GameListFile.parse(drive.downloadText(remote.gameListId)))
        if (fresh.zipFor(gameId) == null) return@withLock fresh
        syncLocked(fresh, gameId, SyncAction.UPLOAD)
    }

    companion object {
        /** Impede que o app e o sync em segundo plano mexam nos saves ao mesmo tempo. */
        private val lock = Mutex()
    }

    /** Lê game-list.json de novo, altera só um jogo e grava (preservando o resto). */
    private suspend fun modifyGame(remote: Remote, gameId: String, transform: (CloudGame) -> CloudGame): GameListFile {
        val fresh = GameListFile.parse(drive.downloadText(remote.gameListId))
        val game = fresh.find(gameId) ?: throw SyncException("Este jogo foi removido no EmuSync do PC.")
        val updated = fresh.replace(transform(game))
        drive.updateContent(remote.gameListId, "application/json", updated.encode().toByteArray())
        return updated
    }
}

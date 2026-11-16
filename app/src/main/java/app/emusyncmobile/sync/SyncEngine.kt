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
import kotlinx.coroutines.withContext
import java.io.File

class SyncException(message: String) : Exception(message)

data class GameStatus(
    val game: CloudGame,
    val local: LocalGame?,
    val action: SyncAction,
    val scan: LocalScan?,
    val error: String? = null,
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

            registerDevice(byName)
            val gameList = GameListFile.parse(drive.downloadText(listFile.id))
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
        remote.gameList.games.map { game -> statusOf(remote, game, state.games[game.id]) }
            .sortedBy { it.game.name.lowercase() }
    }

    private fun statusOf(remote: Remote, game: CloudGame, local: LocalGame?): GameStatus {
        if (local == null) return GameStatus(game, null, SyncAction.NO_FOLDER, null)
        return try {
            val scan = SafFolder(context, Uri.parse(local.folderUri)).scan()
            val action = SyncDecider.decide(game, remote.zipFor(game.id) != null, scan, local.lastSync)
            GameStatus(game, local, action, scan)
        } catch (e: Exception) {
            GameStatus(game, local, SyncAction.NO_FOLDER, null, e.message ?: e.toString())
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
    suspend fun sync(remote: Remote, gameId: String, force: SyncAction? = null): Remote = withContext(Dispatchers.IO) {
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

    /** Lê game-list.json de novo, altera só um jogo e grava (preservando o resto). */
    private suspend fun modifyGame(remote: Remote, gameId: String, transform: (CloudGame) -> CloudGame): GameListFile {
        val fresh = GameListFile.parse(drive.downloadText(remote.gameListId))
        val game = fresh.find(gameId) ?: throw SyncException("Este jogo foi removido no EmuSync do PC.")
        val updated = fresh.replace(transform(game))
        drive.updateContent(remote.gameListId, "application/json", updated.encode().toByteArray())
        return updated
    }
}

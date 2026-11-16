package app.emusyncmobile.local

import android.os.Build
import app.emusyncmobile.core.LastSync
import app.emusyncmobile.core.newEmuSyncId
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/** Configuração de um jogo neste celular. */
@Serializable
data class LocalGame(
    /** URI da pasta escolhida (Storage Access Framework). */
    val folderUri: String,
    /** Caminho legível, ex.: /storage/emulated/0/PSP/SAVEDATA */
    val displayPath: String,
    val cloudLatestWrite: String? = null,
    val localFingerprint: String? = null,
) {
    val lastSync: LastSync get() = LastSync(cloudLatestWrite, localFingerprint)
}

@Serializable
data class LocalState(
    /** Id deste celular em sync-sources.json (mesmo formato do EmuSync). */
    val deviceId: String,
    val deviceName: String,
    val games: Map<String, LocalGame> = emptyMap(),
    val maxBackups: Int = 10,
)

/** Estado local guardado num JSON dentro da pasta privada do app. */
class LocalStore(private val file: File) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    @Synchronized
    fun load(): LocalState {
        if (file.exists()) {
            runCatching { return json.decodeFromString(LocalState.serializer(), file.readText()) }
        }
        val fresh = LocalState(
            deviceId = newEmuSyncId(),
            deviceName = "Android - ${Build.MANUFACTURER} ${Build.MODEL}".trim(),
        )
        save(fresh)
        return fresh
    }

    @Synchronized
    fun update(transform: (LocalState) -> LocalState): LocalState {
        val updated = transform(load())
        save(updated)
        return updated
    }

    fun updateGame(gameId: String, transform: (LocalGame) -> LocalGame) = update { state ->
        val game = state.games[gameId] ?: return@update state
        state.copy(games = state.games + (gameId to transform(game)))
    }

    private fun save(state: LocalState) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(json.encodeToString(LocalState.serializer(), state))
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }
}

package app.emusyncmobile.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Formato dos arquivos que o EmuSync de PC grava na pasta EmuSync_Data do Google Drive.
 * Os objetos são mantidos como JsonObject para preservar campos desconhecidos
 * (versões futuras do EmuSync podem adicionar campos novos).
 */
object CloudNames {
    const val DATA_FOLDER = "EmuSync_Data"
    const val GAME_LIST = "game-list.json"
    const val SYNC_SOURCES = "sync-sources.json"
    fun gameZip(gameId: String) = "game-$gameId.zip"

    const val STORAGE_PROVIDER_GOOGLE_DRIVE = 1
    /** O EmuSync não tem valor para Android; usamos Linux (2) para aparecer com ícone no PC. */
    const val OS_PLATFORM_LINUX = 2
}

private val json = Json { ignoreUnknownKeys = true }

private fun parseObject(text: String): JsonObject =
    json.parseToJsonElement(text.trimStart('﻿')).jsonObject

private fun JsonObject.str(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull

/** Um jogo de game-list.json (campos com nomes abreviados pelo EmuSync). */
data class CloudGame(val raw: JsonObject) {
    val id: String get() = raw.str("id") ?: error("Jogo sem id em game-list.json")
    val name: String get() = raw.str("b") ?: "(sem nome)"
    val autoSync: Boolean get() = (raw["as"] as? JsonPrimitive)?.booleanOrNull ?: false
    val locations: Map<String, String>
        get() = (raw["sl"] as? JsonObject)
            ?.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.contentOrNull?.let { k to it } }
            ?.toMap()
            ?: emptyMap()
    val lastSyncedFrom: String? get() = raw.str("lsf")
    val lastSyncTime: String? get() = raw.str("lst")
    val latestWriteTime: String? get() = raw.str("lwt")
    val storageBytes: Long get() = (raw["sb"] as? JsonPrimitive)?.longOrNull ?: 0L

    fun with(vararg changes: Pair<String, JsonElement>): CloudGame =
        CloudGame(JsonObject(raw + changes))

    fun withLocation(deviceId: String, path: String): CloudGame {
        val current = (raw["sl"] as? JsonObject) ?: JsonObject(emptyMap())
        return with("sl" to JsonObject(current + (deviceId to JsonPrimitive(path))))
    }

    /** Campos que o EmuSync grava depois de um upload. */
    fun afterUpload(deviceId: String, timestamp: String, bytes: Long): CloudGame = with(
        "lsf" to JsonPrimitive(deviceId),
        "lst" to JsonPrimitive(timestamp),
        "lwt" to JsonPrimitive(timestamp),
        "sb" to JsonPrimitive(bytes),
    )
}

class GameListFile(val raw: JsonObject) {
    val games: List<CloudGame>
        get() = (raw["games"] as? JsonArray)
            ?.mapNotNull { (it as? JsonObject)?.let(::CloudGame) }
            ?: emptyList()

    fun find(gameId: String): CloudGame? = games.firstOrNull { it.raw.str("id") == gameId }

    fun replace(updated: CloudGame): GameListFile {
        val array = (raw["games"] as? JsonArray) ?: JsonArray(emptyList())
        var found = false
        val newArray = array.map { element ->
            if (element is JsonObject && element.str("id") == updated.id) {
                found = true
                updated.raw
            } else element
        }
        require(found) { "Jogo ${updated.id} não existe mais em game-list.json" }
        return GameListFile(JsonObject(raw + ("games" to JsonArray(newArray))))
    }

    fun encode(): String = json.encodeToString(JsonObject.serializer(), raw)

    companion object {
        fun parse(text: String) = GameListFile(parseObject(text))
    }
}

class SyncSourcesFile(val raw: JsonObject) {
    private val sources: List<JsonObject>
        get() = (raw["sources"] as? JsonArray)?.mapNotNull { it as? JsonObject } ?: emptyList()

    fun contains(deviceId: String) = sources.any { it.str("id") == deviceId }

    fun nameOf(deviceId: String): String? = sources.firstOrNull { it.str("id") == deviceId }?.str("name")

    /** Adiciona este celular (ou atualiza o nome). Retorna null se nada mudou. */
    fun upsertDevice(deviceId: String, name: String): SyncSourcesFile? {
        val existing = sources.firstOrNull { it.str("id") == deviceId }
        if (existing != null && existing.str("name") == name) return null

        val entry = JsonObject(
            (existing ?: emptyMap<String, JsonElement>()) + mapOf(
                "id" to JsonPrimitive(deviceId),
                "name" to JsonPrimitive(name),
                "storageProvider" to JsonPrimitive(CloudNames.STORAGE_PROVIDER_GOOGLE_DRIVE),
                "osPlatform" to (existing?.get("osPlatform") ?: JsonPrimitive(CloudNames.OS_PLATFORM_LINUX)),
            )
        )
        val array = (raw["sources"] as? JsonArray)?.toMutableList() ?: mutableListOf()
        val index = array.indexOfFirst { it is JsonObject && it.str("id") == deviceId }
        if (index >= 0) array[index] = entry else array.add(entry)
        return SyncSourcesFile(JsonObject(raw + ("sources" to JsonArray(array))))
    }

    fun encode(): String = json.encodeToString(JsonObject.serializer(), raw)

    companion object {
        fun parse(text: String) = SyncSourcesFile(parseObject(text))
    }
}

/** Formata um id no mesmo estilo do EmuSync: Guid.ToString("N"). */
fun newEmuSyncId(): String = java.util.UUID.randomUUID().toString().replace("-", "")

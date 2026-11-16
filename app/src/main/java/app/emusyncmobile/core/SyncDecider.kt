package app.emusyncmobile.core

import java.security.MessageDigest

enum class SyncAction {
    /** Celular e nuvem iguais. */
    IN_SYNC,
    /** Save do celular é mais novo: enviar. */
    UPLOAD,
    /** Save da nuvem é mais novo: baixar. */
    DOWNLOAD,
    /** Os dois mudaram desde o último sync (ou primeira vez com saves dos dois lados). */
    CONFLICT,
    /** Nenhuma pasta escolhida neste celular. */
    NO_FOLDER,
    /** O jogo ainda não tem zip na nuvem: o primeiro envio precisa sair do PC/Deck. */
    NEEDS_PC_FIRST,
    /** Nada para sincronizar (sem saves em nenhum dos lados). */
    NOTHING_TO_DO,
}

data class FileEntry(val path: String, val size: Long, val lastModified: Long)

data class LocalScan(
    val folderExists: Boolean,
    val files: List<FileEntry>,
) {
    val fileCount: Int get() = files.size
    val totalBytes: Long get() = files.sumOf { it.size }
    val hasFiles: Boolean get() = folderExists && files.isNotEmpty()
    val fingerprint: String get() = Fingerprint.of(files)

    companion object {
        val MISSING = LocalScan(folderExists = false, files = emptyList())
    }
}

/** O que o celular lembra do último sync de um jogo. */
data class LastSync(
    /** Valor de "lwt" na nuvem logo após o último sync. */
    val cloudLatestWrite: String?,
    /** Impressão digital da pasta local logo após o último sync. */
    val localFingerprint: String?,
)

/**
 * Impressão digital de uma pasta: no Android não é possível alterar a data de modificação
 * dos arquivos (o EmuSync de PC faz isso), então detectamos mudanças comparando a lista
 * de arquivos, tamanhos e datas com a do último sync.
 */
object Fingerprint {
    fun of(files: List<FileEntry>): String {
        val text = files.sortedBy { it.path }
            .joinToString("\n") { "${it.path}|${it.size}|${it.lastModified}" }
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }
}

object SyncDecider {
    fun decide(
        game: CloudGame,
        zipExistsInCloud: Boolean,
        scan: LocalScan?,
        last: LastSync?,
    ): SyncAction {
        if (scan == null) return SyncAction.NO_FOLDER

        if (!zipExistsInCloud) {
            return if (scan.hasFiles) SyncAction.NEEDS_PC_FIRST else SyncAction.NOTHING_TO_DO
        }

        // Zip vazio (22 bytes) criado pelo PC a partir de uma pasta vazia
        val cloudEmpty = game.storageBytes == 0L

        // Primeira vez deste jogo neste celular
        if (last?.localFingerprint == null) {
            return when {
                !scan.hasFiles && cloudEmpty -> SyncAction.NOTHING_TO_DO
                !scan.hasFiles -> SyncAction.DOWNLOAD
                cloudEmpty -> SyncAction.UPLOAD
                else -> SyncAction.CONFLICT
            }
        }

        val localChanged = scan.fingerprint != last.localFingerprint
        val cloudChanged = !DotNetTime.same(game.latestWriteTime, last.cloudLatestWrite)

        return when {
            localChanged && cloudChanged -> SyncAction.CONFLICT
            // Pasta local esvaziada/apagada: nunca enviar "nada" por cima da nuvem sem perguntar
            localChanged && !scan.hasFiles -> SyncAction.CONFLICT
            localChanged -> SyncAction.UPLOAD
            cloudChanged -> SyncAction.DOWNLOAD
            else -> SyncAction.IN_SYNC
        }
    }
}

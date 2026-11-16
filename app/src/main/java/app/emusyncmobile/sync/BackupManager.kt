package app.emusyncmobile.sync

import app.emusyncmobile.local.SafFolder
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.zip.ZipFile

/** Um backup local de um jogo. */
data class BackupInfo(
    val file: File,
    val createdAtMillis: Long,
    val sizeBytes: Long,
    val fileCount: Int,
)

/**
 * Backups locais antes de cada download (igual ao EmuSync de PC: padrão de 10 por jogo).
 * Ficam na pasta privada do app: files/game-backups/{gameId}/backup_dd-MM-yyyy_HH-mm-ss.zip
 */
class BackupManager(private val baseDir: File) {
    private val nameFormat = DateTimeFormatter.ofPattern("dd-MM-yyyy_HH-mm-ss")

    fun list(gameId: String): List<File> =
        File(baseDir, gameId).listFiles { f -> f.name.endsWith(".zip") }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()

    /** Lista com detalhes (mais recente primeiro). */
    fun details(gameId: String): List<BackupInfo> = list(gameId).map { file ->
        BackupInfo(
            file = file,
            createdAtMillis = file.lastModified(),
            sizeBytes = file.length(),
            fileCount = runCatching {
                ZipFile(file).use { zip -> zip.entries().toList().count { !it.isDirectory } }
            }.getOrDefault(0),
        )
    }

    fun count(gameId: String): Int = list(gameId).size

    /** Retorna o arquivo criado, ou null se não havia nada para guardar. */
    fun create(gameId: String, folder: SafFolder, maxBackups: Int): File? {
        val scan = folder.scan()
        if (!scan.hasFiles) return null

        val dir = File(baseDir, gameId).apply { mkdirs() }
        var file = File(dir, "backup_${LocalDateTime.now().format(nameFormat)}.zip")
        // Dois backups no mesmo segundo (ex.: restaurar logo após um download)
        var n = 2
        while (file.exists()) file = File(dir, "backup_${LocalDateTime.now().format(nameFormat)}_${n++}.zip")
        folder.zipTo(file)

        list(gameId).drop(maxBackups.coerceAtLeast(1)).forEach { it.delete() }
        return file
    }
}

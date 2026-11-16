package app.emusyncmobile.sync

import app.emusyncmobile.local.SafFolder
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

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

    /** Retorna o arquivo criado, ou null se não havia nada para guardar. */
    fun create(gameId: String, folder: SafFolder, maxBackups: Int): File? {
        val scan = folder.scan()
        if (!scan.hasFiles) return null

        val dir = File(baseDir, gameId).apply { mkdirs() }
        val file = File(dir, "backup_${LocalDateTime.now().format(nameFormat)}.zip")
        folder.zipTo(file)

        list(gameId).drop(maxBackups.coerceAtLeast(1)).forEach { it.delete() }
        return file
    }
}

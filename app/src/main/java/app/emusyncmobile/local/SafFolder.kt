package app.emusyncmobile.local

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import app.emusyncmobile.core.FileEntry
import app.emusyncmobile.core.LocalScan
import app.emusyncmobile.core.ZipPaths
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Uma pasta escolhida pelo usuário via Storage Access Framework.
 *
 * Regras importantes:
 * - Nunca apagamos a pasta raiz (o Android revogaria a permissão); só o conteúdo dela.
 * - Não dá para alterar datas de modificação aqui, por isso a detecção de mudanças usa
 *   impressão digital (ver Fingerprint).
 */
class SafFolder(private val context: Context, val treeUri: Uri) {

    private fun root(): DocumentFile? = DocumentFile.fromTreeUri(context, treeUri)

    fun isAccessible(): Boolean = root()?.let { it.exists() && it.canRead() } == true

    fun scan(): LocalScan {
        val root = root()
        if (root == null || !root.exists()) return LocalScan.MISSING
        if (!root.canRead()) throw IOException("Sem permissão para ler a pasta. Escolha a pasta de novo.")
        return LocalScan(folderExists = true, files = listFiles(root).map { it.first })
    }

    /** Compacta o conteúdo da pasta e retorna exatamente o que foi incluído. */
    fun zipTo(dest: File): LocalScan {
        val root = root() ?: throw IOException("Pasta não encontrada")
        val files = listFiles(root)
        dest.parentFile?.mkdirs()
        ZipOutputStream(dest.outputStream().buffered()).use { zip ->
            for ((entry, doc) in files) {
                val zipEntry = ZipEntry(entry.path).apply { time = entry.lastModified }
                zip.putNextEntry(zipEntry)
                context.contentResolver.openInputStream(doc.uri).use { input ->
                    input?.copyTo(zip) ?: throw IOException("Não foi possível ler ${entry.path}")
                }
                zip.closeEntry()
            }
        }
        return LocalScan(folderExists = true, files = files.map { it.first })
    }

    /**
     * Substitui o conteúdo da pasta pelo conteúdo do zip.
     * O zip é validado inteiro antes de qualquer arquivo ser apagado.
     */
    fun replaceContentsWith(zipFile: File) {
        val root = root() ?: throw IOException("Pasta não encontrada")
        if (!root.canWrite()) throw IOException("Sem permissão para gravar na pasta. Escolha a pasta de novo.")

        ZipFile(zipFile).use { zip ->
            val entries = zip.entries().toList().mapNotNull { e ->
                ZipPaths.normalize(e.name)?.let { parts -> e to parts }
            }

            clearContents(root)

            val dirs = HashMap<String, DocumentFile>()
            dirs[""] = root
            for ((entry, parts) in entries) {
                val dir = ensureDir(dirs, parts.dropLast(1))
                val name = parts.last()
                dir.findFile(name)?.delete()
                val target = dir.createFile("application/octet-stream", name)
                    ?: throw IOException("Não foi possível criar ${parts.joinToString("/")}")
                context.contentResolver.openOutputStream(target.uri, "w").use { out ->
                    if (out == null) throw IOException("Não foi possível gravar ${parts.joinToString("/")}")
                    zip.getInputStream(entry).use { it.copyTo(out) }
                }
            }
        }
    }

    private fun ensureDir(cache: MutableMap<String, DocumentFile>, parts: List<String>): DocumentFile {
        var current = cache.getValue("")
        var key = ""
        for (part in parts) {
            key = if (key.isEmpty()) part else "$key/$part"
            current = cache.getOrPut(key) {
                current.findFile(part)?.takeIf { it.isDirectory }
                    ?: current.createDirectory(part)
                    ?: throw IOException("Não foi possível criar a pasta $key")
            }
        }
        return current
    }

    private fun clearContents(root: DocumentFile) {
        for (child in root.listFiles()) {
            if (!child.delete()) throw IOException("Não foi possível apagar ${child.name}")
        }
    }

    private fun listFiles(root: DocumentFile): List<Pair<FileEntry, DocumentFile>> {
        val result = mutableListOf<Pair<FileEntry, DocumentFile>>()
        fun walk(dir: DocumentFile, prefix: String) {
            for (child in dir.listFiles()) {
                val name = child.name ?: continue
                val path = if (prefix.isEmpty()) name else "$prefix/$name"
                if (child.isDirectory) {
                    walk(child, path)
                } else if (child.isFile) {
                    result += FileEntry(path, child.length(), child.lastModified()) to child
                }
            }
        }
        walk(root, "")
        return result
    }

    companion object {
        /** "primary:PSP/SAVEDATA" -> "/storage/emulated/0/PSP/SAVEDATA" */
        fun displayPath(treeUri: Uri): String {
            val docId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull()
                ?: return treeUri.toString()
            val volume = docId.substringBefore(':')
            val path = docId.substringAfter(':', "")
            val base = if (volume.equals("primary", ignoreCase = true)) "/storage/emulated/0" else "/storage/$volume"
            return if (path.isEmpty()) base else "$base/$path"
        }
    }
}

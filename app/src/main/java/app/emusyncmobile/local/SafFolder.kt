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
            for ((entry, uri) in files) {
                val zipEntry = ZipEntry(entry.path).apply { time = entry.lastModified }
                zip.putNextEntry(zipEntry)
                context.contentResolver.openInputStream(uri).use { input ->
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

            // A pasta está vazia agora: dá para criar tudo direto, sem procurar arquivos existentes
            val dirs = HashMap<String, DocumentFile>()
            dirs[""] = root
            val written = HashSet<String>()
            for ((entry, parts) in entries) {
                if (!written.add(parts.joinToString("/"))) continue // entrada repetida no zip
                val dir = ensureDir(dirs, parts.dropLast(1))
                val name = parts.last()
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
            val parent = current
            current = cache.getOrPut(key) {
                parent.createDirectory(part) ?: throw IOException("Não foi possível criar a pasta $key")
            }
        }
        return current
    }

    private fun clearContents(root: DocumentFile) {
        for (child in root.listFiles()) {
            if (!child.delete()) throw IOException("Não foi possível apagar ${child.name}")
        }
    }

    /**
     * Lista todos os arquivos com UMA consulta por pasta (nome, tipo, tamanho e data juntos).
     * O DocumentFile faria uma consulta separada para cada propriedade de cada arquivo,
     * o que deixava pastas com muitos saves bem lentas.
     */
    private fun listFiles(@Suppress("UNUSED_PARAMETER") root: DocumentFile): List<Pair<FileEntry, Uri>> {
        val result = mutableListOf<Pair<FileEntry, Uri>>()
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )

        fun walk(parentDocId: String, prefix: String) {
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
            val subdirs = mutableListOf<Pair<String, String>>()
            context.contentResolver.query(childrenUri, projection, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val docId = c.getString(0) ?: continue
                    val name = c.getString(1) ?: continue
                    val path = if (prefix.isEmpty()) name else "$prefix/$name"
                    if (c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR) {
                        subdirs += docId to path
                    } else {
                        val size = if (c.isNull(3)) 0L else c.getLong(3)
                        val modified = if (c.isNull(4)) 0L else c.getLong(4)
                        val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
                        result += FileEntry(path, size, modified) to uri
                    }
                }
            } ?: throw IOException("Não foi possível ler a pasta. Escolha a pasta de novo.")
            subdirs.forEach { (docId, path) -> walk(docId, path) }
        }

        walk(DocumentsContract.getTreeDocumentId(treeUri), "")
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

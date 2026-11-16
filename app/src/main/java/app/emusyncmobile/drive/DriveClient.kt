package app.emusyncmobile.drive

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

class DriveException(val code: Int, message: String) : IOException(message)

data class DriveFile(
    val id: String,
    val name: String,
    val size: Long?,
    val createdTime: String?,
)

/**
 * Cliente mínimo da API REST do Google Drive v3.
 *
 * Importante: este app NÃO cria arquivos dentro de EmuSync_Data. O EmuSync de PC usa a
 * permissão drive.file e não enxergaria arquivos criados por outro app. Por isso só
 * atualizamos o conteúdo de arquivos que já existem.
 */
class DriveClient(
    private val tokens: TokenProvider,
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.MINUTES)
        .writeTimeout(5, TimeUnit.MINUTES)
        .build(),
) {
    interface TokenProvider {
        suspend fun token(): String
        suspend fun invalidate(token: String)
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val api = "https://www.googleapis.com/drive/v3/files".toHttpUrl()
    private val uploadApi = "https://www.googleapis.com/upload/drive/v3/files".toHttpUrl()

    suspend fun findFolders(name: String): List<DriveFile> = listAll(
        "mimeType='application/vnd.google-apps.folder' and name='$name' and trashed=false"
    )

    suspend fun listChildren(folderId: String): List<DriveFile> = listAll(
        "'$folderId' in parents and trashed=false"
    )

    suspend fun downloadText(fileId: String): String = withContext(Dispatchers.IO) {
        execute { token -> get(mediaUrl(fileId), token) }.use { it.body.string() }
    }

    suspend fun downloadToFile(fileId: String, dest: File) = withContext(Dispatchers.IO) {
        dest.parentFile?.mkdirs()
        execute { token -> get(mediaUrl(fileId), token) }.use { response ->
            dest.outputStream().use { out -> response.body.byteStream().copyTo(out) }
        }
    }

    suspend fun updateContent(fileId: String, mimeType: String, bytes: ByteArray) =
        update(fileId, mimeType, bytes.size.toLong()) { bytes.toRequestBody(mimeType.toMediaType()) }

    suspend fun updateContent(fileId: String, mimeType: String, file: File) =
        update(fileId, mimeType, file.length()) { file.asRequestBody(mimeType.toMediaType()) }

    /** Até 5 MB: upload simples (1 requisição). Acima disso: resumable (2 requisições). */
    private suspend fun update(fileId: String, mimeType: String, length: Long, body: () -> okhttp3.RequestBody) {
        if (length <= SIMPLE_UPLOAD_LIMIT) simpleUpdate(fileId, body) else resumableUpdate(fileId, mimeType, length, body)
    }

    private suspend fun simpleUpdate(fileId: String, body: () -> okhttp3.RequestBody) = withContext(Dispatchers.IO) {
        val url = uploadApi.newBuilder()
            .addPathSegment(fileId)
            .addQueryParameter("uploadType", "media")
            .addQueryParameter("fields", "id")
            .build()
        execute { token ->
            Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $token")
                .patch(body())
                .build()
        }.close()
    }

    /**
     * Upload "resumable" (o upload simples é limitado a 5 MB e memory cards de PS2 têm 8 MB).
     */
    private suspend fun resumableUpdate(
        fileId: String,
        mimeType: String,
        length: Long,
        body: () -> okhttp3.RequestBody,
    ) = withContext(Dispatchers.IO) {
        val startUrl = uploadApi.newBuilder()
            .addPathSegment(fileId)
            .addQueryParameter("uploadType", "resumable")
            .build()

        val sessionUrl = execute { token ->
            Request.Builder()
                .url(startUrl)
                .header("Authorization", "Bearer $token")
                .header("X-Upload-Content-Type", mimeType)
                .header("X-Upload-Content-Length", length.toString())
                .patch("{}".toRequestBody("application/json; charset=UTF-8".toMediaType()))
                .build()
        }.use { it.header("Location") ?: throw IOException("Drive não retornou a URL de upload") }

        execute { token ->
            Request.Builder()
                .url(sessionUrl)
                .header("Authorization", "Bearer $token")
                .put(body())
                .build()
        }.close()
    }

    private suspend fun listAll(query: String): List<DriveFile> = withContext(Dispatchers.IO) {
        val result = mutableListOf<DriveFile>()
        var pageToken: String? = null
        do {
            val url = api.newBuilder()
                .addQueryParameter("q", query)
                .addQueryParameter("spaces", "drive")
                .addQueryParameter("pageSize", "1000")
                .addQueryParameter("fields", "nextPageToken, files(id, name, size, createdTime)")
                .apply { pageToken?.let { addQueryParameter("pageToken", it) } }
                .build()
            val body = execute { token -> get(url, token) }.use { it.body.string() }
            val obj = json.parseToJsonElement(body).jsonObject
            (obj["files"] as? JsonArray)?.forEach { element ->
                val f = element.jsonObject
                result += DriveFile(
                    id = f.string("id")!!,
                    name = f.string("name") ?: "",
                    size = f.string("size")?.toLongOrNull(),
                    createdTime = f.string("createdTime"),
                )
            }
            pageToken = obj.string("nextPageToken")
        } while (pageToken != null)
        result
    }

    private fun mediaUrl(fileId: String): HttpUrl =
        api.newBuilder().addPathSegment(fileId).addQueryParameter("alt", "media").build()

    private fun get(url: HttpUrl, token: String): Request =
        Request.Builder().url(url).header("Authorization", "Bearer $token").get().build()

    /** Executa a requisição; em 401 renova o token uma vez e tenta de novo. */
    private suspend fun execute(build: (String) -> Request): Response {
        var token = tokens.token()
        var response = http.newCall(build(token)).execute()
        if (response.code == 401) {
            response.close()
            tokens.invalidate(token)
            token = tokens.token()
            response = http.newCall(build(token)).execute()
        }
        if (!response.isSuccessful) {
            val code = response.code
            val text = runCatching { response.body.string() }.getOrDefault("")
            response.close()
            throw DriveException(code, "Erro do Google Drive ($code): ${text.take(300)}")
        }
        return response
    }

    private fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

    companion object {
        /** Limite do upload simples da API do Drive. */
        private const val SIMPLE_UPLOAD_LIMIT = 5L * 1024 * 1024
    }
}

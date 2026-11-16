package app.emusyncmobile.auth

import android.content.Context
import android.content.Intent
import android.content.IntentSender
import app.emusyncmobile.drive.DriveClient
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

class NotAuthorizedException : Exception("É preciso entrar com a conta Google")

/**
 * Login e permissão do Google Drive com o Google Identity Services.
 *
 * Usa o escopo "drive" completo (e não "drive.file") para conseguir ler os arquivos que
 * o EmuSync de PC criou. Isso exige um cliente OAuth Android no SEU projeto do Google Cloud
 * (nome do pacote + SHA-1 do certificado). Ver CONFIGURAR-GOOGLE.md.
 */
class GoogleAuth(context: Context) : DriveClient.TokenProvider {
    private val appContext = context.applicationContext
    private val client get() = Identity.getAuthorizationClient(appContext)

    sealed interface Result {
        data class Authorized(val accessToken: String) : Result
        data class NeedsConsent(val intentSender: IntentSender) : Result
    }

    private fun request() = AuthorizationRequest.builder()
        .setRequestedScopes(listOf(Scope(DRIVE_SCOPE)))
        .build()

    suspend fun authorize(): Result {
        val result = client.authorize(request()).await()
        val pending = result.pendingIntent
        return if (result.hasResolution() && pending != null) {
            Result.NeedsConsent(pending.intentSender)
        } else {
            Result.Authorized(result.accessToken ?: throw NotAuthorizedException())
        }
    }

    /** Chamado com o resultado da tela de consentimento do Google. */
    fun handleConsentResult(data: Intent?): Boolean =
        runCatching { client.getAuthorizationResultFromIntent(data).accessToken != null }
            .getOrDefault(false)

    @Volatile private var cachedToken: String? = null
    @Volatile private var cachedAt = 0L

    /** Reaproveita o token por alguns minutos (tokens do Google valem ~1 hora). */
    override suspend fun token(): String {
        val cached = cachedToken
        if (cached != null && System.currentTimeMillis() - cachedAt < TOKEN_REUSE_MS) return cached
        return when (val r = authorize()) {
            is Result.Authorized -> r.accessToken
            is Result.NeedsConsent -> throw NotAuthorizedException()
        }.also {
            cachedToken = it
            cachedAt = System.currentTimeMillis()
        }
    }

    override suspend fun invalidate(token: String) {
        if (cachedToken == token) cachedToken = null
        withContext(Dispatchers.IO) {
            runCatching { GoogleAuthUtil.clearToken(appContext, token) }
        }
    }

    companion object {
        const val DRIVE_SCOPE = "https://www.googleapis.com/auth/drive"
        private const val TOKEN_REUSE_MS = 10 * 60 * 1000L
    }
}

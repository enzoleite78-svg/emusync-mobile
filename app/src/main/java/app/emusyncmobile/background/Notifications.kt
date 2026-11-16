package app.emusyncmobile.background

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.emusyncmobile.sync.AutoSyncResult
import app.emusyncmobile.ui.MainActivity

object Notifications {
    private const val CHANNEL_SYNC = "sync"
    private const val CHANNEL_ATTENTION = "attention"
    private const val ID_RESULT = 1
    private const val ID_ATTENTION = 2

    fun showResult(context: Context, result: AutoSyncResult) {
        if (result.synced.isNotEmpty()) {
            notify(
                context, CHANNEL_SYNC, ID_RESULT,
                title = "Saves sincronizados",
                text = result.synced.joinToString(", "),
            )
        }
        val problems = buildList {
            if (result.conflicts.isNotEmpty()) add("Conflito em: ${result.conflicts.joinToString(", ")}. Abra o app para escolher qual save manter.")
            if (result.failures.isNotEmpty()) add("Falhou: ${result.failures.joinToString("; ")}")
        }
        if (problems.isNotEmpty()) {
            notify(context, CHANNEL_ATTENTION, ID_ATTENTION, title = "EmuSync precisa de atenção", text = problems.joinToString("\n"))
        }
    }

    fun showLoginNeeded(context: Context) = notify(
        context, CHANNEL_ATTENTION, ID_ATTENTION,
        title = "Entre de novo no Google",
        text = "O login expirou. Abra o EmuSync Mobile e toque em \"Entrar com Google\".",
    )

    private fun notify(context: Context, channel: String, id: Int, title: String, text: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return

        ensureChannels(context)
        val open = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, channel)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(id, notification) }
    }

    private fun ensureChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_SYNC, "Sincronização", NotificationManager.IMPORTANCE_LOW)
                .apply { description = "Aviso quando saves são enviados ou baixados" }
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ATTENTION, "Precisa de atenção", NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = "Conflitos, erros e login expirado" }
        )
    }
}

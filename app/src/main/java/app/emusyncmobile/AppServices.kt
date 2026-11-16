package app.emusyncmobile

import android.content.Context
import app.emusyncmobile.auth.GoogleAuth
import app.emusyncmobile.drive.DriveClient
import app.emusyncmobile.local.LocalStore
import app.emusyncmobile.sync.BackupManager
import app.emusyncmobile.sync.SyncEngine
import java.io.File

/** Monta as peças do app. Usado pela tela e pelo sync em segundo plano. */
class AppServices(context: Context) {
    private val app = context.applicationContext

    val auth = GoogleAuth(app)
    val store = LocalStore(File(app.filesDir, "state.json"))
    val engine = SyncEngine(
        context = app,
        drive = DriveClient(auth),
        store = store,
        backups = BackupManager(File(app.filesDir, "game-backups")),
    )
}

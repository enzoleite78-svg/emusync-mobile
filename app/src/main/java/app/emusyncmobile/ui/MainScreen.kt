package app.emusyncmobile.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.emusyncmobile.core.SyncAction
import app.emusyncmobile.sync.GameStatus

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    state: UiState,
    onLogin: () -> Unit,
    onRefresh: () -> Unit,
    onSyncAll: () -> Unit,
    onPickFolder: (String) -> Unit,
    onSync: (String, SyncAction?) -> Unit,
    onAutoSyncChange: (Boolean) -> Unit,
    onGameAutoSyncChange: (String, Boolean) -> Unit,
    onDismissMessages: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("EmuSync Mobile")
                        Text(state.deviceName, style = MaterialTheme.typography.bodySmall)
                    }
                },
                actions = {
                    TextButton(onClick = onRefresh, enabled = !state.loading) { Text("Atualizar") }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())

            LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (state.error != null || state.message != null) {
                    item { MessageCard(state.error, state.message, onDismissMessages) }
                }

                if (state.needsLogin) {
                    item { LoginCard(onLogin) }
                } else if (state.games.isNotEmpty()) {
                    item { AutoSyncCard(state.autoSyncEnabled, onAutoSyncChange) }
                    item {
                        Button(
                            onClick = onSyncAll,
                            enabled = !state.loading,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Sincronizar tudo") }
                    }
                    items(state.games, key = { it.game.id }) { status ->
                        GameCard(
                            status = status,
                            busy = state.busyGameId == status.game.id,
                            enabled = !state.loading,
                            onPickFolder = { onPickFolder(status.game.id) },
                            onSync = { force -> onSync(status.game.id, force) },
                            showAutoSwitch = state.autoSyncEnabled,
                            onAutoSyncChange = { onGameAutoSyncChange(status.game.id, it) },
                        )
                    }
                } else if (!state.loading) {
                    item { Text("Nenhum jogo encontrado. Adicione jogos no EmuSync do PC.") }
                }
            }
        }
    }
}

@Composable
private fun AutoSyncCard(enabled: Boolean, onChange: (Boolean) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Sync automático", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Verifica os saves a cada ~15 minutos, mesmo com o app fechado. " +
                        "Conflitos nunca são resolvidos sozinhos: você recebe uma notificação.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Switch(checked = enabled, onCheckedChange = onChange)
        }
    }
}

@Composable
private fun LoginCard(onLogin: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Conectar ao Google Drive", style = MaterialTheme.typography.titleMedium)
            Text("Use a mesma conta Google configurada no EmuSync do PC.")
            Button(onClick = onLogin) { Text("Entrar com Google") }
        }
    }
}

@Composable
private fun MessageCard(error: String?, message: String?, onDismiss: () -> Unit) {
    val isError = error != null
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isError) MaterialTheme.colorScheme.errorContainer
            else MaterialTheme.colorScheme.secondaryContainer,
        ),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(error ?: message.orEmpty(), Modifier.weight(1f))
            TextButton(onClick = onDismiss) { Text("OK") }
        }
    }
}

@Composable
private fun GameCard(
    status: GameStatus,
    busy: Boolean,
    enabled: Boolean,
    onPickFolder: () -> Unit,
    onSync: (SyncAction?) -> Unit,
    showAutoSwitch: Boolean,
    onAutoSyncChange: (Boolean) -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    status.game.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            }

            val (label, color) = describe(status.action)
            Text(label, color = color, style = MaterialTheme.typography.bodyMedium)

            Text(
                status.local?.displayPath ?: "Pasta não definida neste celular",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "Nuvem: ${formatBytes(status.game.storageBytes)}" +
                    (status.scan?.let { " · Celular: ${formatBytes(it.totalBytes)} (${it.fileCount} arquivos)" } ?: ""),
                style = MaterialTheme.typography.bodySmall,
            )
            status.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }

            if (showAutoSwitch && status.local != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Incluir no sync automático", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Switch(checked = status.local.autoSync, onCheckedChange = onAutoSyncChange, enabled = enabled)
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onPickFolder, enabled = enabled) {
                    Text(if (status.local == null) "Escolher pasta" else "Trocar pasta")
                }
                when (status.action) {
                    SyncAction.UPLOAD, SyncAction.DOWNLOAD ->
                        Button(onClick = { onSync(null) }, enabled = enabled) { Text("Sincronizar") }
                    else -> {}
                }
            }

            if (status.action == SyncAction.CONFLICT) {
                Text(
                    "O save mudou no celular e na nuvem. Qual você quer manter? O outro será substituído " +
                        "(ao manter a nuvem, o save do celular fica guardado num backup).",
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { onSync(SyncAction.UPLOAD) }, enabled = enabled) { Text("Manter do celular") }
                    Spacer(Modifier.width(4.dp))
                    OutlinedButton(onClick = { onSync(SyncAction.DOWNLOAD) }, enabled = enabled) { Text("Manter da nuvem") }
                }
            }
        }
    }
}

@Composable
private fun describe(action: SyncAction): Pair<String, Color> {
    val c = MaterialTheme.colorScheme
    return when (action) {
        SyncAction.IN_SYNC -> "✓ Em sincronia" to c.primary
        SyncAction.UPLOAD -> "↑ Save do celular é mais novo (enviar)" to c.tertiary
        SyncAction.DOWNLOAD -> "↓ Save da nuvem é mais novo (baixar)" to c.tertiary
        SyncAction.CONFLICT -> "⚠ Conflito" to c.error
        SyncAction.NO_FOLDER -> "Escolha a pasta de saves deste jogo" to c.onSurfaceVariant
        SyncAction.NEEDS_PC_FIRST -> "Sincronize este jogo uma vez pelo PC primeiro" to c.error
        SyncAction.NOTHING_TO_DO -> "Sem saves ainda" to c.onSurfaceVariant
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
    bytes >= 1024 -> "%.0f KB".format(bytes / 1024.0)
    else -> "$bytes bytes"
}

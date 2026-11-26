package build.raft.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.NonRestartableComposable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import build.raft.app.base.BridgeModule
import build.raft.app.data.SharedConversationDirectoryProjection
import build.raft.app.data.StoredAuthSession
import build.raft.app.model.ServerId

/**
 * The header route diagnostics: on a direct message or a reply thread, logs what the header was
 * projected from and what it shows each time any of those changes. Called every composition of the
 * page from where this code stood, so its remembers and effect keep their keys and their place. The
 * last accepted projection comes as its `State` and is read live, as before.
 */
@Composable
@NonRestartableComposable
internal fun ThreadHeaderRouteDiagnosticsEffect(
    bridge: BridgeModule,
    currentSession: StoredAuthSession?,
    headerProjectionInput: ThreadHeaderProjectionInput,
    conversationDirectoryLookupIds: List<String>,
    directoryConversationProjections: Map<String, SharedConversationDirectoryProjection>,
    directoryConversationProjection: SharedConversationDirectoryProjection?,
    lastAcceptedHeaderProjectionState: State<SharedConversationDirectoryProjection?>,
    headerProjectionSource: String,
    stableHeaderProjection: SharedConversationDirectoryProjection?,
    headerDisplayModel: ThreadHeaderDisplayModel,
    headerProjectionServerId: ServerId?,
    initialPayload: RaftThreadScreenPayload,
    payload: RaftThreadScreenPayload,
) {
    val lastAcceptedHeaderProjection by lastAcceptedHeaderProjectionState
    val headerProjectionMapDiagnostics =
        remember(directoryConversationProjections) {
            directoryConversationProjections.headerProjectionMapDiagnostics()
        }
    val initialHeaderRouteDiagnostics =
        remember(initialPayload) {
            initialPayload.headerRouteDiagnostics()
        }
    val payloadHeaderRouteDiagnostics =
        remember(payload) {
            payload.headerRouteDiagnostics()
        }
    LaunchedEffect(
        currentSession?.currentServerId,
        headerProjectionInput,
        conversationDirectoryLookupIds,
        headerProjectionMapDiagnostics,
        directoryConversationProjection,
        lastAcceptedHeaderProjection,
        headerProjectionSource,
        stableHeaderProjection,
        headerDisplayModel,
        initialHeaderRouteDiagnostics,
        payloadHeaderRouteDiagnostics
    ) {
        if (headerProjectionInput.surfaceKind == RaftThreadSurfaceKind.DirectMessage) {
            bridge.log(
                "thread_header_dm_diag stage=render " +
                    "serverId=${currentSession?.currentServerId ?: "null"} " +
                    "input=${headerProjectionInput.headerProjectionDiagnostics()} " +
                    "initialRoute=$initialHeaderRouteDiagnostics " +
                    "stateRoute=$payloadHeaderRouteDiagnostics " +
                    "projectionMap=$headerProjectionMapDiagnostics " +
                    "current=${directoryConversationProjection.headerProjectionDiagnostics()} " +
                    "previous=${lastAcceptedHeaderProjection.headerProjectionDiagnostics()} " +
                    "stableSource=$headerProjectionSource " +
                    "stable=${stableHeaderProjection.headerProjectionDiagnostics()} " +
                    "final=${headerDisplayModel.headerDisplayDiagnostics()}"
            )
        }
        if (headerProjectionInput.surfaceKind == RaftThreadSurfaceKind.ReplyThread) {
            bridge.log(
                "thread_header_reply_diag stage=render " +
                    "serverId=${headerProjectionServerId?.value ?: "null"} " +
                    "input=${headerProjectionInput.headerProjectionDiagnostics()} " +
                    "initialRoute=$initialHeaderRouteDiagnostics " +
                    "stateRoute=$payloadHeaderRouteDiagnostics " +
                    "projectionMap=$headerProjectionMapDiagnostics " +
                    "current=${directoryConversationProjection.headerProjectionDiagnostics()} " +
                    "previous=${lastAcceptedHeaderProjection.headerProjectionDiagnostics()} " +
                    "stableSource=$headerProjectionSource " +
                    "stable=${stableHeaderProjection.headerProjectionDiagnostics()} " +
                    "final=${headerDisplayModel.headerDisplayDiagnostics()}"
            )
        }
    }
}

package build.raft.app.desktop

import kotlinx.serialization.json.JsonObject

/** `reportRealTime` host. TODO(desktop): Socket.IO client (port of SocketIoRealtimeClient). */
object RaftDesktopRealtimeHost {
    fun install() = Unit

    fun report(params: JsonObject) {
        log("reportRealTime action=${params.string("action")} serverId=${params.string("serverId")}")
    }
}

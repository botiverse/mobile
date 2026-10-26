package build.raft.app.desktop

import kotlinx.serialization.json.JsonObject

/**
 * `settingsCapability` (storage, about, developer environment, diagnostics, notifications, app
 * login, ...). TODO(desktop): every action answers "not implemented" in M1; the shared settings
 * pages fall back as they do for an unsupported host action.
 */
object RaftDesktopSettingsCapability {
    fun handle(command: JsonObject, callback: ((String?) -> Unit)?) {
        log("settingsCapability not implemented: action=${command.string("action")}")
        callback?.invoke(RaftDesktopBridgeTransport.NOT_IMPLEMENTED)
    }
}

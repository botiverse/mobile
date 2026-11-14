package build.raft.realtime.socket

import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.plugins.websocket.WebSockets

actual fun createRaftRealtimeHttpClient(): HttpClient =
    HttpClient(Darwin) {
        install(WebSockets)
    }

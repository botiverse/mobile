package build.raft.realtime.socket

import io.ktor.client.HttpClient

/**
 * The platform HttpClient for realtime WebSockets, with the Ktor WebSockets plugin: OkHttp on Android
 * and desktop (system proxy and TLS as the app's HTTP), Darwin (NSURLSessionWebSocketTask, system
 * trust) on iOS. No WebSocket-level ping: liveness is Engine.IO's server ping, timed from the open packet.
 */
expect fun createRaftRealtimeHttpClient(): HttpClient

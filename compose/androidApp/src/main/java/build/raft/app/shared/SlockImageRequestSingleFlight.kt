package build.raft.app.shared

import build.raft.app.data.SlockAvatarMountedRenderDescriptor
import java.net.URI

internal data class SlockMountedAvatarRequestKey(
    val partitionKey: String,
    val subjectKey: String,
    val bytesKey: String,
    val commitGeneration: Long,
    val commitNonce: String,
    val modelRevision: Long,
    val requestWidth: Int,
    val requestHeight: Int,
    val needResize: Boolean,
    val scaleType: String
) {
    companion object {
        fun create(
            descriptor: SlockAvatarMountedRenderDescriptor,
            requestWidth: Int,
            requestHeight: Int,
            needResize: Boolean,
            scaleType: String
        ): SlockMountedAvatarRequestKey =
            SlockMountedAvatarRequestKey(
                partitionKey = descriptor.slot.nativePartitionKey,
                subjectKey = descriptor.slot.nativeSubjectKey,
                bytesKey = descriptor.slot.nativeBytesKey,
                commitGeneration = descriptor.commitIdentity.commitGeneration,
                commitNonce = descriptor.commitIdentity.nonce,
                modelRevision = descriptor.modelRevision,
                requestWidth = requestWidth.coerceAtLeast(0),
                requestHeight = requestHeight.coerceAtLeast(0),
                needResize = needResize,
                scaleType = scaleType
            )
    }
}

internal data class SlockNetworkImageRequestKey(
    val urlIdentity: String,
    val requestWidth: Int,
    val requestHeight: Int,
    val needResize: Boolean,
    val scaleType: String
) {
    companion object {
        fun create(
            url: String,
            requestWidth: Int,
            requestHeight: Int,
            needResize: Boolean,
            scaleType: String
        ): SlockNetworkImageRequestKey =
            SlockNetworkImageRequestKey(
                urlIdentity = url.slockNetworkImageUrlIdentity(),
                requestWidth = requestWidth.coerceAtLeast(0),
                requestHeight = requestHeight.coerceAtLeast(0),
                needResize = needResize,
                scaleType = scaleType
            )
    }
}

internal enum class SlockImageFlightDisposition {
    Started,
    Joined
}

internal class SlockImageFlightSubscription internal constructor(
    val disposition: SlockImageFlightDisposition,
    private val detachCallback: () -> Unit
) {
    fun detach() {
        detachCallback()
    }
}

/**
 * Process-safe callback fanout for in-progress image fetch/decode work.
 *
 * A subscriber detaches only its callback. The shared producer is deliberately
 * not canceled: the Android Kuikly image adapter has no per-view cancellation
 * token, and another view may still be waiting for the same request identity.
 */
internal class SlockImageRequestSingleFlight<K : Any, V : Any> {
    private class Flight<V : Any> {
        val callbacks = linkedMapOf<Long, (Result<V>) -> Unit>()
    }

    private val lock = Any()
    private val active = mutableMapOf<K, Flight<V>>()
    private var nextSubscriberId = 0L

    fun load(
        key: K,
        start: (complete: (Result<V>) -> Unit) -> Unit,
        callback: (Result<V>) -> Unit
    ): SlockImageFlightSubscription {
        lateinit var flight: Flight<V>
        var shouldStart = false
        val subscriberId: Long
        synchronized(lock) {
            val existing = active[key]
            if (existing != null) {
                flight = existing
            } else {
                flight = Flight()
                active[key] = flight
                shouldStart = true
            }
            subscriberId = nextSubscriberId++
            flight.callbacks[subscriberId] = callback
        }

        if (shouldStart) {
            runCatching {
                start { result -> complete(key, flight, result) }
            }.onFailure { throwable ->
                complete(key, flight, Result.failure(throwable))
            }
        }

        return SlockImageFlightSubscription(
            disposition =
                if (shouldStart) {
                    SlockImageFlightDisposition.Started
                } else {
                    SlockImageFlightDisposition.Joined
                },
            detachCallback = {
                synchronized(lock) {
                    if (active[key] === flight) {
                        flight.callbacks.remove(subscriberId)
                    }
                }
            }
        )
    }

    internal fun activeFlightCount(): Int =
        synchronized(lock) {
            active.size
        }

    private fun complete(key: K, flight: Flight<V>, result: Result<V>) {
        val callbacks =
            synchronized(lock) {
                if (active[key] !== flight) {
                    return
                }
                active.remove(key)
                flight.callbacks.values.toList().also { flight.callbacks.clear() }
            }
        callbacks.forEach { callback ->
            runCatching { callback(result) }
        }
    }
}

internal fun String.slockNetworkImageUrlIdentity(): String {
    val raw = trim()
    val uri = runCatching { URI(raw) }.getOrNull() ?: return raw
    val scheme = uri.scheme?.lowercase() ?: return raw
    val host = uri.host?.lowercase() ?: return raw
    val path = uri.rawPath?.takeIf { it.isNotBlank() } ?: return raw
    val userInfo = uri.rawUserInfo?.let { "$it@" }.orEmpty()
    val port = uri.port.takeIf { it >= 0 }?.let { ":$it" }.orEmpty()
    val base = "$scheme://$userInfo$host$port$path"
    val normalizedQuery =
        if (host.isGravatarHost() && path.isGravatarAvatarPath()) {
            uri.rawQuery.withoutGravatarSizeParameters()
        } else {
            uri.rawQuery
        }
    val query = normalizedQuery?.takeIf { it.isNotBlank() }?.let { "?$it" }.orEmpty()
    val fragment = uri.rawFragment?.let { "#$it" }.orEmpty()
    return base + query + fragment
}

private fun String.isGravatarHost(): Boolean =
    this == "gravatar.com" || endsWith(".gravatar.com")

private fun String.isGravatarAvatarPath(): Boolean =
    lowercase().let { path -> path == "/avatar" || path.startsWith("/avatar/") }

private fun String?.withoutGravatarSizeParameters(): String? =
    this?.split('&')
        ?.filterNot { parameter ->
            parameter.substringBefore('=').lowercase() in setOf("s", "size", "sz")
        }
        ?.joinToString("&")
        ?.takeIf { it.isNotBlank() }

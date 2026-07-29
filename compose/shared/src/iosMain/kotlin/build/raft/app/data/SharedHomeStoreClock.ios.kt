@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package build.raft.app.data

import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import platform.posix.gettimeofday
import platform.posix.timeval

internal actual fun legacyPlatformNowMillis(): Long =
    memScoped {
        val now = alloc<timeval>()
        gettimeofday(now.ptr, null)
        now.tv_sec.toLong() * 1_000L + now.tv_usec.toLong() / 1_000L
    }

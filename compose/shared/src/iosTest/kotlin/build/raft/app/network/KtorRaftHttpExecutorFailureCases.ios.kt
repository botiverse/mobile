package build.raft.app.network

import io.ktor.client.engine.darwin.DarwinHttpRequestException
import platform.Foundation.NSError
import platform.Foundation.NSURLErrorDomain

internal actual fun platformFailureCases(): List<Pair<Throwable, String>> =
    listOf(
        -1001L to "TIMEOUT",
        -1003L to "DNS",
        -1004L to "CONNECT",
        -1009L to "CONNECT",
        -1202L to "TLS",
        -1005L to "UNKNOWN",
    ).map { (code, kind) ->
        DarwinHttpRequestException(NSError.errorWithDomain(NSURLErrorDomain, code, null)) to kind
    }

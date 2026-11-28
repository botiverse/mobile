package build.raft.app.model

import platform.Foundation.NSString
import platform.Foundation.decomposedStringWithCanonicalMapping
import platform.Foundation.precomposedStringWithCompatibilityMapping

@Suppress("CAST_NEVER_SUCCEEDS")
internal actual fun raftNormalizeNfkc(text: String): String =
    (text as NSString).precomposedStringWithCompatibilityMapping

@Suppress("CAST_NEVER_SUCCEEDS")
internal actual fun raftNormalizeNfd(text: String): String =
    (text as NSString).decomposedStringWithCanonicalMapping

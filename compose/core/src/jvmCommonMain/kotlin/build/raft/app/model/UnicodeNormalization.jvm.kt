package build.raft.app.model

import java.text.Normalizer

internal actual fun raftNormalizeNfkc(text: String): String =
    Normalizer.normalize(text, Normalizer.Form.NFKC)

internal actual fun raftNormalizeNfd(text: String): String =
    Normalizer.normalize(text, Normalizer.Form.NFD)

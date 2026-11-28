package build.raft.app.data

import java.text.Normalizer

internal actual fun normalizeUnicodeNfkc(text: String): String =
    Normalizer.normalize(text, Normalizer.Form.NFKC)

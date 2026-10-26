package build.raft.app

/**
 * The composeResources values directory of a catalog the resource tests name by its former
 * kmmresource folder: "base" (English) -> values, "zh-rCN" -> values-zh-rCN, "ja" -> values-ja.
 */
internal fun raftComposeValuesDir(locale: String): String = if (locale == "base") "values" else "values-$locale"

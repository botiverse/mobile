package build.raft.app

import build.raft.app.data.AppLocale

/**
 * Android resolves KmmResource strings against the process-local
 * `StringDesc.localeType` directly; no host signal needed (task #190).
 */
internal actual fun applyAppLocaleOverride(locale: AppLocale) = Unit

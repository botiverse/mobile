package build.raft.app

import build.raft.app.data.AppLocale

/**
 * Android and desktop resolve strings against the process-local
 * `StringDesc.localeType` directly; no host signal needed (task #190).
 */
internal actual fun applyAppLocaleOverride(locale: AppLocale) = Unit

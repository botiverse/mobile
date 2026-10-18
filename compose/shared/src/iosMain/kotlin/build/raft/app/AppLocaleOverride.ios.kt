package build.raft.app

import build.raft.app.data.AppLocale

/**
 * iOS resolves KmmResource strings against the process-local
 * `StringDesc.localeType` (NSBundle-backed `localized()`); no host signal
 * needed (task #190).
 */
internal actual fun applyAppLocaleOverride(locale: AppLocale) = Unit

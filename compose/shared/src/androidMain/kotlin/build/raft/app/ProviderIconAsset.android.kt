package build.raft.app

// Android renders the inline SVG data URI fine; keep using it (return null so the
// caller falls back to the existing data URI). task #436.
internal actual fun providerIconIosSvgSource(key: ProviderIconKey): String? = null

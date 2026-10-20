package build.raft.app

import build.raft.app.data.SharedMessageTranslationEntry
import build.raft.app.data.SharedMessageTranslationSettings
import build.raft.app.data.SharedMessageTranslationState
import build.raft.app.data.SharedMessageTranslationStatus
import build.raft.app.data.TranslationModePreference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun availableSettings(
    mode: TranslationModePreference = TranslationModePreference.Automatic
): SharedMessageTranslationSettings =
    SharedMessageTranslationSettings(
        loaded = true,
        serverId = "server-1",
        serverTranslationEnabled = true,
        providerAvailable = true,
        translationMode = mode,
        effectiveLanguage = "en"
    )

private fun message(
    id: String = "m1",
    body: String = "你好",
    isOwn: Boolean = false,
    messageType: String = "Chat"
): RaftThreadMessageItem =
    RaftThreadMessageItem(
        id = id,
        authorId = "a1",
        authorName = "Alice",
        isOwn = isOwn,
        messageType = messageType,
        body = body
    )

private fun translatedEntry(
    id: String = "m1",
    original: String = "你好",
    translated: String = "Hello",
    showOriginal: Boolean? = null
): SharedMessageTranslationEntry =
    SharedMessageTranslationEntry(
        messageId = id,
        status = SharedMessageTranslationStatus.Translated,
        translatedContent = translated,
        originalContent = original,
        showOriginal = showOriginal
    )

private fun state(
    entry: SharedMessageTranslationEntry?,
    settings: SharedMessageTranslationSettings = availableSettings()
): SharedMessageTranslationState =
    SharedMessageTranslationState(
        settings = settings,
        entries = entry?.let { mapOf(it.messageId to it) } ?: emptyMap()
    )

class ThreadMessageTranslationUiTest {
    @Test
    fun translatedEntrySubstitutesBodyAndOffersShowOriginal() {
        val ui = resolveThreadMessageTranslationUi(message(), state(translatedEntry()))

        checkNotNull(ui)
        assertEquals("Hello", ui.displayBody)
        assertFalse(ui.showOriginal)
        assertEquals("Show original", ui.indicator?.actionLabel)
        assertEquals(ThreadMessageTranslationIndicatorAction.ToggleOriginal, ui.indicator?.action)
    }

    @Test
    fun showOriginalKeepsBodyAndOffersShowTranslation() {
        val ui = resolveThreadMessageTranslationUi(message(), state(translatedEntry(showOriginal = true)))

        checkNotNull(ui)
        assertNull(ui.displayBody)
        assertTrue(ui.showOriginal)
        assertEquals("Show translation", ui.indicator?.actionLabel)
    }

    @Test
    fun originalModeMakesMessagesIneligible() {
        assertNull(
            resolveThreadMessageTranslationUi(
                message(),
                state(translatedEntry(), settings = availableSettings(mode = TranslationModePreference.Original))
            )
        )
    }

    @Test
    fun manualModeStillRendersEntries() {
        val ui =
            resolveThreadMessageTranslationUi(
                message(),
                state(translatedEntry(), settings = availableSettings(mode = TranslationModePreference.Manual))
            )

        checkNotNull(ui)
        assertEquals("Hello", ui.displayBody)
    }

    @Test
    fun pendingEntryShowsPlaceholderAndTranslatingIndicator() {
        val entry =
            SharedMessageTranslationEntry(
                messageId = "m1",
                status = SharedMessageTranslationStatus.Pending,
                originalContent = "你好"
            )

        val ui = resolveThreadMessageTranslationUi(message(), state(entry))

        checkNotNull(ui)
        assertTrue(ui.pendingPlaceholder)
        assertEquals("Translating…", ui.indicator?.message)
    }

    @Test
    fun failedEntryOffersRetry() {
        val entry =
            SharedMessageTranslationEntry(
                messageId = "m1",
                status = SharedMessageTranslationStatus.Failed,
                reason = "provider",
                originalContent = "你好"
            )

        val ui = resolveThreadMessageTranslationUi(message(), state(entry))

        checkNotNull(ui)
        assertEquals("Retry", ui.indicator?.actionLabel)
        assertEquals(ThreadMessageTranslationIndicatorAction.Retry, ui.indicator?.action)
    }

    @Test
    fun silentSkipRendersNothing() {
        val entry =
            SharedMessageTranslationEntry(
                messageId = "m1",
                status = SharedMessageTranslationStatus.Skipped,
                reason = "same_language",
                originalContent = "你好"
            )

        assertNull(resolveThreadMessageTranslationUi(message(), state(entry)))
    }

    @Test
    fun staleEntryForEditedMessageRendersNothing() {
        val ui =
            resolveThreadMessageTranslationUi(
                message(body = "新内容"),
                state(translatedEntry(original = "你好"))
            )

        assertNull(ui)
    }

    @Test
    fun ownSystemAndUnavailableMessagesAreNotEligible() {
        assertNull(resolveThreadMessageTranslationUi(message(isOwn = true), state(translatedEntry())))
        assertNull(resolveThreadMessageTranslationUi(message(messageType = "System"), state(translatedEntry())))
        assertNull(
            resolveThreadMessageTranslationUi(
                message(),
                state(
                    translatedEntry(),
                    settings = availableSettings().copy(serverTranslationEnabled = false)
                )
            )
        )
        assertNull(
            resolveThreadMessageTranslationUi(
                message(),
                state(translatedEntry(), settings = availableSettings().copy(effectiveLanguage = null))
            )
        )
    }
}

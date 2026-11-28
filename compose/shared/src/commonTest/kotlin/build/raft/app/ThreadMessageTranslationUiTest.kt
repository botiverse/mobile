package build.raft.app

import build.raft.app.data.SharedMessageTranslationEntry
import build.raft.app.data.SharedMessageTranslationSettings
import build.raft.app.data.SharedMessageTranslationState
import build.raft.app.data.SharedMessageTranslationStatus
import build.raft.app.data.TranslationDisplayPreference
import build.raft.app.data.TranslationModePreference
import build.raft.app.thread.RaftThreadMessageItem
import build.raft.app.thread.ThreadMessageTranslationIndicatorAction
import build.raft.app.thread.resolveThreadMessageTranslationUi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun availableSettings(
    mode: TranslationModePreference = TranslationModePreference.Automatic,
    preferredDisplay: TranslationDisplayPreference = TranslationDisplayPreference.Translated,
): SharedMessageTranslationSettings =
    SharedMessageTranslationSettings(
        loaded = true,
        serverId = "server-1",
        serverTranslationEnabled = true,
        providerAvailable = true,
        translationMode = mode,
        effectiveLanguage = "en",
        preferredTranslationDisplay = preferredDisplay,
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
    fun staleEntryForEditedMessageRendersNothing() {
        val ui =
            resolveThreadMessageTranslationUi(
                message(body = "新内容"),
                state(translatedEntry(original = "你好"))
            )

        assertNull(ui)
    }

    @Test
    fun bilingualDefaultViewShowsTranslatedBodyAndOriginalBlockAndTogglesBackToOriginal() {
        val bilingualSettings = availableSettings(preferredDisplay = TranslationDisplayPreference.Bilingual)

        val defaultUi = resolveThreadMessageTranslationUi(message(), state(translatedEntry(), settings = bilingualSettings))
        checkNotNull(defaultUi)
        assertEquals("Hello", defaultUi.displayBody)
        assertEquals("你好", defaultUi.bilingualOriginalBody)
        assertFalse(defaultUi.showOriginal)
        assertEquals("Show original", defaultUi.indicator?.actionLabel)

        val toggledOriginal =
            resolveThreadMessageTranslationUi(message(), state(translatedEntry(showOriginal = true), settings = bilingualSettings))
        checkNotNull(toggledOriginal)
        assertNull(toggledOriginal.displayBody)
        assertNull(toggledOriginal.bilingualOriginalBody)
        assertTrue(toggledOriginal.showOriginal)
        assertEquals("Show translation", toggledOriginal.indicator?.actionLabel)

        val toggledBack =
            resolveThreadMessageTranslationUi(message(), state(translatedEntry(showOriginal = false), settings = bilingualSettings))
        checkNotNull(toggledBack)
        assertEquals("Hello", toggledBack.displayBody)
        assertEquals("你好", toggledBack.bilingualOriginalBody)
        assertFalse(toggledBack.showOriginal)
    }

    @Test
    fun originalDefaultViewKeepsOriginalUntilToggledToTranslatedAndSkipsPendingPlaceholder() {
        val originalSettings = availableSettings(preferredDisplay = TranslationDisplayPreference.Original)

        val defaultUi = resolveThreadMessageTranslationUi(message(), state(translatedEntry(), settings = originalSettings))
        checkNotNull(defaultUi)
        assertNull(defaultUi.displayBody)
        assertNull(defaultUi.bilingualOriginalBody)
        assertTrue(defaultUi.showOriginal)
        assertEquals("Show translation", defaultUi.indicator?.actionLabel)

        val toggledTranslated =
            resolveThreadMessageTranslationUi(message(), state(translatedEntry(showOriginal = false), settings = originalSettings))
        checkNotNull(toggledTranslated)
        assertEquals("Hello", toggledTranslated.displayBody)
        assertNull(toggledTranslated.bilingualOriginalBody)
        assertFalse(toggledTranslated.showOriginal)
        assertEquals("Show original", toggledTranslated.indicator?.actionLabel)

        val pendingEntry =
            SharedMessageTranslationEntry(
                messageId = "m1",
                status = SharedMessageTranslationStatus.Pending,
                originalContent = "你好",
            )
        val pendingUi = resolveThreadMessageTranslationUi(message(), state(pendingEntry, settings = originalSettings))
        checkNotNull(pendingUi)
        assertFalse(pendingUi.pendingPlaceholder)
        assertTrue(pendingUi.showOriginal)
    }
}

package build.raft.app

import build.raft.app.data.TranslationModePreference
import build.raft.app.data.TranslationTargetLanguages
import build.raft.app.resources.*
import build.raft.app.settings.settingsTranslationModeLabelRes
import build.raft.app.settings.settingsTranslationTargetLanguageRes
import build.raft.app.thread.key
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Settings options the store labels in English (as ids) read from the catalog by their key:
 * translation mode, translation target language, message font size.
 */
class SettingsLocalizedOptionLabelsTest {
    @Test
    fun everyTranslationModeHasItsWord() {
        TranslationModePreference.entries.forEach { mode ->
            assertNotNull(settingsTranslationModeLabelRes(mode.storageKey), mode.storageKey)
        }
        assertEquals(Res.string.settings_translation_mode_auto, settingsTranslationModeLabelRes("automatic"))
        assertEquals(Res.string.settings_translation_mode_off, settingsTranslationModeLabelRes("original"))
        assertNull(settingsTranslationModeLabelRes("auto"))
    }

    @Test
    fun everyTranslationTargetIsNamedInTheUiLanguage() {
        TranslationTargetLanguages.options
            .filter { it.key != TranslationTargetLanguages.System }
            .forEach { option -> assertNotNull(settingsTranslationTargetLanguageRes(option.key), option.key) }
        assertEquals(Res.string.settings_translation_target_language_pt_br, settingsTranslationTargetLanguageRes("pt-br"))
        // "Same as display language" has its own label; an unknown saved code keeps its stored label.
        assertNull(settingsTranslationTargetLanguageRes(TranslationTargetLanguages.System))
        assertNull(settingsTranslationTargetLanguageRes("nl"))
    }
}

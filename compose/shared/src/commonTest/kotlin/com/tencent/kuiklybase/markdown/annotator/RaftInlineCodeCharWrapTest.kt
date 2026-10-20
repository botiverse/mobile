package com.tencent.kuiklybase.markdown.annotator

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// task #401 A-bridge: char-level wrap break interleaving for platforms whose
// native render lacks inline-code atomization (OHOS/iOS). These tests cover
// the transform itself; they are target-independent (the platform gate that
// decides WHETHER to apply it is an expect/actual returning null on Android).
class RaftInlineCodeCharWrapTest {
    private val zwsp = '\u200B'

    @Test
    fun shortTextIsUntouched() {
        val text = "gradle build"
        assertEquals(text, text.withRaftInlineCodeCharWrapBreaks(16))
    }

    @Test
    fun longRunGetsBreakBetweenEveryCharacter() {
        val run = "sharedSettingsReleaseNotesUrl"
        val result = run.withRaftInlineCodeCharWrapBreaks(16)
        assertEquals(run, result.filterNot { it == zwsp })
        assertEquals(run.length - 1, result.count { it == zwsp })
        assertFalse(result.startsWith(zwsp))
        assertFalse(result.endsWith(zwsp))
    }

    @Test
    fun onlyRunsOverThresholdAreTransformed() {
        val text = "val x = reallyReallyLongIdentifierName"
        val result = text.withRaftInlineCodeCharWrapBreaks(16)
        val segments = result.split(' ')
        assertEquals("val", segments[0])
        assertEquals("x", segments[1])
        assertEquals("=", segments[2])
        assertTrue(segments[3].contains(zwsp))
        assertEquals(text, result.filterNot { it == zwsp })
    }

    @Test
    fun longTextOfShortRunsIsUntouched() {
        val text = "one two three four five six"
        assertEquals(text, text.withRaftInlineCodeCharWrapBreaks(16))
    }

    @Test
    fun whitespaceIsPreservedVerbatim() {
        val text = "alpha  beta\naReallyLongUnbrokenCodeRun"
        val result = text.withRaftInlineCodeCharWrapBreaks(16)
        assertEquals(text, result.filterNot { it == zwsp })
        assertTrue(result.contains("alpha  beta\n"))
    }

    @Test
    fun surrogatePairsAreNeverSplit() {
        val run = "😀".repeat(10) + "abcd"
        val result = run.withRaftInlineCodeCharWrapBreaks(16)
        assertEquals(run, result.filterNot { it == zwsp })
        result.forEachIndexed { index, char ->
            if (char == zwsp) {
                assertFalse(result[index + 1].isLowSurrogate(), "break inserted inside a surrogate pair at $index")
                assertFalse(result[index - 1].isHighSurrogate(), "break inserted after a lone high surrogate at $index")
            }
        }
        assertTrue(result.count { it == zwsp } > 0)
    }
}

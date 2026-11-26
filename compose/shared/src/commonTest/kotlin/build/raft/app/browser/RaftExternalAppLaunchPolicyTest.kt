package build.raft.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class RaftExternalAppLaunchPolicyTest {
    @Test
    fun httpAndHttpsOpenExternallyWhenPanelVisible() {
        assertEquals(
            RaftExternalAppLaunchDecision.OpenExternalWeb("https://example.com/page"),
            classifyRaftExternalAppLaunch("https://example.com/page", isUserGesture = false, panelVisible = true),
        )
        assertEquals(
            RaftExternalAppLaunchDecision.OpenExternalWeb("http://example.org/"),
            classifyRaftExternalAppLaunch("http://example.org/", isUserGesture = true, panelVisible = true),
        )
    }

    @Test
    fun hiddenPanelDropsAllExternalLaunches() {
        val web = classifyRaftExternalAppLaunch("https://example.com", isUserGesture = true, panelVisible = false)
        assertEquals(RaftExternalAppLaunchDecision.Drop("panel_not_visible", "https"), web)

        val app = classifyRaftExternalAppLaunch("weixin://dl/business/?t=abc", isUserGesture = true, panelVisible = false)
        assertEquals(RaftExternalAppLaunchDecision.Drop("panel_not_visible", "weixin"), app)
    }

    @Test
    fun nonHttpExternalAppWithoutUserGestureIsDropped() {
        for (url in listOf("weixin://dl/business/?t=1", "alipays://platformapi/startapp", "mailto:hi@example.com", "tel:+14155550100")) {
            val decision = classifyRaftExternalAppLaunch(url, isUserGesture = false, panelVisible = true)
            val drop = assertIs<RaftExternalAppLaunchDecision.Drop>(decision)
            assertEquals("no_user_gesture", drop.reason, "Expected gesture block for $url")
        }
    }

    @Test
    fun dangerousSchemesAreHardDeniedEvenWithUserGesture() {
        val denied =
            listOf(
                "javascript:alert(1)",
                "vbscript:msgbox(1)",
                "data:text/html,<h1>hi</h1>",
                "blob:https://example.com/1234",
                "file:///etc/passwd",
                "about:blank",
                "content://com.example.provider/secret",
                "intent://scan/#Intent;scheme=zxing;package=com.example;end",
                "android-app://com.example.app/https/example.com",
                "chrome://settings",
                "view-source:https://example.com",
                "ftp://files.example.com/a",
                "ws://example.com/socket",
                "wss://example.com/socket",
            )
        for (url in denied) {
            val decision = classifyRaftExternalAppLaunch(url, isUserGesture = true, panelVisible = true)
            val drop = assertIs<RaftExternalAppLaunchDecision.Drop>(decision)
            assertEquals("denied_scheme", drop.reason, "Expected hard deny for $url")
        }
    }

    @Test
    fun validExternalAppSchemesWithGestureRequireConfirmationAndSanitizeDisplay() {
        val weixin =
            assertIs<RaftExternalAppLaunchDecision.ConfirmExternalApp>(
                classifyRaftExternalAppLaunch(
                    "weixin://dl/business/?ticket=secret-token#frag",
                    isUserGesture = true,
                    panelVisible = true,
                ),
            )
        assertEquals("weixin", weixin.scheme)
        assertEquals("weixin://dl", weixin.displayTarget)

        val mailto =
            assertIs<RaftExternalAppLaunchDecision.ConfirmExternalApp>(
                classifyRaftExternalAppLaunch(
                    "mailto:support@raft.build?subject=hi&body=secret",
                    isUserGesture = true,
                    panelVisible = true,
                ),
            )
        assertEquals("mailto", mailto.scheme)
        assertEquals("mailto:support@raft.build", mailto.displayTarget)

        val tel =
            assertIs<RaftExternalAppLaunchDecision.ConfirmExternalApp>(
                classifyRaftExternalAppLaunch("tel:+14155550100", isUserGesture = true, panelVisible = true),
            )
        assertEquals("tel", tel.scheme)
        assertEquals("tel:+14155550100", tel.displayTarget)
    }

    @Test
    fun displayTargetStripsBidiOverridesUserinfoAndCapsLength() {
        val spoofed = "myapp://evil@good.example.com/\u202Epath?token=123"
        val formatted = formatRaftExternalAppDisplayTarget(spoofed, "myapp")
        assertEquals("myapp://good.example.com", formatted)

        val backslashSpoofed = "myapp://evil\\@good.example.com/path"
        assertEquals("myapp://evil", formatRaftExternalAppDisplayTarget(backslashSpoofed, "myapp"))

        val longHost = "myapp://" + "a".repeat(120) + ".example.com"
        val capped = formatRaftExternalAppDisplayTarget(longHost, "myapp")
        assertTrue(capped.length <= 80)
        assertTrue(capped.endsWith("…"))
    }

    @Test
    fun malformedUrlsUnicodeHomoglyphsAndEmptyTargetsAreDropped() {
        assertIs<RaftExternalAppLaunchDecision.Drop>(
            classifyRaftExternalAppLaunch("", isUserGesture = true, panelVisible = true),
        )
        assertIs<RaftExternalAppLaunchDecision.Drop>(
            classifyRaftExternalAppLaunch("mailto:", isUserGesture = true, panelVisible = true),
        )
        assertIs<RaftExternalAppLaunchDecision.Drop>(
            classifyRaftExternalAppLaunch("weixin://dl\nspoof", isUserGesture = true, panelVisible = true),
        )
        assertIs<RaftExternalAppLaunchDecision.Drop>(
            classifyRaftExternalAppLaunch("1bad://host", isUserGesture = true, panelVisible = true),
        )
        val cyrillicJs =
            assertIs<RaftExternalAppLaunchDecision.Drop>(
                classifyRaftExternalAppLaunch("\u0458avascript:alert(1)", isUserGesture = true, panelVisible = true),
            )
        assertEquals("invalid_scheme", cyrillicJs.reason)
    }
}

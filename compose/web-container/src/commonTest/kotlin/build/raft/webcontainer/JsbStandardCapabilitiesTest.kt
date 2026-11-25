@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package build.raft.webcontainer

import build.raft.webcontainer.jsb.JsbAppInfo
import build.raft.webcontainer.jsb.JsbAuthStatus
import build.raft.webcontainer.jsb.JsbCallContext
import build.raft.webcontainer.jsb.JsbDispatcher
import build.raft.webcontainer.jsb.JsbErrorCode
import build.raft.webcontainer.jsb.JsbHapticStyle
import build.raft.webcontainer.jsb.JsbPolicy
import build.raft.webcontainer.jsb.JsbRegistry
import build.raft.webcontainer.jsb.JsbStandardCapabilities
import build.raft.webcontainer.jsb.JsbStandardEvents
import build.raft.webcontainer.jsb.JsbStandardHost
import build.raft.webcontainer.jsb.JsbStandardMethods
import build.raft.webcontainer.jsb.JsbThemeInfo
import build.raft.webcontainer.jsb.JsbToastDuration
import build.raft.webcontainer.jsb.buildThemeChangedEventPayload
import build.raft.webcontainer.jsb.isSafeJsbExternalUrl
import build.raft.webcontainer.jsb.registerStandardCapabilities
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class JsbStandardCapabilitiesTest {
    private val trustedOrigin = parseWebContainerOrigin("https://app.raft.build")!!

    private class FakeStandardHost : JsbStandardHost {
        val haptics = mutableListOf<JsbHapticStyle>()
        val copied = mutableListOf<String>()
        val toasts = mutableListOf<Pair<String, JsbToastDuration>>()
        val externalUrls = mutableListOf<String>()
        val closeReasons = mutableListOf<String?>()

        override suspend fun getAppInfo(context: JsbCallContext): JsbAppInfo =
            JsbAppInfo(
                appVersion = "1.30.0",
                buildNumber = "13000001",
                platform = "android",
                osVersion = "15",
                locale = "zh-CN",
            )

        override suspend fun getThemeInfo(context: JsbCallContext): JsbThemeInfo =
            JsbThemeInfo(isDark = true)

        override suspend fun triggerHaptic(context: JsbCallContext, style: JsbHapticStyle): Boolean {
            haptics += style
            return true
        }

        override suspend fun copyText(context: JsbCallContext, text: String): Boolean {
            copied += text
            return true
        }

        override suspend fun showToast(context: JsbCallContext, message: String, duration: JsbToastDuration): Boolean {
            toasts += message to duration
            return true
        }

        override suspend fun openExternalUrl(context: JsbCallContext, url: String): Boolean {
            externalUrls += url
            return true
        }

        override suspend fun closeContainer(context: JsbCallContext, reason: String?): Boolean {
            closeReasons += reason
            return true
        }

        override suspend fun getAuthStatus(context: JsbCallContext): JsbAuthStatus =
            JsbAuthStatus(authenticated = true, accountId = "u-1", serverId = "s-1")
    }

    @Test
    fun allEightStandardMethodsExecuteThroughKotlinDispatcher() = runTest {
        val host = FakeStandardHost()
        val registry = JsbRegistry().registerStandardCapabilities(host)
        val policy =
            JsbPolicy(
                enabled = true,
                allowedOrigins = setOf(trustedOrigin),
                grantedCapabilities = JsbStandardCapabilities.All,
            )
        val dispatcher = JsbDispatcher(policy = policy, registry = registry, scope = this)

        fun call(id: String, method: String, paramsJson: String = "{}"): String {
            var reply = ""
            dispatcher.dispatch(
                rawPayload = """{"v":1,"id":"$id","method":"$method","params":$paramsJson}""",
                origin = trustedOrigin,
                documentUrl = "https://app.raft.build/settings",
                isMainFrame = true,
            ) { reply = it }
            runCurrent()
            return reply
        }

        val info = parseOkData(call("1", JsbStandardMethods.APP_GET_INFO))
        assertEquals("1.30.0", info["appVersion"]?.jsonPrimitive?.content)
        assertEquals("android", info["platform"]?.jsonPrimitive?.content)
        assertEquals("zh-CN", info["locale"]?.jsonPrimitive?.content)

        val theme = parseOkData(call("2", JsbStandardMethods.UI_GET_THEME))
        assertEquals(true, theme["isDark"]?.jsonPrimitive?.boolean)
        assertEquals("dark", theme["mode"]?.jsonPrimitive?.content)

        assertEquals(true, parseOkData(call("3", JsbStandardMethods.UI_HAPTIC))["performed"]?.jsonPrimitive?.boolean)
        assertEquals(true, parseOkData(call("4", JsbStandardMethods.UI_HAPTIC, """{"style":"success"}"""))["performed"]?.jsonPrimitive?.boolean)
        assertEquals(listOf(JsbHapticStyle.LIGHT, JsbHapticStyle.SUCCESS), host.haptics)

        assertEquals(true, parseOkData(call("5", JsbStandardMethods.UI_COPY_TEXT, """{"text":"hello"}"""))["copied"]?.jsonPrimitive?.boolean)
        assertEquals(listOf("hello"), host.copied)

        assertEquals(
            true,
            parseOkData(call("6", JsbStandardMethods.UI_SHOW_TOAST, """{"message":"Saved","duration":"long"}"""))["shown"]?.jsonPrimitive?.boolean,
        )
        assertEquals(listOf("Saved" to JsbToastDuration.LONG), host.toasts)

        assertEquals(
            true,
            parseOkData(call("7", JsbStandardMethods.NAV_OPEN_EXTERNAL, """{"url":"https://docs.raft.build/guide"}"""))["opened"]?.jsonPrimitive?.boolean,
        )
        assertEquals(listOf("https://docs.raft.build/guide"), host.externalUrls)

        assertEquals(
            true,
            parseOkData(call("8", JsbStandardMethods.NAV_CLOSE, """{"reason":"user_done"}"""))["closed"]?.jsonPrimitive?.boolean,
        )
        assertEquals(listOf<String?>("user_done"), host.closeReasons)

        val auth = parseOkData(call("9", JsbStandardMethods.AUTH_GET_STATUS))
        assertEquals(true, auth["authenticated"]?.jsonPrimitive?.boolean)
        assertEquals("u-1", auth["accountId"]?.jsonPrimitive?.content)
        assertEquals("s-1", auth["serverId"]?.jsonPrimitive?.content)
    }

    @Test
    fun standardCapabilitiesEnforceGate5ValidationAndUnimplementedHostHooks() = runTest {
        val partialHost = object : JsbStandardHost {}
        val registry = JsbRegistry().registerStandardCapabilities(partialHost)
        val policy =
            JsbPolicy(
                enabled = true,
                allowedOrigins = setOf(trustedOrigin),
                grantedCapabilities = setOf(
                    JsbStandardCapabilities.UI_HAPTIC,
                    JsbStandardCapabilities.UI_TOAST,
                    JsbStandardCapabilities.NAV_OPEN_EXTERNAL,
                    JsbStandardCapabilities.NAV_CLOSE,
                    JsbStandardCapabilities.APP_INFO,
                ),
            )
        val dispatcher = JsbDispatcher(policy = policy, registry = registry, scope = this)

        fun callError(id: String, method: String, paramsJson: String = "{}"): String {
            var reply = ""
            dispatcher.dispatch(
                rawPayload = """{"v":1,"id":"$id","method":"$method","params":$paramsJson}""",
                origin = trustedOrigin,
                documentUrl = "https://app.raft.build/settings",
                isMainFrame = true,
            ) { reply = it }
            runCurrent()
            val root = Json.parseToJsonElement(reply).jsonObject
            assertFalse(root["ok"]!!.jsonPrimitive.boolean)
            return assertNotNull(root["error"]?.jsonObject?.get("code")?.jsonPrimitive?.content)
        }

        // Missing capability is denied at Gate 5 even when host doesn't implement it.
        assertEquals(JsbErrorCode.CAPABILITY_DENIED.wireCode, callError("1", JsbStandardMethods.UI_COPY_TEXT, """{"text":"x"}"""))

        // Granted capability on unimplemented host returns METHOD_NOT_FOUND cleanly.
        assertEquals(JsbErrorCode.METHOD_NOT_FOUND.wireCode, callError("2", JsbStandardMethods.APP_GET_INFO))

        // Parameter validation failures return INVALID_PARAMS.
        assertEquals(JsbErrorCode.INVALID_PARAMS.wireCode, callError("3", JsbStandardMethods.UI_HAPTIC, """{"style":"vibrate_hard"}"""))
        assertEquals(JsbErrorCode.INVALID_PARAMS.wireCode, callError("4", JsbStandardMethods.UI_HAPTIC, """{"style":123}"""))
        assertEquals(JsbErrorCode.INVALID_PARAMS.wireCode, callError("5", JsbStandardMethods.UI_SHOW_TOAST, """{"message":"   "}"""))
        assertEquals(JsbErrorCode.INVALID_PARAMS.wireCode, callError("6", JsbStandardMethods.UI_SHOW_TOAST, """{"message":"ok","duration":"forever"}"""))
        assertEquals(JsbErrorCode.INVALID_PARAMS.wireCode, callError("7", JsbStandardMethods.NAV_OPEN_EXTERNAL, """{"url":"javascript:alert(1)"}"""))
        assertEquals(JsbErrorCode.INVALID_PARAMS.wireCode, callError("8", JsbStandardMethods.NAV_OPEN_EXTERNAL, """{"url":"file:///etc/passwd"}"""))
        assertEquals(JsbErrorCode.INVALID_PARAMS.wireCode, callError("9", JsbStandardMethods.NAV_OPEN_EXTERNAL, """{"url":"https://user:pass@evil.example/"}"""))
        assertEquals(JsbErrorCode.INVALID_PARAMS.wireCode, callError("10", JsbStandardMethods.NAV_CLOSE, """{"reason":42}"""))

        // Disallowed/empty allowedExternalSchemes fails closed instead of falling back to defaults.
        val closedRegistry =
            JsbRegistry().registerStandardCapabilities(
                host = FakeStandardHost(),
                allowedExternalSchemes = setOf("javascript"),
            )
        val closedDispatcher = JsbDispatcher(policy = policy, registry = closedRegistry, scope = this)
        var closedReply = ""
        closedDispatcher.dispatch(
            rawPayload = """{"v":1,"id":"11","method":"${JsbStandardMethods.NAV_OPEN_EXTERNAL}","params":{"url":"https://raft.build"}}""",
            origin = trustedOrigin,
            documentUrl = "https://app.raft.build/settings",
            isMainFrame = true,
        ) { closedReply = it }
        runCurrent()
        val closedRoot = Json.parseToJsonElement(closedReply).jsonObject
        assertFalse(closedRoot["ok"]!!.jsonPrimitive.boolean)
        assertEquals(JsbErrorCode.INVALID_PARAMS.wireCode, closedRoot["error"]?.jsonObject?.get("code")?.jsonPrimitive?.content)
    }

    @Test
    fun externalUrlSafetyAndThemeChangedEventPayloadAreStrict() {
        assertTrue(isSafeJsbExternalUrl("https://raft.build/docs"))
        assertTrue(isSafeJsbExternalUrl("http://example.com"))
        assertTrue(isSafeJsbExternalUrl("mailto:support@raft.build"))
        assertTrue(isSafeJsbExternalUrl("tel:+14155550100"))

        assertFalse(isSafeJsbExternalUrl("javascript:alert(1)"))
        assertFalse(isSafeJsbExternalUrl("data:text/html,<script>alert(1)</script>"))
        assertFalse(isSafeJsbExternalUrl("file:///data/local/tmp/a"))
        assertFalse(isSafeJsbExternalUrl("intent://scan/#Intent;end"))
        assertFalse(isSafeJsbExternalUrl("https://user:secret@raft.build"))
        assertFalse(isSafeJsbExternalUrl("https://raft.build/\nfoo"))
        assertFalse(isSafeJsbExternalUrl("https:foo://bar"))
        assertFalse(isSafeJsbExternalUrl("https:javascript://alert(1)"))
        assertFalse(isSafeJsbExternalUrl("https://:80/x"))
        assertFalse(isSafeJsbExternalUrl("https://evil.example:999999/x"))
        assertFalse(isSafeJsbExternalUrl("https://[bad"))
        assertFalse(isSafeJsbExternalUrl("mailto:"))
        assertFalse(isSafeJsbExternalUrl("https://raft.build", allowedSchemes = emptySet()))
        assertTrue(isSafeJsbExternalUrl("https://raft.build", allowedSchemes = setOf("HTTPS")))

        val payload = buildThemeChangedEventPayload(JsbThemeInfo(isDark = true))
        assertEquals(true, payload["isDark"]?.jsonPrimitive?.boolean)
        assertEquals("dark", payload["mode"]?.jsonPrimitive?.content)
        assertEquals("theme:changed", JsbStandardEvents.THEME_CHANGED)
    }

    private fun parseOkData(rawJson: String) =
        Json.parseToJsonElement(rawJson).jsonObject.also { root ->
            assertTrue(root["ok"]!!.jsonPrimitive.boolean, "Expected ok=true, got: $rawJson")
        }["data"]!!.jsonObject
}

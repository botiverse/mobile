package build.raft.app.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The compact quota line of the machine/runtime pickers ("Claude Max · 5 小时 剩 72% · 本周 剩 40%").
 * Window labels are the ones `@botiverse/oar` 0.69 writes (runtimes/<provider>/account-usage.js)
 * and the generic spellings slock's fixtures use; window ids are opaque on the wire.
 */
class RuntimeQuotaLineTest {
    private val zh =
        RuntimeQuotaLineCopy(
            fiveHour = "5 小时",
            week = "本周",
            left = { window, percent -> "$window 剩 $percent" },
            usedUp = { window, reset -> "$window 已用完，${reset}恢复" },
            usedUpNoReset = { window -> "$window 已用完" },
            attention = { plan -> "$plan · 需要处理" }
        )

    private fun window(label: String, ratio: Double?, status: RuntimeAccountUsageWindowStatus = RuntimeAccountUsageWindowStatus.Ok, resetsAt: String? = null) =
        RuntimeAccountUsageWindow(id = "w_${label.hashCode()}", label = label, status = status, usedRatio = ratio, resetsAt = resetsAt)

    private fun read(
        vararg windows: RuntimeAccountUsageWindow,
        plan: String? = "max",
        health: RuntimeAccountUsageHealth = RuntimeAccountUsageHealth.Ok
    ): RuntimeAccountUsageRead =
        RuntimeAccountUsageRead.Available(
            stale = false,
            snapshot =
                RuntimeAccountUsageSnapshot(
                    provider = "claude",
                    collectedAt = null,
                    accounts = listOf(RuntimeAccountUsageAccount("acct", plan, null, health, windows.toList()))
                )
        )

    private fun line(provider: RuntimeAccountUsageProvider, read: RuntimeAccountUsageRead?): String? =
        summarizeRuntimeQuota(provider, read)?.let { formatRuntimeQuotaLine(it, zh) { "3 小时后" } }

    @Test
    fun windowsAreRecognisedByLabelPerProvider() {
        val five = RuntimeQuotaWindowClass.Known(RuntimeQuotaWindowKind.FiveHour)
        val week = RuntimeQuotaWindowClass.Known(RuntimeQuotaWindowKind.Week)
        val cases =
            listOf(
                Triple(RuntimeAccountUsageProvider.Claude, "Current session", five),
                Triple(RuntimeAccountUsageProvider.Claude, "Current week (all models)", week),
                Triple(RuntimeAccountUsageProvider.Claude, "Current week (Opus)", RuntimeQuotaWindowClass.PerModel),
                Triple(RuntimeAccountUsageProvider.Claude, "Current week (OAuth apps)", RuntimeQuotaWindowClass.PerModel),
                Triple(RuntimeAccountUsageProvider.Claude, "Extra usage", RuntimeQuotaWindowClass.Other),
                Triple(RuntimeAccountUsageProvider.Codex, "Codex · 5 hours", five),
                Triple(RuntimeAccountUsageProvider.Codex, "Codex · 1 week", week),
                Triple(RuntimeAccountUsageProvider.Codex, "5 hours", five),
                Triple(RuntimeAccountUsageProvider.Codex, "GPT-5.3-Codex-Spark · 5 hours", RuntimeQuotaWindowClass.PerModel),
                Triple(RuntimeAccountUsageProvider.Kimi, "5h limit", five),
                Triple(RuntimeAccountUsageProvider.Kimi, "Weekly limit", week),
                Triple(RuntimeAccountUsageProvider.Kimi, "Extra Usage monthly limit", RuntimeQuotaWindowClass.Other),
                Triple(RuntimeAccountUsageProvider.Grok, "Weekly included usage", week),
                Triple(RuntimeAccountUsageProvider.Grok, "Monthly included usage", RuntimeQuotaWindowClass.Other),
                Triple(RuntimeAccountUsageProvider.Claude, "5-hour", five),
                Triple(RuntimeAccountUsageProvider.Kimi, "7 days", week),
                Triple(RuntimeAccountUsageProvider.Grok, "Weekly", week)
            )
        cases.forEach { (provider, label, expected) ->
            assertEquals(expected, runtimeQuotaWindowClass(provider, label), "$provider \"$label\"")
        }
    }

    @Test
    fun lineShowsFiveHourThenWeekAndLeavesPerModelWindowsToTheCard() {
        val usage =
            read(
                window("Current week (Opus)", 0.9),
                window("Current week (all models)", 0.6),
                window("Current session", 0.28)
            )
        assertEquals("Claude Max · 5 小时 剩 72% · 本周 剩 40%", line(RuntimeAccountUsageProvider.Claude, usage))
    }

    @Test
    fun usedUpWindowSaysWhenItComesBack() {
        val withReset = read(window("Current session", 1.0, RuntimeAccountUsageWindowStatus.LimitReached, "2026-10-23T05:00:00Z"), window("Current week (all models)", 0.6))
        assertEquals("Claude Max · 5 小时 已用完，3 小时后恢复 · 本周 剩 40%", line(RuntimeAccountUsageProvider.Claude, withReset))
        val noReset = read(window("Current session", 1.0, RuntimeAccountUsageWindowStatus.LimitReached))
        assertEquals("Claude Max · 5 小时 已用完", line(RuntimeAccountUsageProvider.Claude, noReset))
        // 0.4% left is not used up: it reads 0% left, as the web's meter says.
        assertEquals("Claude Max · 5 小时 剩 0%", line(RuntimeAccountUsageProvider.Claude, read(window("Current session", 0.996))))
    }

    @Test
    fun missingWindowsAreLeftOut() {
        assertEquals("Codex Pro · 本周 剩 90%", line(RuntimeAccountUsageProvider.Codex, read(window("Codex · 1 week", 0.1), plan = "pro")))
        // Unparseable windows count as missing, never as 0%.
        assertEquals(
            "Kimi · 本周 剩 75%",
            line(RuntimeAccountUsageProvider.Kimi, read(window("5h limit", null, RuntimeAccountUsageWindowStatus.Unavailable), window("Weekly limit", 0.25), plan = null))
        )
    }

    @Test
    fun withNothingRecognisedTheTightestWindowShowsUnderItsRawLabel() {
        val usage = read(window("Monthly included usage", 0.3), window("Pay-as-you-go", 0.5), plan = null)
        assertEquals("Grok · Pay-as-you-go 剩 50%", line(RuntimeAccountUsageProvider.Grok, usage))
        // Per-model windows never stand in for the account's.
        assertNull(line(RuntimeAccountUsageProvider.Claude, read(window("Current week (Opus)", 0.3))))
    }

    @Test
    fun nothingTrustworthyMeansNoLineAndAnUnhealthyAccountNeedsAttention() {
        assertNull(line(RuntimeAccountUsageProvider.Claude, null))
        assertNull(line(RuntimeAccountUsageProvider.Claude, RuntimeAccountUsageRead.Missing))
        assertEquals(
            "Claude Max · 需要处理",
            line(RuntimeAccountUsageProvider.Claude, read(plan = "max", health = RuntimeAccountUsageHealth.ReauthRequired))
        )
    }

    @Test
    fun planNameStartsWithTheBrand() {
        assertEquals("Claude Max", runtimeQuotaPlanName(RuntimeAccountUsageProvider.Claude, "max"))
        assertEquals("Claude Max", runtimeQuotaPlanName(RuntimeAccountUsageProvider.Claude, "Claude Max"))
        assertEquals("Codex ChatGPT Plus", runtimeQuotaPlanName(RuntimeAccountUsageProvider.Codex, "ChatGPT Plus"))
        assertEquals("Grok", runtimeQuotaPlanName(RuntimeAccountUsageProvider.Grok, " "))
    }

    @Test
    fun usageVisibilityFollowsTheWebRule() {
        data class Case(val attached: Boolean, val creator: String?, val me: String?, val editMachines: Boolean, val visible: Boolean)
        listOf(
            Case(attached = true, creator = null, me = null, editMachines = false, visible = true),
            Case(attached = false, creator = "u1", me = "u1", editMachines = false, visible = true),
            Case(attached = false, creator = "u2", me = "u1", editMachines = false, visible = false),
            Case(attached = false, creator = null, me = null, editMachines = false, visible = false),
            Case(attached = false, creator = "u2", me = "u1", editMachines = true, visible = true)
        ).forEach { case ->
            assertEquals(case.visible, canViewMachineRuntimeAccountUsage(case.attached, case.creator, case.me, case.editMachines), case.toString())
        }
    }

    @Test
    fun viewerHoldsEditMachinesOnlyAsOwnerOrAdminOfTheCurrentServer() {
        fun session(role: String?) =
            StoredAuthSession(
                user = StoredAuthUser(id = "u1", email = "u1@example.com"),
                accessToken = "a",
                refreshToken = "r",
                selectedServerId = "s1",
                servers = listOf(StoredAuthServer(id = "s1", name = "One", role = role))
            )
        assertEquals(RuntimeAccountUsageViewer("u1", canEditMachines = true), runtimeAccountUsageViewerFor(session("admin")))
        assertEquals(RuntimeAccountUsageViewer("u1", canEditMachines = true), runtimeAccountUsageViewerFor(session("owner")))
        assertEquals(RuntimeAccountUsageViewer("u1", canEditMachines = false), runtimeAccountUsageViewerFor(session("member")))
        assertNull(runtimeAccountUsageViewerFor(null))
    }
}

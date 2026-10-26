package build.raft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.RaftPageActivity
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.data.ActionCardSpecEntityRef
import build.raft.app.model.RaftTimeContext
import build.raft.app.model.raftEpochDayForDate
import build.raft.designsystem.theme.RaftTheme
import kotlin.test.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * #proj-mobile task #36 (action card A2): first-party action cards drawn from
 * `presentation.card` (`action-card.v1`) by the generic frame ([RaftActionCardView]) and the
 * card renderer link cards share ([RaftCardTree]), light and dark, from the S0 vector's valid
 * cards (botiverse/slock 4befacd2 `actionCard.v1.vector.json` as A1 reads and strips it, [ActionCardSpecFixtures]).
 *
 * - elements-1: install an App (Field of an agent, ScopeList, warning Notice, a confirm
 *   button), approve a login (Badge row, Field, RelativeTime, a navigate button to an agent).
 * - elements-2: create a channel (mono Field, Msg value, member refs, muted Text), add members
 *   (channel and people refs), create an agent (a computer ref), upgrade (info Notice with an
 *   integer param).
 * - elements-3: the feedback form button, a clamped Text, an EntityRef element with a Duration
 *   and a disabled button with its reason and a URL button with its domain, a destructive
 *   button, an "Open in web" button made by strip, two buttons.
 * - frame-1: busy, executed with its result tree (record under the title), executed without
 *   a result (action buttons gone), frozen.
 * - frame-2: confirm again, view only with a reason, read-only, an error.
 * - s0-updates (S0.1 / S0.2): Field clamp (1 and 6 lines), ScopeList groups, the shield and
 *   credit_card header icons, "Runs on {server}" on a joint channel's card, and an execute with
 *   a key this App doesn't take, stripped to "Open in web", still usable on a frozen card.
 * - limits: every string at its limit in English and Chinese (title wraps, labels truncate),
 *   and the install card in `zh-cn`.
 *
 * Entities this viewer's store knows show its name (scout as "Scout"), the rest the card's
 * fallback; no UUID is drawn. Reports land in `build/reports/home-screenshots/`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h1500dp-xxhdpi")
class RaftActionCardScreenshotTest {
    @get:org.junit.Rule
    val activities = RobolectricComposeActivityRule()

    @Test fun elements1Light() = capture("thread.action-card.elements-1", RaftAppAppearance.Light) { Elements1() }

    @Test fun elements1Dark() = capture("thread.action-card.elements-1.dark", RaftAppAppearance.Dark) { Elements1() }

    @Test fun elements2Light() = capture("thread.action-card.elements-2", RaftAppAppearance.Light) { Elements2() }

    @Test fun elements2Dark() = capture("thread.action-card.elements-2.dark", RaftAppAppearance.Dark) { Elements2() }

    @Test fun elements3Light() = capture("thread.action-card.elements-3", RaftAppAppearance.Light) { Elements3() }

    @Test fun elements3Dark() = capture("thread.action-card.elements-3.dark", RaftAppAppearance.Dark) { Elements3() }

    @Test fun frame1Light() = capture("thread.action-card.frame-1", RaftAppAppearance.Light) { Frame1() }

    @Test fun frame1Dark() = capture("thread.action-card.frame-1.dark", RaftAppAppearance.Dark) { Frame1() }

    @Test fun frame2Light() = capture("thread.action-card.frame-2", RaftAppAppearance.Light) { Frame2() }

    @Test fun frame2Dark() = capture("thread.action-card.frame-2.dark", RaftAppAppearance.Dark) { Frame2() }

    @Test fun contractUpdatesLight() = capture("thread.action-card.s0-updates", RaftAppAppearance.Light) { ContractUpdates() }

    @Test fun contractUpdatesDark() = capture("thread.action-card.s0-updates.dark", RaftAppAppearance.Dark) { ContractUpdates() }

    @Test fun limitsLight() = capture("thread.action-card.limits", RaftAppAppearance.Light) { Limits() }

    @Test fun limitsDark() = capture("thread.action-card.limits.dark", RaftAppAppearance.Dark) { Limits() }

    private fun capture(name: String, appearance: RaftAppAppearance, content: @Composable () -> Unit) {
        RaftRobolectricScreenshot.capture(name, appearance, reportDir = "build/reports/home-screenshots", activities = activities) {
            val pager = remember { object : Pager() {}.apply { pageData = PageData(JSONObject()) } }
            CompositionLocalProvider(
                LocalActivity provides RaftPageActivity(pager),
                LocalRaftTimeContextProvider provides RaftTimeContextProvider(actionCardTimeContext),
            ) {
                RaftDesignSystemTheme {
                    Column(
                        Modifier.fillMaxSize().background(RaftTheme.colors.layerCanvas).padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) { content() }
                }
            }
        }
    }
}

/** 2026-10-26T03:05:00Z, five minutes after the vector's RelativeTime. */
private val actionCardTimeContext =
    RaftTimeContext(localOffsetMinutes = 0, todayEpochDay = raftEpochDayForDate(2026, 10, 26), nowEpochMillis = 1_792_983_900_000L)

/** The viewer's store: it knows scout and alice by their display names; the rest fall back. */
private val fixtureNames =
    mapOf(
        "7b2e8f3a-0c5d-4e4f-9a3b-2c3d4e5f6071" to "Scout",
        "5f0c6d1e-8a3b-4c2d-9e1f-0a1b2c3d4e5f" to "Alice Chen",
    )

private val fixtureEntityName: (ActionCardSpecEntityRef, String?) -> String? = { ref, _ -> fixtureNames[ref.id] }

@Composable
private fun Card(
    name: String,
    state: RaftActionCardFrameState = RaftActionCardFrameState(),
    locale: String = "en",
    expanded: Boolean? = null,
) {
    RaftActionCardView(
        card = ActionCardSpecFixtures.card(name),
        state = state,
        entityName = fixtureEntityName,
        onAction = {},
        displayLocale = locale,
        initiallyExpanded = expanded,
    )
}

@Composable
private fun Elements1() {
    Card("install_app_execute_confirm")
    Card("approve_login_execute")
}

@Composable
private fun Elements2() {
    Card("open_create_channel")
    Card("open_add_members")
    Card("open_create_agent")
    Card("open_upgrade_dialog_gate")
}

@Composable
private fun Elements3() {
    Card("feedback_open_form_all_fields")
    Card("open_feedback_form")
    Card("navigate_entities_and_url")
    Card("destructive_execute_confirm")
    Card("read_unknown_action_kind")
    Card("limit_buttons_2")
}

@Composable
private fun Frame1() {
    Card("approve_login_execute", RaftActionCardFrameState(busyElementKey = "approve"))
    Card(
        "install_app_execute_confirm",
        RaftActionCardFrameState(status = RaftActionCardStatus.Executed, record = "Installed by Alice Chen · 10:51"),
        expanded = true,
    )
    Card("open_create_channel", RaftActionCardFrameState(status = RaftActionCardStatus.Executed, record = "Created by Alice Chen · 10:51"), expanded = true)
    Card("open_add_members", RaftActionCardFrameState(status = RaftActionCardStatus.Frozen))
}

@Composable
private fun Frame2() {
    Card("destructive_execute_confirm", RaftActionCardFrameState(status = RaftActionCardStatus.ReconfirmRequired))
    Card("approve_login_execute", RaftActionCardFrameState(blockedReason = "Prepared in Raft Labs. Open it there to approve."))
    Card("approve_login_execute", RaftActionCardFrameState(blockedReason = "Prepared in Raft Labs. Open it there to approve."), expanded = true)
    Card("open_create_agent", RaftActionCardFrameState(readOnly = true))
    Card("open_feedback_form", RaftActionCardFrameState(error = "Couldn't open the feedback form. Try again."))
}

@Composable
private fun ContractUpdates() {
    Card("field_clamp_6")
    Card("scope_list_groups")
    Card("icon_shield", RaftActionCardFrameState(runsOnServerName = "Raft Labs"))
    Card("icon_credit_card")
    Card("read_unknown_prop_execute", RaftActionCardFrameState(status = RaftActionCardStatus.Frozen))
}

@Composable
private fun Limits() {
    Card("all_lengths_at_limit")
    Card("all_lengths_at_limit", locale = "zh-cn")
    Card("install_app_execute_confirm", locale = "zh-cn")
}

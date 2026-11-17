package build.raft.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import build.raft.app.data.SecondFactorRecovery
import build.raft.app.data.SecondFactorRecoveryStatus
import build.raft.app.data.SharedSecondFactorRecoveryEffect
import build.raft.app.data.SharedSecondFactorRecoveryIntent
import build.raft.app.data.SharedSecondFactorRecoveryState
import build.raft.app.data.SharedSecondFactorRecoveryStore
import build.raft.app.resources.*
import kotlin.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/** Home notification-center id of the pending two-step verification reset (web `second-factor-recovery`). */
internal const val SECOND_FACTOR_RECOVERY_HOME_NOTIFICATION_ID = "second-factor-recovery"

internal class HomeSecondFactorRecoveryUi(
    /** The notification center's first entry while a reset is pending; null otherwise. */
    val item: RaftHomeNotificationItem?,
    val refresh: () -> Unit,
    val cancel: () -> Unit,
)

/** The entry's words, resolved; the bodies carry the request's formatted time. */
internal class SecondFactorRecoveryNoticeCopy(
    val waitingTitle: String,
    val waitingBody: String,
    val readyTitle: String,
    val readyBody: String,
    val setPassword: String,
    val cancel: String,
)

/**
 * Web `useSecondFactorRecoveryNotification` (slock #12295): a warning with the shield, first in
 * the list, no Dismiss. Waiting: from when a sign-in on the web can finish it. Ready: until when
 * (the app has no in-app finish). Either way Cancel Reset, disabled while it runs.
 */
internal fun SecondFactorRecovery.toHomeNotificationItem(
    copy: SecondFactorRecoveryNoticeCopy,
    cancelling: Boolean,
): RaftHomeNotificationItem {
    val ready = status == SecondFactorRecoveryStatus.Ready
    val body =
        listOfNotNull(
            if (ready) copy.readyBody else copy.waitingBody,
            copy.setPassword.takeIf { setPasswordFirst },
        ).joinToString(" ")
    return RaftHomeNotificationItem(
        id = SECOND_FACTOR_RECOVERY_HOME_NOTIFICATION_ID,
        title = if (ready) copy.readyTitle else copy.waitingTitle,
        body = body,
        actionLabel = copy.cancel,
        kind = RaftHomeNotificationKind.Warning,
        icon = RaftHomeNotificationIcon.ShieldAlert,
        security = true,
        actionEnabled = !cancelling,
    )
}

/** The time a body names: ready, until when; waiting, from when. */
internal val SecondFactorRecovery.noticeTime: String
    get() = if (status == SecondFactorRecoveryStatus.Ready) expiresAt else eligibleAt

/** An ISO time in the reader's zone, web `formatMediumDateTime` (medium date, 24-hour time); the raw value if unreadable. */
@Composable
internal fun secondFactorRecoveryLocalTime(iso: String): String {
    val local =
        runCatching { Instant.parse(iso).toLocalDateTime(TimeZone.currentSystemDefault()) }.getOrNull()
            ?: return iso
    return TaskHeadDateTime(local.year, local.month.ordinal + 1, local.day, local.hour, local.minute).localized()
}

@Composable
internal fun secondFactorRecoveryNoticeCopy(time: String): SecondFactorRecoveryNoticeCopy =
    SecondFactorRecoveryNoticeCopy(
        waitingTitle = stringResource(Res.string.home_second_factor_recovery_waiting_title),
        waitingBody = stringResource(Res.string.home_second_factor_recovery_waiting_body, time),
        readyTitle = stringResource(Res.string.home_second_factor_recovery_ready_title),
        readyBody = stringResource(Res.string.home_second_factor_recovery_ready_body, time),
        setPassword = stringResource(Res.string.home_second_factor_recovery_set_password),
        cancel = stringResource(Res.string.home_second_factor_recovery_cancel),
    )

/**
 * The pending reset for the Home notification center: read while Home is [active] (and again
 * through [HomeSecondFactorRecoveryUi.refresh] on foreground and when the center opens); Cancel
 * Reset toasts the web's result copy.
 */
@Composable
internal fun rememberHomeSecondFactorRecovery(
    bridge: build.raft.app.base.BridgeModule,
    store: SharedSecondFactorRecoveryStore?,
    active: Boolean,
): HomeSecondFactorRecoveryUi {
    val fallbackFlow = remember { MutableStateFlow(SharedSecondFactorRecoveryState()) }
    val state by (store?.state ?: fallbackFlow).collectAsState()
    LaunchedEffect(store, active) {
        if (active) store?.dispatch(SharedSecondFactorRecoveryIntent.Refresh)
    }
    val cancelled = stringResource(Res.string.home_second_factor_recovery_cancelled)
    val cancelFailed = stringResource(Res.string.home_second_factor_recovery_cancel_failed)
    LaunchedEffect(store, state.effect) {
        when (state.effect) {
            SharedSecondFactorRecoveryEffect.Cancelled -> bridge.toast(cancelled)
            SharedSecondFactorRecoveryEffect.CancelFailed -> bridge.toast(cancelFailed)
            null -> Unit
        }
        if (state.effect != null) store?.dispatch(SharedSecondFactorRecoveryIntent.ConsumeEffect)
    }
    val recovery = state.recovery
    val item =
        recovery?.toHomeNotificationItem(
            secondFactorRecoveryNoticeCopy(secondFactorRecoveryLocalTime(recovery.noticeTime)),
            state.cancelling,
        )
    return HomeSecondFactorRecoveryUi(
        item = item,
        refresh = { store?.dispatch(SharedSecondFactorRecoveryIntent.Refresh) },
        cancel = { store?.dispatch(SharedSecondFactorRecoveryIntent.Cancel) },
    )
}

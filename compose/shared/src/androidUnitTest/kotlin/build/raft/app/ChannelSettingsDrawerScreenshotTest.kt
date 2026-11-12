package build.raft.app

import org.robolectric.shadows.ShadowDialog

// The channel settings drawer is retired: a channel opens its details page (ChannelSettingsPage.kt),
// captured by ChannelSettingsScreenshotTest and WideChannelSettingsPaneScreenshotTest. The DM
// settings drawer still opens in a window of its own (DirectMessageSettingsScreenshotTest).

/**
 * The drawer is its own window (Modal): capture that window, its content measured to it (as
 * [RaftHomeSectionDialogScreenshotTest]).
 */
internal fun channelSettingsDrawerWindow(): android.view.View? {
    val decor = ShadowDialog.getLatestDialog()?.window?.decorView ?: return null
    val content = channelSettingsDrawerCompose(decor) ?: return decor
    val parent = content.parent as? android.view.View ?: return decor
    val exactly = android.view.View.MeasureSpec.EXACTLY
    content.measure(
        android.view.View.MeasureSpec.makeMeasureSpec(parent.width, exactly),
        android.view.View.MeasureSpec.makeMeasureSpec(parent.height, exactly),
    )
    content.layout(0, 0, parent.width, parent.height)
    return decor
}

private fun channelSettingsDrawerCompose(view: android.view.View): android.view.View? {
    if (view is androidx.compose.ui.platform.ViewRootForTest) return view
    if (view is android.view.ViewGroup) {
        for (index in 0 until view.childCount) channelSettingsDrawerCompose(view.getChildAt(index))?.let { return it }
    }
    return null
}

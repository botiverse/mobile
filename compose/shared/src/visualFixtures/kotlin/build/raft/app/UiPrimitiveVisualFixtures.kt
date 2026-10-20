package build.raft.app

import build.raft.app.components.SegmentedControl
import build.raft.app.components.SegmentedControlOption
import build.raft.app.components.SlockAvatarListRow
import build.raft.app.components.SlockBrutalActionButton
import build.raft.app.components.SlockBrutalCard
import build.raft.app.components.SlockBrutalBadge
import build.raft.app.components.SlockBrutalBadgeButton
import build.raft.app.components.SlockBrutalInputField
import build.raft.app.components.SlockBrutalTextarea
import build.raft.app.components.SlockAttentionDot
import build.raft.app.components.SlockCheckMarker
import build.raft.app.components.SlockCheckMarkerShape
import build.raft.app.components.SlockCheckMarkerTone
import build.raft.app.components.SlockCheckboxLabelRow
import build.raft.app.components.SlockFormField
import build.raft.app.components.SlockFormFieldLabelStyle
import build.raft.app.components.SlockFormFieldSize
import build.raft.app.components.SlockProgressBar
import build.raft.app.components.SlockProgressBarTone
import build.raft.app.components.SlockBrutalTone
import build.raft.app.components.SlockSectionHeader
import build.raft.app.components.SlockSectionEyebrow
import build.raft.app.components.SlockSelectTrigger
import build.raft.app.components.SlockMenuItemRow
import build.raft.app.components.slockRaftOverlaySurface
import build.raft.app.components.SlockSelectionPopover
import build.raft.app.components.SlockSelectionPopoverOption
import build.raft.app.components.SlockSkeleton
import build.raft.app.components.SlockSkeletonRow
import build.raft.app.components.SlockSkeletonVariant
import build.raft.app.components.SlockSlugInput
import build.raft.app.components.SlockSpinner
import build.raft.app.components.SlockSpinnerSize
import build.raft.app.components.SlockStatusDot
import build.raft.app.components.SlockSurfaceListItem
import androidx.compose.runtime.Composable
import androidx.compose.foundation.background
import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun UiSegmentedControlVisualContent() {
    Box(
        modifier =
            Modifier
                .width(342.dp)
                // Fill the full 96dp capture crop with SurfaceWhite so the pager's
                // default surface does not bleed a cream band below the control
                // (matches the React baseline and the sibling button fixture, which
                // pins its height for the same reason). Fixes visual-drift #1516.
                .height(96.dp)
                .background(SlockColor.SurfaceWhite)
                .padding(16.dp)
    ) {
        SegmentedControl(
            selectedValue = "mentions",
            options =
                listOf(
                    SegmentedControlOption(value = "all", label = "All", count = 24),
                    SegmentedControlOption(value = "mentions", label = "Mentions", count = 3),
                    SegmentedControlOption(value = "unread", label = "Unread", count = 9)
                ),
            onValueSelected = {}
        )
    }
}

@Composable
internal fun UiButtonVisualContent(rowSpacing: Dp = 12.dp) {
    Box(
        modifier =
            Modifier
                .width(342.dp)
                .height(168.dp)
                .background(SlockColor.SurfaceWhite)
                .padding(16.dp)
    ) {
        // Mirrors web VisualTestingCases `ui-button` (raft-ui 0.6.0, slock #10148): two rows
        // of `size="sm"` buttons 12px apart — outline Save / primary Sync / accent Delete,
        // information Add / success Continue / muted disabled Disabled. The brutal fills map
        // onto those variants (yellow + pink -> pink accent sticker; cyan, lime, white ->
        // paper outline) and every minHeight below 38dp lands on raft's 32dp `md` size.
        // Raft buttons cast no shadow and reserve none, so the gap is the full 12dp.
        Column(verticalArrangement = Arrangement.spacedBy(rowSpacing)) {
            Row(horizontalArrangement = Arrangement.spacedBy(rowSpacing), verticalAlignment = Alignment.CenterVertically) {
                SlockBrutalActionButton(label = "Save", onClick = {}, minHeight = 28.dp, minWidth = 0.dp, shadowOffset = 2.dp, textStyle = SlockType.ButtonBold.copy(fontSize = 12.sp, lineHeight = 14.sp))
                SlockBrutalActionButton(label = "Sync", onClick = {}, backgroundColor = SlockColor.BrandYellow, minHeight = 28.dp, minWidth = 0.dp, shadowOffset = 2.dp, textStyle = SlockType.ButtonBold.copy(fontSize = 12.sp, lineHeight = 14.sp))
                SlockBrutalActionButton(label = "Delete", onClick = {}, backgroundColor = SlockColor.AccentPink, minHeight = 28.dp, minWidth = 0.dp, shadowOffset = 2.dp, textStyle = SlockType.ButtonBold.copy(fontSize = 12.sp, lineHeight = 14.sp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(rowSpacing), verticalAlignment = Alignment.CenterVertically) {
                SlockBrutalActionButton(label = "Add", onClick = {}, backgroundColor = SlockColor.AccentCyan, minHeight = 24.dp, minWidth = 0.dp, shadowOffset = 2.dp, textStyle = SlockType.ButtonBold.copy(fontSize = 11.sp, lineHeight = 12.sp))
                SlockBrutalActionButton(label = "Continue", onClick = {}, backgroundColor = SlockColor.AccentLime, minHeight = 32.dp, minWidth = 0.dp, shadowOffset = 2.dp, textStyle = SlockType.ButtonBold.copy(fontSize = 14.sp, lineHeight = 16.sp))
                // Disabled is raft's opaque fill-muted / line-muted / disabled-ink chrome.
                SlockBrutalActionButton(label = "Disabled", onClick = {}, backgroundColor = SlockColor.AccentStone, enabled = false, minHeight = 28.dp, minWidth = 0.dp, shadowOffset = 2.dp, textStyle = SlockType.ButtonBold.copy(fontSize = 12.sp, lineHeight = 14.sp))
            }
        }
    }
}

@Composable
internal fun UiButtonThemeVisualContent() {
    // Phase 1 theme-axis proof: the SAME SlockBrutalActionButton rendered under each
    // register via SlockThemeProvider. Top row = brutalTheme (default, byte-identical
    // to the shipping look: square, 2dp black hairline, hard 4dp offset shadow, black
    // ink). Bottom row = elegantTheme (8dp rounded, 1dp gray hairline, near-black ink).
    // Neither border/corner/shadow/ink is passed here — they resolve from
    // LocalSlockTheme, so a visible brutal->elegant delta in this capture IS the
    // CompositionLocal switch working. (The elegant soft ambient blur is a documented
    // follow-up; this offset-box shadow renders the offset+color but no blur.)
    Box(
        modifier =
            Modifier
                .width(342.dp)
                .height(150.dp)
                .background(SlockColor.SurfaceWhite)
                .padding(16.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            SlockThemeProvider(theme = brutalTheme) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    SlockBrutalActionButton(label = "Brutal", onClick = {}, minHeight = 32.dp, minWidth = 0.dp)
                    SlockBrutalActionButton(label = "Save", onClick = {}, backgroundColor = SlockColor.BrandYellow, minHeight = 32.dp, minWidth = 0.dp)
                }
            }
            SlockThemeProvider(theme = elegantTheme) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    SlockBrutalActionButton(label = "Elegant", onClick = {}, minHeight = 32.dp, minWidth = 0.dp)
                    SlockBrutalActionButton(label = "Save", onClick = {}, backgroundColor = SlockColor.BrandYellow, minHeight = 32.dp, minWidth = 0.dp)
                }
            }
        }
    }
}

@Composable
internal fun UiCardVisualContent() {
    val theme = LocalSlockTheme.current
    // Core-bbox normalization across registers (XiShi contract): the brutal card reserves a
    // 4dp shadow gutter (visible core = outer - 4dp); the raft card (container radius,
    // hairline, no shadow) reserves none, matching web's 310px `rounded-container` card.
    val cardWidth = if (theme.isAmbient) 310.dp else 314.dp
    // Fixed 342x190 backdrop with only start/top padding (no end/bottom) so the outer
    // width isn't constrained to 310 — brutal outer 314 (core 314-4=310) and elegant outer
    // 310 (core 310) both fit and the visible card core equals 310dp from x=16 to x=326.
    Box(
        modifier =
            Modifier
                .width(342.dp)
                .height(190.dp)
                .background(SlockColor.SurfaceWhite)
                .padding(start = 16.dp, top = 16.dp)
    ) {
        SlockBrutalCard(modifier = Modifier.width(cardWidth)) {
            Text(
                text = "Channel settings",
                style = SlockType.PageTitle.copy(fontSize = 16.sp, lineHeight = 20.sp),
                color = theme.ink,
                maxLines = 1
            )
            Text(
                text = "Control who can post and how the channel appears to members.",
                style = SlockType.Caption.copy(fontSize = 12.sp, lineHeight = 16.sp),
                color = theme.inkMuted
            )
            SlockBrutalActionButton(
                label = "Save changes",
                onClick = {},
                // React places <Button size="sm"> in a flex-col so it stretches to the card
                // content width (~280dp); Kuikly Column keeps it content-sized without this,
                // so fill the card width to match (XiShi actual-capture audit, both registers).
                //
                // Shadow-ink contract (brutal): SlockBrutalActionButton uses shadowOffset as BOTH
                // the offset-box shadow displacement AND its end/bottom layout reserve. React's
                // size="sm" button shadow is 2px, so shadowOffset = 2.dp gives a matching 2dp
                // shadow ink (the standalone button fixture already sets 2.dp per size; the card
                // fixture previously omitted it and fell to brutalTheme.shadow.offset = 4dp). To
                // keep the already-parity brutal footprint (Card border 128dp, nested Button core
                // 278dp), a brutal-only external end/bottom = 2.dp reserve restores the original
                // 4dp footprint (2dp external + 2dp internal). Elegant renders via Modifier.shadow
                // (no offset-box), so it ignores shadowOffset and takes NO external reserve — its
                // geometry (the accepted waiver capture) is unchanged.
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (!theme.isAmbient) {
                            Modifier.padding(end = 2.dp, bottom = 2.dp)
                        } else {
                            Modifier
                        }
                    ),
                shadowOffset = 2.dp,
                backgroundColor = SlockColor.BrandYellow,
                minHeight = 28.dp,
                minWidth = 0.dp,
                textStyle = SlockType.ButtonBold.copy(fontSize = 12.sp, lineHeight = 14.sp)
            )
        }
    }
}

@Composable
internal fun UiFormFieldVisualContent() {
    // Fixed 342x296 frame (capture-contract, XiShi run 30837350660): the old 226dp crop
    // truncated the 3rd field's error input + "Name is required" (KMP content bottom is
    // y=831/831@3x ≈ 277dp brutal, 795 ≈ 265dp elegant), while React renders 296dp. 296dp is
    // tall enough to contain all three states + bottom padding in both registers (the earlier
    // 226dp that "collapsed the error box" was simply too short); UI_FORM_FIELD_CROP matches.
    Box(
        modifier =
            Modifier
                .width(342.dp)
                .height(296.dp)
                .background(SlockColor.SurfaceWhite)
                .padding(16.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SlockFormField(
                label = "Email",
                required = true,
                labelStyle = SlockFormFieldLabelStyle.Plain
            ) {
                SlockBrutalInputField(
                    value = "cindy@slock.ai",
                    onValueChange = {},
                    placeholder = "",
                    autoFocus = false,
                    fieldMinHeight = 42.dp
                )
            }
            SlockFormField(
                label = "Description",
                optional = true,
                hint = "Shown in channel discovery."
            ) {
                SlockBrutalInputField(
                    value = "Visual parity fixture",
                    onValueChange = {},
                    placeholder = "",
                    autoFocus = false,
                    fieldMinHeight = 42.dp
                )
            }
            SlockFormField(
                label = "Server Name",
                size = SlockFormFieldSize.Compact,
                error = "Name is required"
            ) {
                SlockBrutalInputField(
                    value = "",
                    onValueChange = {},
                    placeholder = "",
                    autoFocus = false,
                    fieldMinHeight = 42.dp,
                    borderColor = SlockColor.AccentRed
                )
            }
        }
    }
}

@Composable
internal fun UiTextareaVisualContent() {
    // Fixed 342x252 frame (capture-contract, XiShi source-derived H=252dp; UI_TEXTAREA_CROP).
    // Mirrors web VisualTestingCases `ui-textarea` after raft-ui 0.6.0: one invalid raft
    // Textarea (min-h-24 = 96dp, two rows) followed by its error + `TextareaCounter` row.
    Box(
        modifier =
            Modifier
                .width(342.dp)
                .height(252.dp)
                .background(SlockColor.Canvas)
                .padding(16.dp)
    ) {
        SlockBrutalTextarea(
            value = "This agreement copy is too long for the configured limit.",
            onValueChange = {},
            error = "Agreement body must be shorter.",
            showCounter = true,
            limit = 40,
            fieldHeight = 96.dp,
            maxLines = 2
        )
    }
}

@Composable
internal fun UiCheckboxVisualContent() {
    Box(
        modifier =
            Modifier
                .width(342.dp)
                .height(132.dp)
                .background(SlockColor.SurfaceWhite)
                .padding(16.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SlockCheckboxLabelRow(label = "As Task selected", checked = true, size = 14.dp)
            SlockCheckboxLabelRow(label = "Permission row unchecked", checked = false, size = 16.dp)
            SlockCheckboxLabelRow(label = "Disabled checked", checked = true, enabled = false, size = 16.dp)
        }
    }
}

@Composable
internal fun UiCheckMarkerVisualContent() {
    Box(
        modifier =
            Modifier
                .width(342.dp)
                .height(112.dp)
                .background(SlockColor.SurfaceWhite)
                .padding(16.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                SlockCheckMarker(checked = true, size = 14.dp)
                Text("Square checked", style = SlockType.Body.copy(fontSize = 14.sp, lineHeight = 18.sp).withSlockFontWeight(FontWeight.Bold), color = SlockColor.TextBlack)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                SlockCheckMarker(checked = false, size = 16.dp)
                Text("Square unchecked", style = SlockType.Body.copy(fontSize = 14.sp, lineHeight = 18.sp).withSlockFontWeight(FontWeight.Bold), color = SlockColor.TextBlack)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                SlockCheckMarker(checked = true, shape = SlockCheckMarkerShape.Circle, size = 18.dp, tone = SlockCheckMarkerTone.YellowFill)
                Text("Circle yellow", style = SlockType.Body.copy(fontSize = 14.sp, lineHeight = 18.sp).withSlockFontWeight(FontWeight.Bold), color = SlockColor.TextBlack)
            }
        }
    }
}

@Composable
internal fun UiAttentionDotVisualContent() {
    Box(
        modifier =
            Modifier
                .width(342.dp)
                .height(80.dp)
                .background(SlockColor.SurfaceWhite)
                .padding(16.dp)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                SlockAttentionDot()
                Text("Unread", style = SlockType.Body.copy(fontSize = 14.sp, lineHeight = 18.sp).withSlockFontWeight(FontWeight.Bold), color = SlockColor.TextBlack)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                SlockAttentionDot(size = 4.dp)
                Text("Compact", style = SlockType.Body.copy(fontSize = 14.sp, lineHeight = 18.sp).withSlockFontWeight(FontWeight.Bold), color = SlockColor.TextBlack)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                SlockAttentionDot(color = SlockColor.AccentOrange)
                Text("Warning", style = SlockType.Body.copy(fontSize = 14.sp, lineHeight = 18.sp).withSlockFontWeight(FontWeight.Bold), color = SlockColor.TextBlack)
            }
        }
    }
}

@Composable
internal fun UiStatusDotVisualContent() {
    Box(
        modifier =
            Modifier
                .width(342.dp)
                .height(92.dp)
                .background(SlockColor.SurfaceWhite)
                .padding(16.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                SlockStatusDot(color = SlockColor.AccentLime)
                SlockStatusDot(color = SlockColor.AccentOrange, size = 8.dp)
                SlockStatusDot(color = SlockColor.StatusNeutralGray, size = 11.dp)
                SlockStatusDot(color = SlockColor.AccentCyan)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                SlockStatusDot(color = SlockColor.AccentPink, pulsing = true)
                Text("Waiting pulse", style = SlockType.Body.copy(fontSize = 14.sp, lineHeight = 18.sp).withSlockFontWeight(FontWeight.Bold), color = SlockColor.TextBlack)
            }
        }
    }
}

@Composable
internal fun UiProgressBarVisualContent() {
    Box(
        modifier =
            Modifier
                .width(342.dp)
                .height(124.dp)
                .background(SlockColor.SurfaceWhite)
                .padding(16.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            SlockProgressBar(modifier = Modifier.width(310.dp), value = 64, label = "Downloading update", showPercent = true, tone = SlockProgressBarTone.Pink)
            SlockProgressBar(modifier = Modifier.width(310.dp), value = 28, label = "Verifying package", showPercent = true, tone = SlockProgressBarTone.Cyan)
        }
    }
}

@Composable
internal fun UiSkeletonVisualContent() {
    Box(
        modifier =
            Modifier
                .width(342.dp)
                .height(150.dp)
                .background(SlockColor.SurfaceWhite)
                // No bottom padding on purpose: react's fixed 150px canvas lets the
                // 134px content stack overflow into the bottom padding (CSS keeps
                // padding out of the child's constraint). A symmetric 16.dp here
                // caps children at 118dp and Compose COMPRESSES the last row - the
                // 32dp circle rendered as a 16dp ring (the PR-549 constraint bug
                // class). start/top/end only -> content max 134dp = exact fit.
                .padding(start = 16.dp, top = 16.dp, end = 16.dp)
    ) {
        // Values mirror the react fixture (VisualTestingCases.tsx ui-skeleton case):
        // avatars size-5/size-8 = 20/32px, outer gap-3 = 12px, block h-12 = 48px.
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SlockSkeletonRow(avatar = true, avatarSize = 20.dp, lineWidths = listOf(128.dp, 80.dp))
            SlockSkeleton(modifier = Modifier.width(310.dp).height(48.dp), variant = SlockSkeletonVariant.Block)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                SlockSkeleton(modifier = Modifier.size(32.dp), variant = SlockSkeletonVariant.Circle)
                SlockSkeleton(modifier = Modifier.width(160.dp).height(12.dp), variant = SlockSkeletonVariant.Line)
            }
        }
    }
}

@Composable
internal fun UiSpinnerVisualContent() {
    // web: `flex items-center gap-5` of Spinner xs / sm / md / lg and an inverse sm in a
    // `bg-black p-2` span. The span is a flex item holding an inline-flex spinner, so its
    // content box is one 24px line with the spinner sitting on the baseline (the bottom).
    // The spinners are pinned to step 1 (top-right pixel lit) so the capture is
    // deterministic; the web frame depends on its capture timing.
    Box(
        modifier =
            Modifier
                .width(342.dp)
                .height(92.dp)
                .background(SlockColor.Canvas)
                .padding(16.dp)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
            SlockSpinner(size = SlockSpinnerSize.Xs, stillStep = UiSpinnerVisualStillStep)
            SlockSpinner(size = SlockSpinnerSize.Sm, stillStep = UiSpinnerVisualStillStep)
            SlockSpinner(size = SlockSpinnerSize.Md, stillStep = UiSpinnerVisualStillStep)
            SlockSpinner(size = SlockSpinnerSize.Lg, stillStep = UiSpinnerVisualStillStep)
            Box(
                modifier = Modifier.background(Color.Black).padding(8.dp).height(24.dp),
                contentAlignment = Alignment.BottomStart
            ) {
                SlockSpinner(size = SlockSpinnerSize.Sm, inverse = true, stillStep = UiSpinnerVisualStillStep)
            }
        }
    }
}

private const val UiSpinnerVisualStillStep = 1

@Composable
internal fun UiSlugInputVisualContent() {
    Box(
        modifier =
            Modifier
                .width(342.dp)
                // Content-sized: the old fixed 122.dp couldn't hold 2x42dp rows +
                // 16dp gap + 32dp padding, so the constraint compressed each input
                // to ~26dp - react's boxes are 44 css px tall (same clip family as
                // the form-field fixture fix).
                .background(SlockColor.SurfaceWhite)
                .padding(16.dp)
    ) {
        // raft-ui InputGroup has no shadow gutter, so react's gap-4 is 16dp.
        // React renders the second row read-only at opacity-60 (not disabled).
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            SlockSlugInput(value = "design-lab", onValueChange = {}, modifier = Modifier.width(310.dp))
            SlockSlugInput(
                value = "partner-workspace",
                onValueChange = {},
                modifier = Modifier.width(310.dp).alpha(0.6f)
            )
        }
    }
}

@Composable
internal fun UiSectionEyebrowVisualContent() {
    Box(
        modifier =
            Modifier
                .width(342.dp)
                .height(92.dp)
                .background(SlockColor.SurfaceWhite)
                .padding(16.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SlockSectionEyebrow(label = "Recent Activity")
            SlockSectionEyebrow(label = "Connected Apps", color = SlockColor.TextBlack)
            Box(modifier = Modifier.background(SlockColor.SurfaceWhite.copy(alpha = 0.5f)).padding(horizontal = 8.dp, vertical = 4.dp)) {
                SlockSectionEyebrow(label = "Choose Avatar")
            }
        }
    }
}

@Composable
internal fun UiSelectionPopoverVisualContent() {
    Box(
        modifier =
            Modifier
                .width(342.dp)
                .height(252.dp)
                .background(SlockColor.SurfaceWhite)
                .padding(16.dp)
    ) {
        SlockSelectionPopover(
            title = "Channels",
            showClear = true,
            search = "des",
            searchPlaceholder = "Search channels",
            options =
                listOf(
                    SlockSelectionPopoverOption("design", "design", checked = true, reserveLeadingSlot = true),
                    SlockSelectionPopoverOption("visual-testing", "visual-testing", reserveLeadingSlot = true),
                    SlockSelectionPopoverOption("archive", "archived channel", enabled = false, reserveLeadingSlot = true),
                    SlockSelectionPopoverOption("none", "No channel", italic = true, reserveLeadingSlot = true)
                )
        )
    }
}

@Composable
internal fun UiMenuItemVisualContent() {
    // Mirrors the web case: four slock `MenuItem` rows (36dp, px-2.5, callout medium, mono
    // hint shortcut) stacked flush inside a `rounded-container border border-line-seam
    // bg-layer-popover shadow-float` card -- no popup inset -- with the last row
    // carrying its own `border-t border-line-hairline` inside its 36dp box. 342 x 178dp =
    // 16 + 1 + 4 x 36 + 1 + 16.
    Box(
        modifier =
            Modifier
                .width(342.dp)
                .height(178.dp)
                .background(SlockColor.SurfaceWhite)
                .padding(16.dp)
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .slockRaftOverlaySurface()
                    .padding(SlockStroke.Thin)
        ) {
            SlockMenuItemRow(label = "Open Channel", trailing = "⌘K")
            SlockMenuItemRow(label = "Mark as Read")
            SlockMenuItemRow(label = "Archive unavailable", enabled = false)
            Box(modifier = Modifier.fillMaxWidth()) {
                SlockMenuItemRow(label = "Delete Message")
                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(SlockStroke.Thin)
                            .background(SlockColor.BorderHairline)
                )
            }
        }
    }
}

@Composable
internal fun UiSelectVisualContent() {
    Box(
        modifier =
            Modifier
                .width(342.dp)
                .height(194.dp)
                .background(SlockColor.SurfaceWhite)
                .padding(16.dp)
    ) {
        // react spaces the triggers 16px box edge to box edge; the Kuikly trigger
        // reserves its 2dp shadow as bottom layout space, so 14dp spacing puts the
        // second trigger 16dp below the first box like react (task #370).
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            SlockSelectTrigger(label = "Codex")
            SlockSelectTrigger(label = "Select...", enabled = false)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun UiBadgeVisualContent() {
    Box(
        modifier =
            Modifier
                .width(342.dp)
                .height(92.dp)
                .background(SlockColor.SurfaceWhite)
                .padding(16.dp)
    ) {
        // React renders one `flex flex-wrap gap-2` run of six raft-ui <Badge>s
        // whose default flipped to uppercase=false ("case-sensitive content
        // stays intact") — Title Case, no tracking, wrap where width dictates
        // (drift #773). Warning fill is --state-warning-base #FF6900, not the
        // softer AccentOrange.
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SlockBrutalBadge("Shared", tone = SlockBrutalTone.White, uppercase = false)
            SlockBrutalBadge("Installed", tone = SlockBrutalTone.Lime, uppercase = false)
            SlockBrutalBadge("Update", tone = SlockBrutalTone.Warning, uppercase = false)
            SlockBrutalBadge("Built In", tone = SlockBrutalTone.Stone, uppercase = false)
            SlockBrutalBadge("task #273", tone = SlockBrutalTone.Red, uppercase = false)
            SlockBrutalBadgeButton("Install", tone = SlockBrutalTone.Pink, uppercase = false, onClick = {})
        }
    }
}

@Composable
internal fun UiSectionHeaderVisualContent() {
    Box(
        modifier =
            Modifier
                .width(342.dp)
                .height(88.dp)
                .background(SlockColor.SurfaceWhite)
                .padding(16.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SlockSectionHeader(
                label = "Connected Apps",
                count = 3,
                modifier = Modifier.fillMaxWidth(),
                action = {
                    SlockBrutalActionButton(label = "Add", onClick = {}, minHeight = 24.dp, minWidth = 0.dp, shadowOffset = 2.dp, textStyle = SlockType.ButtonBold.copy(fontSize = 11.sp, lineHeight = 12.sp))
                }
            )
            Box(modifier = Modifier.fillMaxWidth().height(SlockStroke.Default).background(SlockColor.BorderBlack))
        }
    }
}

@Composable
internal fun UiSurfaceListItemVisualContent() {
    // 182dp matches the react capture's pinned element height; content is
    // top-aligned so any blank slack at the bottom exposes real row spacing
    // diffs instead of hiding them behind a shorter canvas (task #370).
    Box(
        modifier =
            Modifier
                .width(342.dp)
                .height(182.dp)
                .background(SlockColor.SurfaceWhite)
                .padding(16.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SlockSurfaceListItem(interactive = false) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Private app", style = SlockType.ButtonBold.copy(fontSize = 14.sp, lineHeight = 18.sp), color = SlockColor.TextBlack, maxLines = 1)
                    Text("Available to this server", style = SlockType.Caption.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 14.sp), color = SlockColor.TextBlack.copy(alpha = 0.5f), maxLines = 1)
                }
            }
            SlockSurfaceListItem(selected = true, interactive = false) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Active integration", style = SlockType.ButtonBold.copy(fontSize = 14.sp, lineHeight = 18.sp), color = SlockColor.TextBlack, maxLines = 1)
                    Text("Selected list item state", style = SlockType.Caption.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 14.sp), color = SlockColor.TextBlack.copy(alpha = 0.5f), maxLines = 1)
                }
            }
        }
    }
}

@Composable
internal fun UiAvatarListRowVisualContent() {
    Box(
        modifier =
            Modifier
                .width(342.dp)
                .height(168.dp)
                .background(SlockColor.SurfaceWhite)
                .padding(16.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SlockAvatarListRow(
                avatarWireValue = null,
                avatarLabel = "Cindy",
                name = "Cindy",
                subtitle = "Claude Code",
                rightContent = {
                    SlockBrutalBadge("Online", tone = SlockBrutalTone.Lime, uppercase = false)
                }
            )
            SlockAvatarListRow(
                avatarWireValue = null,
                avatarLabel = "Product UX Designer",
                name = "Product UX Designer",
                subtitle = "product@slock.ai",
                selected = true,
                rightContent = {
                    SlockBrutalActionButton(label = "Open", onClick = {}, minHeight = 24.dp, minWidth = 0.dp, shadowOffset = 2.dp, textStyle = SlockType.ButtonBold.copy(fontSize = 11.sp, lineHeight = 12.sp))
                }
            )
        }
    }
}

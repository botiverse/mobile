package build.raft.app

import build.raft.designsystem.components.RaftButton
import build.raft.designsystem.components.RaftButtonVariant
import build.raft.app.components.SegmentedControl
import build.raft.app.components.SegmentedControlOption
import build.raft.app.components.RaftBrutalActionButton
import build.raft.app.components.RaftBrutalCard
import build.raft.app.components.RaftBrutalInputField
import build.raft.app.components.RaftBrutalTextarea
import build.raft.app.components.RaftAttentionDot
import build.raft.app.components.RaftFormField
import build.raft.app.components.RaftFormFieldLabelStyle
import build.raft.app.components.RaftFormFieldSize
import build.raft.app.components.RaftProgressBar
import build.raft.app.components.RaftProgressBarTone
import build.raft.app.components.RaftSectionHeader
import build.raft.app.components.RaftAppSectionEyebrow
import build.raft.app.components.RaftAppSelectTrigger
import build.raft.app.components.RaftSelectionPopover
import build.raft.app.components.RaftSelectionPopoverOption
import build.raft.app.components.RaftAppSkeleton
import build.raft.app.components.RaftSkeletonRow
import build.raft.app.components.RaftAppSkeletonVariant
import build.raft.app.components.RaftSlugInput
import build.raft.app.components.RaftAppSpinner
import build.raft.app.components.RaftAppSpinnerSize
import build.raft.app.components.RaftAppStatusDot
import build.raft.app.components.RaftSurfaceListItem
import androidx.compose.runtime.Composable
import androidx.compose.foundation.background
import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
                // Fill the full 96dp capture crop with the layer canvas so the pager's
                // default surface does not bleed a cream band below the control
                // (matches the React baseline and the sibling button fixture, which
                // pins its height for the same reason). Fixes visual-drift #1516.
                .height(96.dp)
                .background(RaftColor.Canvas)
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
internal fun UiButtonThemeVisualContent() {
    // Phase 1 theme-axis proof: the SAME RaftBrutalActionButton rendered under each
    // register via RaftThemeProvider. Top row = brutalTheme (default, byte-identical
    // to the shipping look: square, 2dp black hairline, hard 4dp offset shadow, black
    // ink). Bottom row = elegantTheme (8dp rounded, 1dp gray hairline, near-black ink).
    // Neither border/corner/shadow/ink is passed here — they resolve from
    // LocalRaftTheme, so a visible brutal->elegant delta in this capture IS the
    // CompositionLocal switch working. (The elegant soft ambient blur is a documented
    // follow-up; this offset-box shadow renders the offset+color but no blur.)
    Box(
        modifier =
            Modifier
                .width(342.dp)
                .height(150.dp)
                .background(RaftColor.SurfaceWhite)
                .padding(16.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            RaftThemeProvider(theme = raftLightTheme) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    RaftButton(text = "Brutal", onClick = {})
                    RaftButton(text = "Save", onClick = {}, variant = RaftButtonVariant.Primary)
                }
            }
            RaftThemeProvider(theme = elegantTheme) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    RaftButton(text = "Elegant", onClick = {})
                    RaftButton(text = "Save", onClick = {}, variant = RaftButtonVariant.Primary)
                }
            }
        }
    }
}

@Composable
internal fun UiCardVisualContent() {
    val theme = LocalRaftTheme.current
    // Core-bbox normalization across registers (XiShi contract): the brutal card reserves a
    // 4dp shadow gutter (visible core = outer - 4dp); the raft card (container radius,
    // hairline, no shadow) reserves none, matching web's 310px `rounded-container` card.
    val cardWidth = 310.dp
    // Fixed 342x190 backdrop with only start/top padding (no end/bottom) so the outer
    // width isn't constrained to 310 — brutal outer 314 (core 314-4=310) and elegant outer
    // 310 (core 310) both fit and the visible card core equals 310dp from x=16 to x=326.
    Box(
        modifier =
            Modifier
                .width(342.dp)
                .height(190.dp)
                .background(RaftColor.Canvas)
                .padding(start = 16.dp, top = 16.dp)
    ) {
        RaftBrutalCard(modifier = Modifier.width(cardWidth)) {
            Text(
                text = "Channel settings",
                style = RaftType.PageTitle.copy(fontSize = 16.sp, lineHeight = 20.sp),
                color = theme.ink,
                maxLines = 1
            )
            Text(
                text = "Control who can post and how the channel appears to members.",
                style = RaftType.Caption.copy(fontSize = 12.sp, lineHeight = 16.sp),
                color = theme.inkMuted
            )
            RaftBrutalActionButton(
                label = "Save changes",
                onClick = {},
                // React places <Button size="sm"> in a flex-col so it stretches to the card
                // content width (~280dp); Kuikly Column keeps it content-sized without this,
                // so fill the card width to match (XiShi actual-capture audit, both registers).
                //
                // Shadow-ink contract (brutal): RaftBrutalActionButton uses shadowOffset as BOTH
                // the offset-box shadow displacement AND its end/bottom layout reserve. React's
                // size="sm" button shadow is 2px, so shadowOffset = 2.dp gives a matching 2dp
                // shadow ink (the standalone button fixture already sets 2.dp per size; the card
                // fixture previously omitted it and fell to brutalTheme.shadow.offset = 4dp). To
                // keep the already-parity brutal footprint (Card border 128dp, nested Button core
                // 278dp), a brutal-only external end/bottom = 2.dp reserve restores the original
                // 4dp footprint (2dp external + 2dp internal). Elegant renders via Modifier.shadow
                // (no offset-box), so it ignores shadowOffset and takes NO external reserve — its
                // geometry (the accepted waiver capture) is unchanged.
                modifier = Modifier.fillMaxWidth(),
                backgroundColor = RaftColor.BrandYellow,
                minHeight = 28.dp,
                minWidth = 0.dp,
                textStyle = RaftType.ButtonBold.copy(fontSize = 12.sp, lineHeight = 14.sp)
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
                .background(RaftColor.Canvas)
                .padding(16.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            RaftFormField(
                label = "Email",
                required = true,
                labelStyle = RaftFormFieldLabelStyle.Plain
            ) {
                RaftBrutalInputField(
                    value = "cindy@slock.ai",
                    onValueChange = {},
                    placeholder = "",
                    autoFocus = false,
                    fieldMinHeight = 42.dp
                )
            }
            RaftFormField(
                label = "Description",
                optional = true,
                hint = "Shown in channel discovery."
            ) {
                RaftBrutalInputField(
                    value = "Visual parity fixture",
                    onValueChange = {},
                    placeholder = "",
                    autoFocus = false,
                    fieldMinHeight = 42.dp
                )
            }
            RaftFormField(
                label = "Server Name",
                size = RaftFormFieldSize.Compact,
                error = "Name is required"
            ) {
                RaftBrutalInputField(
                    value = "",
                    onValueChange = {},
                    placeholder = "",
                    autoFocus = false,
                    fieldMinHeight = 42.dp,
                    borderColor = RaftColor.AccentRed
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
                .background(RaftColor.Canvas)
                .padding(16.dp)
    ) {
        RaftBrutalTextarea(
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

/** Tailwind `text-sm font-bold`: 14px on a 20px line. */
private val UiTextSmBold
    @Composable get() = RaftType.Body.copy(fontSize = 14.sp, lineHeight = 20.sp).withRaftFontWeight(FontWeight.Bold)

@Composable
internal fun UiAttentionDotVisualContent() {
    Box(
        modifier =
            Modifier
                .width(342.dp)
                .height(80.dp)
                .background(RaftColor.Canvas)
                .padding(16.dp)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                RaftAttentionDot()
                Text("Unread", style = UiTextSmBold, color = RaftColor.TextBlack)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                // web `size="sm"`: the 6px compact pixel.
                RaftAttentionDot(size = 6.dp)
                Text("Compact", style = UiTextSmBold, color = RaftColor.TextBlack)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                // web `tone="bg-presence-error"`.
                RaftAttentionDot(color = RaftColor.PresenceError)
                Text("Warning", style = UiTextSmBold, color = RaftColor.TextBlack)
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
                .background(RaftColor.Canvas)
                .padding(16.dp)
    ) {
        // web `ui-status-dot`: a single `gap-3` row of four StatusDots - online (md 9px),
        // error sm (8px), offline lg (11px), external (md). No pulse row (raft lights never
        // animate and the web case has none).
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            RaftAppStatusDot(color = RaftColor.PresenceOnline)
            RaftAppStatusDot(color = RaftColor.PresenceError, size = 8.dp)
            RaftAppStatusDot(color = RaftColor.PresenceOffline, size = 11.dp)
            RaftAppStatusDot(color = RaftColor.PresenceExternal)
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
                .background(RaftColor.Canvas)
                .padding(16.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            RaftProgressBar(modifier = Modifier.width(310.dp), value = 64, label = "Downloading update", showPercent = true, tone = RaftProgressBarTone.Cyan)
            RaftProgressBar(modifier = Modifier.width(310.dp), value = 28, label = "Verifying package", showPercent = true, tone = RaftProgressBarTone.Cyan)
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
                .background(RaftColor.Canvas)
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
            RaftSkeletonRow(avatar = true, avatarSize = 20.dp, lineWidths = listOf(128.dp, 80.dp))
            RaftAppSkeleton(modifier = Modifier.width(310.dp).height(48.dp), variant = RaftAppSkeletonVariant.Block)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                // web `<Skeleton variant="block" className="size-8" />` (square avatar stand-in).
                RaftAppSkeleton(modifier = Modifier.size(32.dp), variant = RaftAppSkeletonVariant.Block)
                RaftAppSkeleton(modifier = Modifier.width(160.dp).height(12.dp), variant = RaftAppSkeletonVariant.Line)
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
                .background(RaftColor.Canvas)
                .padding(16.dp)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
            RaftAppSpinner(size = RaftAppSpinnerSize.Xs, stillStep = UiSpinnerVisualStillStep)
            RaftAppSpinner(size = RaftAppSpinnerSize.Sm, stillStep = UiSpinnerVisualStillStep)
            RaftAppSpinner(size = RaftAppSpinnerSize.Md, stillStep = UiSpinnerVisualStillStep)
            RaftAppSpinner(size = RaftAppSpinnerSize.Lg, stillStep = UiSpinnerVisualStillStep)
            Box(
                modifier = Modifier.background(Color.Black).padding(8.dp).height(24.dp),
                contentAlignment = Alignment.BottomStart
            ) {
                RaftAppSpinner(size = RaftAppSpinnerSize.Sm, inverse = true, stillStep = UiSpinnerVisualStillStep)
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
                .background(RaftColor.Canvas)
                .padding(16.dp)
    ) {
        // raft-ui InputGroup has no shadow gutter, so react's gap-4 is 16dp.
        // React renders the second row read-only at opacity-60 (not disabled).
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            RaftSlugInput(value = "design-lab", onValueChange = {}, modifier = Modifier.width(310.dp))
            RaftSlugInput(
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
                .background(RaftColor.Canvas)
                .padding(16.dp)
    ) {
        // web `ui-section-eyebrow`: hint "Recent Activity", ink "Applications", and a
        // `bg-fill-muted px-2 py-1` label "Choose Avatar" that the flex column stretches to
        // the full 310px content width.
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            RaftAppSectionEyebrow(label = "Recent Activity")
            RaftAppSectionEyebrow(label = "Applications", color = RaftColor.TextBlack)
            Box(modifier = Modifier.fillMaxWidth().background(RaftColor.FillMuted).padding(horizontal = 8.dp, vertical = 4.dp)) {
                RaftAppSectionEyebrow(label = "Choose Avatar")
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
                .background(RaftColor.Canvas)
                .padding(16.dp)
    ) {
        RaftSelectionPopover(
            title = "Channels",
            showClear = true,
            search = "des",
            searchPlaceholder = "Search channels",
            options =
                listOf(
                    RaftSelectionPopoverOption("design", "design", checked = true, reserveLeadingSlot = true),
                    RaftSelectionPopoverOption("visual-testing", "visual-testing", reserveLeadingSlot = true),
                    RaftSelectionPopoverOption("archive", "archived channel", enabled = false, reserveLeadingSlot = true),
                    RaftSelectionPopoverOption("none", "No channel", italic = true, reserveLeadingSlot = true)
                )
        )
    }
}

@Composable
internal fun UiSelectVisualContent() {
    Box(
        modifier =
            Modifier
                .width(342.dp)
                .height(194.dp)
                .background(RaftColor.Canvas)
                .padding(16.dp)
    ) {
        // web `gap-4`: the raft trigger reserves no shadow, so the gap is the full 16dp.
        // The disabled select has value="" and shows its "Select..." placeholder, which
        // RaftAppSelectTrigger renders (placeholder ink/weight) for a blank label.
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            RaftAppSelectTrigger(label = "Codex")
            RaftAppSelectTrigger(label = "", enabled = false)
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
                .background(RaftColor.Canvas)
                .padding(16.dp)
    ) {
        // web: SectionHeader "Applications" with `border-b border-line-seam pb-2`.
        Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            RaftSectionHeader(
                label = "Applications",
                count = 3,
                modifier = Modifier.fillMaxWidth(),
                action = {
                    RaftButton(text = "Add", onClick = {})
                }
            )
            Box(modifier = Modifier.fillMaxWidth().height(RaftAppStroke.Thin).background(RaftColor.BorderSeam))
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
                .background(RaftColor.Canvas)
                .padding(16.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            RaftSurfaceListItem(interactive = false) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Private app", style = RaftType.ButtonBold.copy(fontSize = 14.sp, lineHeight = 20.sp), color = RaftColor.TextBlack, maxLines = 1)
                    Text("Available to this server", style = RaftType.Caption.copy(fontFamily = RaftMonoFamily, fontSize = 12.sp, lineHeight = 16.sp), color = RaftColor.TextMuted, maxLines = 1)
                }
            }
            RaftSurfaceListItem(selected = true, interactive = false) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Active integration", style = RaftType.ButtonBold.copy(fontSize = 14.sp, lineHeight = 20.sp), color = RaftColor.TextBlack, maxLines = 1)
                    Text("Selected list item state", style = RaftType.Caption.copy(fontFamily = RaftMonoFamily, fontSize = 12.sp, lineHeight = 16.sp), color = RaftColor.TextMuted, maxLines = 1)
                }
            }
        }
    }
}

package build.raft.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/** iOS native navigation: the bar's head stack and the trailing-button split. */
class RaftNativeNavBarBridgeTest {
    private class Recorder : RaftNativeNavBarListener {
        val titles = mutableListOf<String>()
        val nested = mutableListOf<Boolean>()
        val actions = mutableListOf<String>()
        val extras = mutableListOf<String>()
        val images = mutableListOf<RaftNativeTabBarAvatar?>()
        var cleared = 0

        override fun onNativeNavBarChanged(
            title: String,
            subtitle: String?,
            actionsJson: String,
            extrasJson: String,
            titleMenuJson: String,
            hasBack: Boolean,
            nested: Boolean,
            titleImage: RaftNativeTabBarAvatar?,
        ) {
            titles += title
            this.nested += nested
            actions += actionsJson
            extras += extrasJson
            images += titleImage
        }

        override fun onNativeNavBarCleared() {
            cleared += 1
        }

        override fun onNativeNavBarScroll(offset: Double, contentHeight: Double, dragging: Boolean) = Unit
    }

    private fun subtitleTap(extrasJson: String) = Json.parseToJsonElement(extrasJson).jsonObject["subtitleTap"]

    private fun head(title: String, onBack: (() -> Unit)? = null) =
        RaftNativeNavBarHead(title, null, emptyList(), RaftNativeNavBarTitleExtras(), onBack = onBack)

    @Test
    fun theHeadActivatedLastIsShownAndGivesTheBarBackWhenItLeaves() {
        val bridge = RaftNativeNavBarBridge()
        val recorder = Recorder()
        bridge.attachListener(recorder)
        val page = RaftNativeNavBarBridge.Entry()
        val subView = RaftNativeNavBarBridge.Entry()
        bridge.activate(page)
        bridge.publish(page, head("Settings"))
        bridge.activate(subView)
        var backs = 0
        bridge.publish(subView, head("Account", onBack = { backs += 1 }))
        assertEquals(listOf("Settings", "Account"), recorder.titles)
        assertEquals(listOf(false, true), recorder.nested)
        assertTrue(bridge.actionTapped(RaftNativeNavBarBackId))
        assertEquals(1, backs)
        bridge.deactivate(subView)
        assertEquals("Settings", recorder.titles.last())
        bridge.deactivate(page)
        assertEquals(1, recorder.cleared)
    }

    @Test
    fun aTappableSubtitleIsFlaggedToTheHostAndRunsItsHandler() {
        val bridge = RaftNativeNavBarBridge()
        val recorder = Recorder()
        bridge.attachListener(recorder)
        val page = RaftNativeNavBarBridge.Entry()
        bridge.activate(page)
        var opened = 0
        val extras = RaftNativeNavBarTitleExtras()
        bridge.publish(page, RaftNativeNavBarHead("@artin: hi", "@Lagoon · 3 replies", emptyList(), extras, onSubtitleClick = { opened += 1 }))
        assertEquals(JsonPrimitive(true), subtitleTap(recorder.extras.last()))
        assertTrue(bridge.actionTapped(RaftNativeNavBarSubtitleId))
        assertEquals(1, opened)
        // Without a subtitle line there is nothing to tap.
        bridge.publish(page, RaftNativeNavBarHead("@artin: hi", null, emptyList(), extras, onSubtitleClick = { opened += 1 }))
        assertEquals(null, subtitleTap(recorder.extras.last()))
        bridge.publish(page, RaftNativeNavBarHead("@artin: hi", "@Lagoon", emptyList(), extras))
        assertEquals(null, subtitleTap(recorder.extras.last()))
        assertEquals(false, bridge.actionTapped(RaftNativeNavBarSubtitleId))
    }

    @Test
    fun aKeptAliveTabPublishesNothingUntilActivated() {
        val bridge = RaftNativeNavBarBridge()
        val recorder = Recorder()
        bridge.attachListener(recorder)
        val tasks = RaftNativeNavBarBridge.Entry()
        bridge.publish(tasks, head("Tasks"))
        assertEquals(emptyList(), recorder.titles)
    }

    @Test
    fun trailingButtonsKeepTwoByPriorityAndTheRestGoToTheOverflow() {
        val actions =
            listOf("search", "settings", "focus", "members").map { id ->
                RaftNativeNavBarAction(id, id, null, {}, priority = if (id == "members") Int.MAX_VALUE else if (id == "focus") -1 else 0)
            }
        val split = raftNativeNavBarSplit(actions).associate { it.id to it.placement }
        assertEquals(RaftNativeNavBarPlacement.Trailing, split["members"])
        assertEquals(RaftNativeNavBarPlacement.Trailing, split["search"])
        assertEquals(RaftNativeNavBarPlacement.Overflow, split["settings"])
        assertEquals(RaftNativeNavBarPlacement.Overflow, split["focus"])
    }

    @Test
    fun aDeclinedHeadOnTopHidesTheBarAndGivesItBackWhenItLeaves() {
        val bridge = RaftNativeNavBarBridge()
        val recorder = Recorder()
        bridge.attachListener(recorder)
        val page = RaftNativeNavBarBridge.Entry()
        bridge.activate(page)
        bridge.publish(page, head("Settings"))
        val unmapped = RaftNativeNavBarBridge.Entry().also { it.declined = true }
        bridge.activate(unmapped)
        assertEquals(1, recorder.cleared)
        bridge.deactivate(unmapped)
        assertEquals(listOf("Settings", "Settings"), recorder.titles)
    }

    @Test
    fun aPageWhoseOnlyHeadDeclinedHidesTheBarOnceTheHostListens() {
        val bridge = RaftNativeNavBarBridge()
        bridge.activate(RaftNativeNavBarBridge.Entry().also { it.declined = true })
        val recorder = Recorder()
        bridge.attachListener(recorder)
        assertEquals(1, recorder.cleared)
        assertTrue(recorder.titles.isEmpty())
    }

    @Test
    fun barButtonsCarryTheirDesignSystemGlyphAsAnAssetAndTheSymbolAsFallback() {
        val bridge = RaftNativeNavBarBridge()
        val recorder = Recorder()
        bridge.attachListener(recorder)
        val page = RaftNativeNavBarBridge.Entry()
        bridge.activate(page)
        val members = RaftNativeNavBarAction("members", "Members", "person.2", {}, icon = RaftLucideIconName.Users)
        val text = RaftNativeNavBarAction("text", "Text", null, {})
        bridge.publish(page, RaftNativeNavBarHead("General", null, listOf(members, text), RaftNativeNavBarTitleExtras()))
        val json = recorder.actions.last()
        assertTrue(""""imageAsset":"lucide-users"""" in json, json)
        assertTrue(""""systemSymbol":"person.2"""" in json, json)
        assertEquals(1, Regex("imageAsset").findAll(json).count(), "a button without a glyph sends none")
    }

    @Test
    fun theAssetNameIsTheIosCatalogNameOfTheGlyph() {
        assertEquals("lucide-bell", raftNativeNavBarImageAssetName(RaftLucideIconName.Bell))
        assertEquals("lucide-direct-message", raftNativeNavBarImageAssetName(RaftLucideIconName.DirectMessage))
        assertEquals("lucide-ellipsis-vertical", raftNativeNavBarImageAssetName(RaftLucideIconName.EllipsisVertical))
    }

    @Test
    fun sideColumnActionsKeepTheGlyphTheColumnDraws() {
        val actions =
            listOf(
                raftSideColumnLucideAction("thread-search", "Search", RaftLucideIconName.Search, {}, systemSymbol = "magnifyingglass"),
                raftSideColumnLucideAction(FOCUS_MODE_MENU_ITEM_ID, "Focus", RaftLucideIconName.Maximize2, {}),
                raftSideColumnLucideAction(THREAD_TIMELINE_MENU_ITEM_ID, "Timeline", RaftLucideIconName.List, {}),
            ).toRaftNativeNavBarActions()
        assertEquals(listOf(RaftLucideIconName.Search, RaftLucideIconName.Maximize2, RaftLucideIconName.List), actions.map { it.icon })
        // The SF Symbol fallback stays: the action's own, else the id table's.
        assertEquals(listOf("magnifyingglass", "arrow.up.left.and.arrow.down.right", "list.bullet"), actions.map { it.systemSymbol })
    }

    @Test
    fun everyLucideWireNameMapsBack() {
        RaftLucideIconName.entries.forEach { assertEquals(it, raftLucideIconNameForWire(it.wireName)) }
        assertEquals(null, raftLucideIconNameForWire("not-a-glyph"))
    }

    @Test
    fun theChannelTileSendsTheComposeTileGlyph() {
        val bridge = RaftNativeNavBarBridge()
        val recorder = Recorder()
        bridge.attachListener(recorder)
        val page = RaftNativeNavBarBridge.Entry()
        bridge.activate(page)
        fun publish(kind: String?, publicPage: Boolean) {
            bridge.publish(
                page,
                RaftNativeNavBarHead("c", null, emptyList(), RaftNativeNavBarTitleExtras(leading = RaftNativeNavBarTitleLeading.Channel(kind, publicPage))),
            )
        }
        publish("channel", false)
        publish("private", false)
        publish("joint", false)
        publish("channel", true)
        val assets = recorder.extras.map { Regex(""""imageAsset":"([^"]+)"""").find(it)?.groupValues?.get(1) }
        assertEquals(listOf("lucide-hash", "lucide-lock", "lucide-git-branch", "lucide-globe"), assets)
    }

    @Test
    fun aCapturedTitleImageIsSentOnceAndANewCaptureAgain() {
        val bridge = RaftNativeNavBarBridge()
        val recorder = Recorder()
        bridge.attachListener(recorder)
        val page = RaftNativeNavBarBridge.Entry()
        bridge.activate(page)
        val avatar = RaftNativeNavBarTitleLeading.Avatar("Ada Lovelace", "pixel:robot", RaftAvatarKind.Labeled)
        val extras = RaftNativeNavBarTitleExtras(leading = avatar)
        bridge.publish(page, RaftNativeNavBarHead("Ada", null, emptyList(), extras))
        val image = RaftNativeTabBarAvatar(1, 1, intArrayOf(0xFF102030.toInt()))
        bridge.publish(page, RaftNativeNavBarHead("Ada", null, emptyList(), extras, titleImage = image))
        bridge.publish(page, RaftNativeNavBarHead("Ada", null, emptyList(), extras, titleImage = image))
        assertEquals(listOf(null, image), recorder.images)
        // The host draws the initials on the fill until the image arrives; no URL to fetch itself.
        assertTrue(""""kind":"avatar"""" in recorder.extras.first())
        assertTrue(""""label":"Ada Lovelace"""" in recorder.extras.first())
        assertTrue("imageUrl" !in recorder.extras.first())
        val next = RaftNativeTabBarAvatar(1, 1, intArrayOf(0xFF102030.toInt()))
        bridge.publish(page, RaftNativeNavBarHead("Ada", null, emptyList(), extras, titleImage = next))
        assertEquals(3, recorder.images.size)
        // An agent's or a person's stand-in carries no letters: their fallback face is the app's to draw.
        bridge.publish(page, RaftNativeNavBarHead("Ada", null, emptyList(), RaftNativeNavBarTitleExtras(leading = avatar.copy(kind = RaftAvatarKind.Agent))))
        assertTrue(""""label":""""" in recorder.extras.last())
    }

    @Test
    fun theBarTypeIsTheComposeHeadsTokens() {
        val title = RaftNativeNavBarTypography.title()
        assertEquals("SpaceGrotesk-Bold", title.fontName)
        assertEquals(RaftType.Headline.fontSize.value.toDouble(), title.size)
        assertEquals(17.0, title.size, "text-headline 17/22 700, not the system's 17pt semibold SF")
        val subtitle = RaftNativeNavBarTypography.subtitle()
        assertEquals(RaftType.Caption.fontSize.value.toDouble(), subtitle.size)
        assertEquals("SpaceGrotesk-Medium", subtitle.fontName)
    }

    @Test
    fun theCaptureRetriesSettleWithinHalfAMinute() {
        val retries = RaftNativeNavBarTitleImageRetryMillis
        assertEquals(0L, retries.first())
        assertEquals(retries.sorted(), retries)
        assertTrue(retries.last() <= 30_000L)
    }
}

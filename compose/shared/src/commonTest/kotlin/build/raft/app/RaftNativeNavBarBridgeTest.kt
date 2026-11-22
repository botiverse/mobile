package build.raft.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
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
    fun aPlainCountTravelsApartFromTheUnreadBadgeAndIsCappedLikeTheWeb() {
        val bridge = RaftNativeNavBarBridge()
        val recorder = Recorder()
        bridge.attachListener(recorder)
        val page = RaftNativeNavBarBridge.Entry()
        bridge.activate(page)
        val members = RaftNativeNavBarAction("members", "Members", "person.2", {}, count = raftNativeNavBarCountText(28))
        val bell = RaftNativeNavBarAction("bell", "Notifications", "bell", {}, badge = 3)
        bridge.publish(page, RaftNativeNavBarHead("General", null, listOf(members, bell), RaftNativeNavBarTitleExtras()))
        val rows = Json.parseToJsonElement(recorder.actions.last()).jsonArray.map { it.jsonObject }
        assertEquals(JsonPrimitive("28"), rows[0]["count"])
        assertEquals(null, rows[0]["badge"])
        assertEquals(JsonPrimitive(3), rows[1]["badge"])
        assertEquals(null, rows[1]["count"])
        assertEquals(listOf(null, "1", "99", "99+"), listOf(0, 1, 99, 100).map(::raftNativeNavBarCountText))
    }

    @Test
    fun everyLucideWireNameMapsBack() {
        RaftLucideIconName.entries.forEach { assertEquals(it, raftLucideIconNameForWire(it.wireName)) }
        assertEquals(null, raftLucideIconNameForWire("not-a-glyph"))
    }
}

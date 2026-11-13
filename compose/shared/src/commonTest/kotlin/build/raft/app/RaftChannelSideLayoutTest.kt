package build.raft.app

import build.raft.app.RaftChannelSideSection.Artifact
import build.raft.app.RaftChannelSideSection.Context
import build.raft.app.RaftChannelSideSection.Details
import build.raft.app.RaftChannelSideSection.Files
import build.raft.app.RaftChannelSideSection.Profile
import build.raft.app.RaftChannelSideSection.Tasks
import build.raft.app.RaftChannelSideSection.Thread
import build.raft.app.data.SyncTestPreferences
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlinx.serialization.json.Json

/**
 * The right-hand sections' layout (#189 step 4): the web's own cases, ported from
 * packages/shared/src/channelSideLayout.test.ts at slock origin/staging 5fa46117cd. What a saved value may be, how
 * missing sections are placed, what shows, and where a moved section lands.
 */
class RaftChannelSideLayoutTest {
    private fun col(vararg sections: RaftChannelSideSection, width: Int = 360) = RaftChannelSideColumn(sections.toList(), width)

    private fun layout(vararg columns: RaftChannelSideColumn) = RaftChannelSideLayout(columns.toList())

    private fun parse(json: String) = raftParseChannelSideLayout(Json.parseToJsonElement(json))

    /** The channel details (added after these cases were written) are left out of a shape: their own test checks where they go. */
    private fun shape(l: RaftChannelSideLayout) =
        l.columns.map { c -> c.sections.filter { it != Details }.joinToString("+") { it.wire } }.filter { it.isNotEmpty() }.joinToString(" | ")

    private fun fullShape(l: RaftChannelSideLayout) = l.columns.joinToString(" | ") { c -> c.sections.joinToString("+") { it.wire } }

    private val default = RAFT_DEFAULT_CHANNEL_SIDE_LAYOUT

    @Test
    fun aSavedLayoutIsV1OneToFourSideColumnsOfKnownSectionsNoneTwiceWidths160To4000() {
        assertEquals(default, raftParseChannelSideLayout(default.toJson()))
        assertEquals(layout(col(Tasks, width = 160)), parse("""{"v":1,"columns":[{"sections":["tasks"],"width":160}]}"""), "as narrow as 160 (#189, rule 2)")
        assertEquals(
            layout(col(Tasks, width = 300)),
            parse("""{"v":1,"columns":[{"sections":["tasks"],"width":300,"extra":true}],"also":1}"""),
            "other keys dropped",
        )
        val five = layout(col(Thread), col(Tasks), col(Files), col(Artifact), col(Profile))
        assertEquals(five, raftParseChannelSideLayout(five.toJson()), "four side columns and the thread's own")
        assertNull(
            parse(
                """{"v":1,"columns":[{"sections":["thread","tasks"],"width":360},{"sections":["files"],"width":360},""" +
                    """{"sections":["artifact"],"width":360},{"sections":["profile"],"width":360},{"sections":["thread"],"width":360}]}"""
            ),
            "a section twice",
        )
        val sixColumns =
            listOf("thread", "tasks", "files", "artifact", "profile", "thread").joinToString(",", "[", "]") { """{"sections":["$it"],"width":300}""" }
        val fiveTasks = List(5) { """{"sections":["tasks"],"width":300}""" }.joinToString(",", "[", "]")
        for (bad in listOf(
            "null", "[]", "\"x\"", """{"v":2,"columns":[{"sections":["tasks"],"width":300}]}""",
            """{"v":1,"columns":[]}""",
            """{"v":1,"columns":$fiveTasks}""",
            """{"v":1,"columns":$sixColumns}""",
            """{"v":1,"columns":[{"sections":[],"width":300}]}""",
            """{"v":1,"columns":[{"sections":["board"],"width":300}]}""",
            """{"v":1,"columns":[{"sections":["tasks"],"width":300},{"sections":["tasks"],"width":300}]}""",
            """{"v":1,"columns":[{"sections":["tasks"],"width":159}]}""",
            """{"v":1,"columns":[{"sections":["tasks"],"width":4001}]}""",
            """{"v":1,"columns":[{"sections":["tasks"],"width":300.5}]}""",
            """{"v":1,"columns":[{"sections":"tasks","width":300}]}""",
        )) {
            assertNull(parse(bad), bad)
        }
        // Not JSON at all, or nothing kept: never saved.
        assertNull(raftParseChannelSideLayoutText("{"))
        assertNull(raftParseChannelSideLayoutText(null))
    }

    @Test
    fun aSectionThisBuildDoesntKnowIsDroppedNotTheWholeLayoutAColumnOfOnlyThoseGoesToo() {
        assertEquals(layout(col(Profile, width = 400)), parse("""{"v":1,"columns":[{"sections":["profile","futureSection"],"width":400}]}"""))
        assertEquals(
            layout(col(Tasks, width = 300), col(Artifact, width = 480)),
            parse(
                """{"v":1,"columns":[{"sections":["tasks"],"width":300},{"sections":["futureSection"],"width":360},""" +
                    """{"sections":["artifact",7],"width":480}]}"""
            ),
        )
        assertNull(parse("""{"v":1,"columns":[{"sections":["futureSection"],"width":360}]}"""), "nothing known left")
    }

    @Test
    fun aLayoutSavedWithTheRetiredExecutionRecordReadsWithoutIt() {
        assertEquals(
            layout(col(Thread, width = 400), col(Profile, Context, width = 400)),
            parse(
                """{"v":1,"columns":[{"sections":["thread"],"width":400},{"sections":["profile","record","context"],"width":400},""" +
                    """{"sections":["record"],"width":360}]}"""
            ),
        )
    }

    @Test
    fun completingPlacesEachMissingSectionWhereTheDefaultWould() {
        assertEquals("thread | tasks+files | artifact | profile+context", shape(raftCompleteChannelSideLayout(layout(col(Tasks)))))
        assertEquals(
            "thread+files+tasks | artifact | profile+context",
            shape(raftCompleteChannelSideLayout(layout(col(Thread, Files)))),
            "Tasks under Files, wherever that is",
        )
        assertEquals("files+tasks | thread | artifact | profile+context", shape(raftCompleteChannelSideLayout(layout(col(Files), col(Thread)))))
        assertEquals("thread | tasks+files | artifact | profile+context", shape(raftCompleteChannelSideLayout(default)))
        assertEquals(default, raftCompleteChannelSideLayout(default), "the default is complete")
        // A layout saved with Tasks and Files apart (before the Artifact and the profile were sections): four side columns
        // and the thread's own; one already at four side columns takes the others at the bottom of its last.
        assertEquals("thread | tasks | files | artifact | profile+context", shape(raftCompleteChannelSideLayout(layout(col(Thread), col(Tasks), col(Files)))))
        assertEquals("thread+tasks | files | artifact | profile+context", shape(raftCompleteChannelSideLayout(layout(col(Thread, Tasks), col(Files)))))
        assertEquals(
            "thread+tasks | files | artifact | profile+context",
            shape(raftCompleteChannelSideLayout(layout(col(Thread, Tasks), col(Files), col(Artifact)))),
        )
        assertEquals(
            "thread+tasks | files | profile+context | artifact",
            shape(raftCompleteChannelSideLayout(layout(col(Thread, Tasks), col(Files), col(Profile), col(Artifact)))),
        )
        assertEquals(480, raftCompleteChannelSideLayout(layout(col(Tasks))).columns[2].width, "the Artifact's own column: its 480")
        assertEquals(400, raftCompleteChannelSideLayout(layout(col(Tasks))).columns[3].width, "the profile's: 400")
        // The context view, added after the others: under the profile wherever it is, else a column of its own.
        assertEquals(
            "thread | profile+context | tasks+files | artifact",
            shape(raftCompleteChannelSideLayout(layout(col(Thread), col(Profile), col(Tasks, Files), col(Artifact)))),
        )
        assertEquals(
            "thread | tasks+files+profile+context | artifact",
            shape(raftCompleteChannelSideLayout(layout(col(Thread), col(Tasks, Files, Profile), col(Artifact)))),
        )
    }

    @Test
    fun aColumnsMinimumIsItsWidestSections() {
        assertEquals(280, raftChannelSideColumnMinWidth(listOf(Tasks, Files)))
        assertEquals(360, raftChannelSideColumnMinWidth(listOf(Tasks, Thread)))
        assertEquals(480, raftChannelSideColumnMinWidth(listOf(Artifact, Tasks)))
        assertEquals(320, raftChannelSideColumnMinWidth(listOf(Profile)))
    }

    @Test
    fun onlyColumnsWithAnOpenSectionShowWithOnlyThoseSections() {
        val l = layout(col(Thread, width = 400), col(Tasks, Files, width = 360))
        assertEquals(listOf(Triple(1, listOf(Tasks), 280)), raftVisibleChannelSideColumns(l, setOf(Tasks)).map { Triple(it.column, it.sections, it.minWidth) })
        assertEquals(listOf(0 to listOf(Thread), 1 to listOf(Files)), raftVisibleChannelSideColumns(l, setOf(Thread, Files)).map { it.column to it.sections })
        assertEquals(emptyList(), raftVisibleChannelSideColumns(l, emptySet()))
    }

    @Test
    fun intoAColumnBeforeASectionOrAtTheBottom() {
        val l = raftCompleteChannelSideLayout(default)
        assertEquals("thread | files+tasks | artifact | profile+context", shape(raftMoveChannelSideSection(l, Files, RaftChannelSideDrop.Into(1, Tasks))))
        assertEquals("thread+tasks | files | artifact | profile+context", shape(raftMoveChannelSideSection(l, Tasks, RaftChannelSideDrop.Into(0, null))))
        assertEquals(
            "tasks+thread+files | artifact | profile+context",
            shape(raftMoveChannelSideSection(l, Thread, RaftChannelSideDrop.Into(1, Files))),
            "the thread's own column, now empty, goes",
        )
        assertEquals(
            "thread | tasks+files | artifact+profile+context",
            shape(raftMoveChannelSideSection(l, Profile, RaftChannelSideDrop.Into(2, null))),
            "the profile stacks under the Artifact, its context view with it",
        )
        assertEquals(480, raftMoveChannelSideSection(l, Profile, RaftChannelSideDrop.Into(2, null)).columns[2].width, "the column it joins keeps its width")
        assertSame(l, raftMoveChannelSideSection(l, Tasks, RaftChannelSideDrop.Into(1, Files)), "where it already is: unchanged")
        assertSame(l, raftMoveChannelSideSection(l, Tasks, RaftChannelSideDrop.Into(1, Tasks)))
        assertSame(l, raftMoveChannelSideSection(l, Tasks, RaftChannelSideDrop.Into(7, null)), "no such column")
    }

    @Test
    fun aNewColumnAtTheEdgesAtMostFourSideColumns() {
        val l = raftCompleteChannelSideLayout(default)
        val split = raftMoveChannelSideSection(l, Files, RaftChannelSideDrop.NewColumn(2))
        assertEquals("thread | tasks | files | artifact | profile+context", shape(split), "four side columns and the thread's own")
        assertEquals(360, split.columns[2].width, "out of a stack: Files' own default")
        assertEquals("tasks | thread | files | artifact | profile+context", shape(raftMoveChannelSideSection(l, Tasks, RaftChannelSideDrop.NewColumn(0))))
        assertSame(l, raftMoveChannelSideSection(l, Thread, RaftChannelSideDrop.NewColumn(0)), "alone at its own left edge")
        assertSame(l, raftMoveChannelSideSection(l, Thread, RaftChannelSideDrop.NewColumn(1)), "alone at its own right edge")
        assertEquals(
            "tasks+files | thread | artifact | profile+context",
            shape(raftMoveChannelSideSection(l, Thread, RaftChannelSideDrop.NewColumn(2))),
            "a lone section moving: same count",
        )
        // The profile (the context view stacked under the Artifact), moved left of Tasks' column, keeps its saved width.
        val wide = raftResizeChannelSideColumn(raftMoveChannelSideSection(l, Context, RaftChannelSideDrop.Into(2, null)), 3, 520f)
        val moved = raftMoveChannelSideSection(wide, Profile, RaftChannelSideDrop.NewColumn(1))
        assertEquals("thread | profile | tasks+files | artifact+context", shape(moved))
        assertEquals(520, moved.columns[1].width)
        // Four sections besides the thread: the cap of four side columns holds whatever moves (the thread's own column
        // aside), and stacking is always a place.
        assertNotSame(split, raftMoveChannelSideSection(split, Artifact, RaftChannelSideDrop.Into(4, null)))
        val full = layout(col(Thread, Tasks), col(Files), col(Artifact), col(Profile))
        assertEquals("thread | files | artifact | profile | tasks", shape(raftMoveChannelSideSection(full, Tasks, RaftChannelSideDrop.NewColumn(4))))
        // The thread out of a stack into a column of its own doesn't count among them.
        val stacked = layout(col(Thread, Tasks), col(Files), col(Artifact), col(Profile))
        assertEquals("thread | tasks | files | artifact | profile", shape(raftMoveChannelSideSection(stacked, Thread, RaftChannelSideDrop.NewColumn(0))))
    }

    @Test
    fun resizingRoundsAndKeepsTheWidthWithinTheSavedRange() {
        val l = raftCompleteChannelSideLayout(default)
        assertEquals(411, raftResizeChannelSideColumn(l, 1, 410.6f).columns[1].width)
        assertEquals(160, raftResizeChannelSideColumn(l, 1, 10f).columns[1].width, "a column goes to 160 (#189); its sections clip")
        assertSame(l, raftResizeChannelSideColumn(l, 1, 360f), "unchanged")
        assertSame(l, raftResizeChannelSideColumn(l, 5, 400f))
    }

    @Test
    fun theContextViewFollowsTheProfileAndNeverHoldsAColumnAlone() {
        val l = raftCompleteChannelSideLayout(default)
        assertEquals("thread | tasks+files | artifact | profile+context", shape(l))
        assertEquals(320, raftChannelSideColumnMinWidth(listOf(Context)))
        // The profile into the Artifact's column, Files split out; the Artifact may still move out.
        val profileIn = raftMoveChannelSideSection(l, Profile, RaftChannelSideDrop.Into(2, null))
        assertEquals("thread | tasks+files | artifact+profile+context", shape(profileIn))
        val filesOut = raftMoveChannelSideSection(profileIn, Files, RaftChannelSideDrop.NewColumn(2))
        assertEquals("thread | tasks | files | artifact+profile+context", shape(filesOut))
        val artifactOut = raftMoveChannelSideSection(filesOut, Artifact, RaftChannelSideDrop.NewColumn(4))
        assertNotEquals(filesOut, artifactOut, "the Artifact moves to a column of its own: no hidden context column takes the fourth place")
        assertEquals("thread | tasks | files | profile+context | artifact", shape(artifactOut))

        // Moving the profile carries the context view, into a stack or into a column of its own.
        assertEquals("thread | tasks+profile+context+files | artifact", shape(raftMoveChannelSideSection(l, Profile, RaftChannelSideDrop.Into(1, Files))))
        val own = raftMoveChannelSideSection(profileIn, Profile, RaftChannelSideDrop.NewColumn(1))
        assertEquals("thread | profile+context | tasks+files | artifact", shape(own))
        assertEquals(raftChannelSideSectionWidth(Profile), own.columns[1].width, "out of a stack: the profile's own width")

        // Out of a stack shared with others, the context view goes with the profile rather than staying in that stack.
        val stacked = layout(col(Thread), col(Tasks, Files, Profile, Context), col(Artifact))
        assertEquals("thread | tasks+files | profile+context | artifact", shape(raftMoveChannelSideSection(stacked, Profile, RaftChannelSideDrop.NewColumn(2))))
        // Moved by itself it stays where it is put; the profile then moves alone.
        val contextMoved = raftMoveChannelSideSection(l, Context, RaftChannelSideDrop.Into(1, null))
        assertEquals("thread | tasks+files+context | artifact | profile", shape(contextMoved))
        assertEquals("thread | tasks+files+context | artifact+profile", shape(raftMoveChannelSideSection(contextMoved, Profile, RaftChannelSideDrop.Into(2, null))))
        // Dropped into a column of its own, it goes back under the profile: nothing changes.
        assertEquals(l, raftMoveChannelSideSection(l, Context, RaftChannelSideDrop.NewColumn(4)))
    }

    @Test
    fun aStoredLayoutWithAColumnHoldingOnlyTheContextViewNormalisesToItUnderTheProfile() {
        assertEquals(
            "thread | tasks+files | profile+context | artifact",
            shape(raftCompleteChannelSideLayout(layout(col(Thread), col(Tasks, Files), col(Profile), col(Context), col(Artifact)))),
        )
        assertEquals(
            "thread | tasks+files | artifact+profile+context",
            shape(raftCompleteChannelSideLayout(layout(col(Context), col(Thread), col(Tasks, Files, width = 360), col(Artifact, Profile)))),
        )
        assertEquals(
            "thread | tasks+files | profile+context | artifact",
            shape(raftCompleteChannelSideLayout(layout(col(Thread), col(Context), col(Tasks, Files), col(Profile)))),
        )
        // A layout saved before the context view existed gets it under the profile.
        assertEquals(
            "thread | tasks+files | artifact | profile+context",
            shape(raftCompleteChannelSideLayout(layout(col(Thread), col(Tasks, Files), col(Artifact), col(Profile)))),
        )
    }

    @Test
    fun theChannelDetailsFollowTheProfile() {
        assertEquals("thread | tasks+files | artifact | profile+context+details", fullShape(default))
        // A layout saved before the details existed gets them under the profile, after the context view.
        assertEquals(
            "thread | tasks+files | profile+context+details | artifact",
            fullShape(raftCompleteChannelSideLayout(layout(col(Thread), col(Tasks, Files), col(Profile, Context), col(Artifact)))),
        )
        val l = raftCompleteChannelSideLayout(default)
        // The profile carries them.
        assertEquals("thread | tasks+files | artifact+profile+context+details", fullShape(raftMoveChannelSideSection(l, Profile, RaftChannelSideDrop.Into(2, null))))
        // Moved by themselves into a stack they stay there; dropped into a column of their own they go back under the profile.
        assertEquals("thread | tasks+files+details | artifact | profile+context", fullShape(raftMoveChannelSideSection(l, Details, RaftChannelSideDrop.Into(1, null))))
        assertEquals(l, raftMoveChannelSideSection(l, Details, RaftChannelSideDrop.NewColumn(4)))
        assertEquals(
            "thread | tasks+files | profile+context+details | artifact",
            fullShape(raftCompleteChannelSideLayout(layout(col(Thread), col(Details), col(Tasks, Files), col(Profile)))),
        )
        assertEquals(320, raftChannelSideColumnMinWidth(listOf(Details)))
        // A reader that knows them keeps them where they were saved.
        assertEquals(
            layout(col(Thread, width = 400), col(Profile, Details, width = 400)),
            parse("""{"v":1,"columns":[{"sections":["thread"],"width":400},{"sections":["profile","details"],"width":400}]}"""),
        )
        // A section a reader doesn't know (as "details" is to an older one) is dropped and the rest of the layout kept.
        assertEquals(
            layout(col(Thread, width = 400), col(Profile, width = 400)),
            parse("""{"v":1,"columns":[{"sections":["thread"],"width":400},{"sections":["profile","not-yet-a-section"],"width":400}]}"""),
        )
    }

    // App-only: the layout is kept per device in the web's shape, completed on read, and the pane grid stands its cells by it.
    @Test
    fun theLayoutIsKeptPerDeviceInTheWebsShape() {
        val prefs = SyncTestPreferences()
        val store = RaftChannelSideLayoutStore { prefs }
        assertEquals(default, store.layout, "nothing kept: the default")
        assertEquals(null, prefs.values[RaftChannelSideLayoutStore.KEY], "reading writes nothing")
        store.move(Files, RaftChannelSideDrop.NewColumn(2))
        assertEquals("thread | tasks | files | artifact | profile+context", shape(store.layout))
        assertEquals(store.layout, raftParseChannelSideLayoutText(prefs.values[RaftChannelSideLayoutStore.KEY]), "kept as the web keeps it")
        // A new store on the same device reads it back.
        assertEquals(store.layout, RaftChannelSideLayoutStore { prefs }.layout)
        // A kept layout missing sections is completed on read; one that is not a layout reads as the default.
        prefs.values[RaftChannelSideLayoutStore.KEY] = """{"v":1,"columns":[{"sections":["tasks"],"width":300}]}"""
        assertEquals("thread | tasks+files | artifact | profile+context", shape(RaftChannelSideLayoutStore { prefs }.layout))
        prefs.values[RaftChannelSideLayoutStore.KEY] = """{"v":1,"columns":[]}"""
        assertEquals(default, RaftChannelSideLayoutStore { prefs }.layout)
        // The grid's arrangement: the sections' cell ids per column, with the column's width.
        val arrangement = default.paneGridArrangement()
        assertEquals(listOf(listOf("thread"), listOf("tasks", "files"), listOf("artifact"), listOf("profile", "context", "details")), arrangement.columns.map { it.cells })
        assertEquals(listOf(400f, 360f, 480f, 400f), arrangement.columns.map { it.widthDp })
    }
}

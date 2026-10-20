package build.raft.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ThreadFirstContentStagingTest {
    @Test
    fun earlyLoadingShellCoversLoadingWithoutRenderableContent() {
        assertTrue(
            RaftThreadScreenPayload(
                status = "loading",
                originStatus = "none"
            ).shouldRenderThreadEarlyLoadingShell()
        )
        assertTrue(
            RaftThreadScreenPayload(
                status = "loading",
                originStatus = "loading"
            ).shouldRenderThreadEarlyLoadingShell()
        )

        assertFalse(
            RaftThreadScreenPayload(
                status = "loading",
                sourceThreadId = "channel-1",
                streamChannelId = "channel-1",
                originStatus = "none"
            ).shouldRenderThreadEarlyLoadingShell()
        )
        assertFalse(
            RaftThreadScreenPayload(
                status = "loading",
                surfaceKind = RaftThreadSurfaceKind.ReplyThread,
                parentChannelId = "channel-1",
                parentMessageId = "m1",
                originParentMessageId = "m1",
                originStatus = "none"
            ).shouldRenderThreadEarlyLoadingShell()
        )

        assertFalse(
            RaftThreadScreenPayload(
                status = "content",
                originStatus = "none"
            ).shouldRenderThreadEarlyLoadingShell()
        )
        assertFalse(
            RaftThreadScreenPayload(
                status = "loading",
                originStatus = "loading",
                originPreview = "Loading original message..."
            ).shouldRenderThreadEarlyLoadingShell()
        )
    }

    @Test
    fun backgroundRefreshingKeepsRenderableContentVisible() {
        assertFalse(
            RaftThreadScreenPayload(
                status = "content",
                messages = listOf(
                    RaftThreadMessageItem(
                        id = "message-1",
                        authorId = "user-1",
                        authorName = "User One",
                        body = "Loaded"
                    )
                ),
                backgroundRefreshing = true
            ).shouldRenderThreadEarlyLoadingShell()
        )
        assertTrue(
            RaftThreadScreenPayload(
                status = "content",
                backgroundRefreshing = true
            ).shouldRenderThreadEarlyLoadingShell()
        )
    }

    @Test
    fun payloadReadyMountPlanMountsTabOwnersAndKeepsSuggestionsLazy() {
        val loadingScaffold =
            ThreadRouteMountPlan.LoadingShell.toThreadRouteScaffoldPlan()
        val contentScaffold =
            ThreadRouteMountPlan.FeatureSurfaces.toThreadRouteScaffoldPlan()

        assertEquals(loadingScaffold.rootIdentity, contentScaffold.rootIdentity)
        assertTrue(loadingScaffold.mountLoadingBody)
        assertFalse(loadingScaffold.mountFeatureBody)
        assertFalse(contentScaffold.mountLoadingBody)
        assertTrue(contentScaffold.mountFeatureBody)

        val loading =
            threadFeatureOwnerMountPlan(
                routeMountPlan = ThreadRouteMountPlan.LoadingShell,
                selectedTab = "chat",
                joined = true,
                showComposer = true,
                suggestionDemand = ThreadComposerSuggestionDemand.Members
            )
        assertFalse(loading.mountChatTimeline)
        assertFalse(loading.mountComposer)
        assertFalse(loading.mountTasksOwner)
        assertFalse(loading.mountFilesOwner)
        assertFalse(loading.mountMemberSuggestionsOwner)

        val firstPayload =
            threadFeatureOwnerMountPlan(
                routeMountPlan = ThreadRouteMountPlan.FeatureSurfaces,
                selectedTab = "chat",
                joined = true,
                showComposer = true,
                suggestionDemand = ThreadComposerSuggestionDemand.None
            )
        assertTrue(firstPayload.mountChatTimeline)
        assertTrue(firstPayload.mountComposer)
        // keepItemAlive pre-composes inactive tabs, so both owners stay mounted.
        assertTrue(firstPayload.mountTasksOwner)
        assertTrue(firstPayload.mountFilesOwner)
        assertFalse(firstPayload.mountMemberSuggestionsOwner)
        assertFalse(firstPayload.mountChannelSuggestionsOwner)

        val memberSuggestionInteraction =
            threadFeatureOwnerMountPlan(
                routeMountPlan = ThreadRouteMountPlan.FeatureSurfaces,
                selectedTab = "chat",
                joined = true,
                showComposer = true,
                suggestionDemand = ThreadComposerSuggestionDemand.Members
            )
        assertTrue(memberSuggestionInteraction.mountMemberSuggestionsOwner)
        assertFalse(memberSuggestionInteraction.mountChannelSuggestionsOwner)

        val tasksSelection =
            threadFeatureOwnerMountPlan(
                routeMountPlan = ThreadRouteMountPlan.FeatureSurfaces,
                selectedTab = "tasks",
                joined = true,
                showComposer = true,
                suggestionDemand = ThreadComposerSuggestionDemand.None
            )
        assertTrue(tasksSelection.mountTasksOwner)
        assertTrue(tasksSelection.mountFilesOwner)
        assertFalse(tasksSelection.mountComposer)

        val filesSelection =
            threadFeatureOwnerMountPlan(
                routeMountPlan = ThreadRouteMountPlan.FeatureSurfaces,
                selectedTab = "files",
                joined = true,
                showComposer = true,
                suggestionDemand = ThreadComposerSuggestionDemand.None
            )
        assertTrue(filesSelection.mountFilesOwner)
        assertTrue(filesSelection.mountTasksOwner)
        assertFalse(filesSelection.mountComposer)
    }

}

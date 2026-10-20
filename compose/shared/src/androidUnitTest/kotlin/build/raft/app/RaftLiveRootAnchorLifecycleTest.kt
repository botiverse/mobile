package build.raft.app

import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Composition
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.node.PointerInputModifierNode
import androidx.compose.ui.platform.ViewConfiguration
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import java.lang.reflect.Proxy
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.jvm.functions.Function0
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Drives Compose's real LayoutNode and pointer-input element contracts. Internal Kotlin
 * declarations remain JVM-public with module-suffixed method names, so reflection lets the app
 * module verify production Modifier.Node wiring instead of stopping at helper-only tests.
 */
@RunWith(RobolectricTestRunner::class)
class RaftLiveRootAnchorLifecycleTest {
    @Test
    fun sameOwnerDeactivateReuseRecapturesThroughProductionModifierAttach() {
        val anchorRef = RaftLiveRootAnchorRef(identity = "message:same")
        val harness = LayoutNodeHarness()
        val layoutNode = harness.attach(Modifier.raftCaptureLiveRootAnchor(anchorRef))
        harness.flushEndApplyChanges()
        val coordinates = harness.coordinates(layoutNode)
        // Model the initial real onGloballyPositioned capture explicitly. The regression tooth is
        // the later same-owner reattach, which must recover without another positioned callback.
        anchorRef.captureFromLayoutCoordinates(coordinates)
        assertNotNull(anchorRef.resolve(RaftPopoverLocalPoint.Exact(Offset.Zero)))

        harness.deactivate(layoutNode)
        assertNull(anchorRef.resolve(RaftPopoverLocalPoint.Exact(Offset.Zero)))

        harness.reuse(layoutNode)
        assertNull(anchorRef.resolve(RaftPopoverLocalPoint.Exact(Offset.Zero)))
        harness.flushEndApplyChanges()

        assertNotNull(anchorRef.resolve(RaftPopoverLocalPoint.Exact(Offset.Zero)))
    }

    @Test
    fun staleCaptureNodeDetachDoesNotInvalidateNewCaptureNodeForSameOwner() {
        val anchorRef = RaftLiveRootAnchorRef(identity = "message:same")
        val harness = LayoutNodeHarness()

        fun attachCaptureNode(): Any {
            val node = harness.attach(Modifier.raftCaptureLiveRootAnchor(anchorRef))
            harness.flushEndApplyChanges()
            assertNotNull(anchorRef.resolve(RaftPopoverLocalPoint.Exact(Offset.Zero)))
            return node
        }

        val staleNode = attachCaptureNode()
        val currentNode = attachCaptureNode()

        // A replacement row may attach before the prior row's delayed detach. The stale node must
        // not revoke the replacement node's capture owner.
        harness.deactivate(staleNode)
        assertNotNull(anchorRef.resolve(RaftPopoverLocalPoint.Exact(Offset.Zero)))

        harness.deactivate(currentNode)
        assertNull(anchorRef.resolve(RaftPopoverLocalPoint.Exact(Offset.Zero)))
    }

    @Test
    fun sameRowCompositionRecreationKeepsGestureOwnerAndPressStateStable() {
        val store = ownerStore()
        val key = ThreadMessageLongPressGestureOwnerKey("thread-page:message-1", "message-1")
        val firstOwner = store.ownerFor(key)
        store.retain(key, firstOwner)
        val firstLease = assertNotNull(firstOwner.captureActiveLease())
        assertTrue(firstOwner.showPressFeedback(firstLease, Offset(12f, 34f)))
        val recreatedOwner = store.ownerFor(instanceKey = "thread-page:message-1", messageId = "message-1")

        assertSame(firstOwner, recreatedOwner)
        assertSame(firstOwner.rootAnchor, recreatedOwner.rootAnchor)
        assertSame(firstOwner.tapMovementGate, recreatedOwner.tapMovementGate)
        assertSame(firstOwner.tapDispatchScope, recreatedOwner.tapDispatchScope)
        assertTrue(recreatedOwner.pressed)
        assertEquals(Offset(12f, 34f), recreatedOwner.pressRippleOrigin)
        assertPointerHandlerKeptAcrossUpdate(
            pointerModifier("tap_movement", firstOwner),
            pointerModifier("tap_movement", recreatedOwner)
        )
        assertPointerHandlerKeptAcrossUpdate(
            pointerModifier("actions", firstOwner),
            pointerModifier("actions", recreatedOwner)
        )

        recreatedOwner.hidePressFeedback(firstLease)
        assertFalse(firstOwner.pressed)
        assertEquals(Offset(12f, 34f), firstOwner.pressRippleOrigin)
        recreatedOwner.clearReleasedPressRipple(firstLease, Offset(12f, 34f))
        assertNull(firstOwner.pressRippleOrigin)
    }

    @Test
    fun sameOwnerReceivesReplacementCallbacksWithoutReplacingPointerHandler() {
        val store = ownerStore()
        val key = ThreadMessageLongPressGestureOwnerKey("thread-page:message-1", "message-1")
        val owner = store.ownerFor(key)
        store.retain(key, owner)
        val lease = assertNotNull(owner.captureActiveLease())
        var oldCallbackInvoked = false
        var replacementCallbackInvoked = false
        owner.updateCallbacks(ThreadMessageLongPressCallbacks(onTap = { oldCallbackInvoked = true }))
        val oldCallbacks = assertNotNull(owner.callbacksSnapshot(lease))
        val pointerBeforeReplacement = pointerModifier("actions", owner)

        owner.updateCallbacks(
            ThreadMessageLongPressCallbacks(onTap = { replacementCallbackInvoked = true })
        )
        owner.callbacksSnapshot(lease)?.onTap?.invoke()

        assertFalse(oldCallbackInvoked)
        assertTrue(replacementCallbackInvoked)
        assertPointerHandlerKeptAcrossUpdate(
            pointerBeforeReplacement,
            pointerModifier("actions", owner)
        )
        oldCallbacks.onTap?.invoke()
        assertTrue(oldCallbackInvoked)
    }

    @Test
    fun recycledLazySlotForDifferentMessageGetsDifferentGestureOwner() {
        val store = ownerStore()
        val firstOwner = store.ownerFor(instanceKey = "reused-slot", messageId = "message-1")
        val recycledOwner = store.ownerFor(instanceKey = "reused-slot", messageId = "message-2")

        assertNotSame(firstOwner, recycledOwner)
        assertNotSame(firstOwner.rootAnchor, recycledOwner.rootAnchor)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun retentionEvictsOnlyFinalizedInactiveGestureOwners() = runTest {
        val store = ownerStore(scope = this, maxRetainedOwners = 1)
        val firstKey = ThreadMessageLongPressGestureOwnerKey("thread-page:message-1", "message-1")
        val secondKey = ThreadMessageLongPressGestureOwnerKey("thread-page:message-2", "message-2")
        val firstOwner = store.ownerFor(firstKey)
        store.retain(firstKey, firstOwner)
        val secondOwner = store.ownerFor(secondKey)
        store.retain(secondKey, secondOwner)

        store.release(firstKey, firstOwner)
        advanceUntilIdle()

        assertNotSame(firstOwner, store.ownerFor(firstKey))
        assertSame(secondOwner, store.ownerFor(secondKey))
    }

    @Test
    fun replacementGestureAuthorityRestartsBothPointerHandlers() {
        val firstOwner =
            ownerStore()
                .ownerFor(instanceKey = "thread-page:message-1", messageId = "message-1")
        val replacementOwner =
            ownerStore()
                .ownerFor(instanceKey = "thread-page:message-1", messageId = "message-1")

        assertNotSame(firstOwner, replacementOwner)
        assertNotEquals(
            pointerModifier("tap_movement", firstOwner),
            pointerModifier("tap_movement", replacementOwner)
        )
        assertNotEquals(
            pointerModifier("actions", firstOwner),
            pointerModifier("actions", replacementOwner)
        )
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun realDownAcrossActualSameRowReplacementKeepsPressAndUsesCurrentCallback() = runTest {
        val compositionHarness = GestureCompositionHarness(this)
        val layoutHarness = LayoutNodeHarness(coroutineContext + compositionHarness.frameClock)
        val lifecycle = mutableListOf<String>()
        val tapCallbacks = mutableListOf<String>()
        var mountedModifier: Modifier? = null
        var mountedOwner: ThreadMessageLongPressGestureOwner? = null
        var layoutNode: Any? = null
        try {
            fun mount(revision: Int) {
                compositionHarness.setContent {
                    GestureRowLifecycleContent(
                        store = compositionHarness.ownerStore,
                        instanceKey = "thread-page:slot-1",
                        messageId = "message-1",
                        revision = revision,
                        onTap = { tapCallbacks += "revision-$revision" },
                        onLifecycle = { lifecycle += it },
                        onMounted = { modifier, owner ->
                            mountedModifier = modifier
                            mountedOwner = owner
                        }
                    )
                }
                compositionHarness.settleWithoutAdvancingTime()
            }

            mount(revision = 1)
            val firstOwner = assertNotNull(mountedOwner)
            val firstLease = assertNotNull(firstOwner.captureActiveLease())
            layoutNode = layoutHarness.attach(assertNotNull(mountedModifier))
            layoutHarness.flushEndApplyChanges()
            val retainedPointers = layoutHarness.pointerNodes(assertNotNull(layoutNode))
            assertEquals(2, retainedPointers.size)

            layoutHarness.dispatchDown(retainedPointers, uptimeMillis = 0L)
            runCurrent()
            advanceTimeBy(150L)
            runCurrent()
            assertTrue(firstOwner.pressed)
            assertEquals(Offset(24f, 32f), firstOwner.pressRippleOrigin)

            mount(revision = 2)
            val replacementOwner = assertNotNull(mountedOwner)
            val replacementLease = assertNotNull(replacementOwner.captureActiveLease())
            layoutHarness.update(assertNotNull(layoutNode), assertNotNull(mountedModifier))
            val pointersAfterReplacement = layoutHarness.pointerNodes(assertNotNull(layoutNode))

            assertSame(firstOwner, replacementOwner)
            assertEquals(firstLease, replacementLease)
            assertTrue(replacementOwner.pressed)
            assertEquals(
                listOf("enter:message-1:1", "dispose:message-1:1", "enter:message-1:2"),
                lifecycle
            )
            assertEquals(retainedPointers.size, pointersAfterReplacement.size)
            retainedPointers.zip(pointersAfterReplacement).forEach { (before, after) ->
                assertSame(before, after)
            }

            layoutHarness.dispatchUp(pointersAfterReplacement, uptimeMillis = 160L)
            runCurrent()
            assertTrue(tapCallbacks.isEmpty(), "tap dispatch must remain deferred until a frame")
            compositionHarness.sendFrame()
            runCurrent()

            assertEquals(listOf("revision-2"), tapCallbacks)
            assertFalse(replacementOwner.pressed)
        } finally {
            layoutNode?.let(layoutHarness::deactivate)
            compositionHarness.close()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun actualRowRemovalRejectsDeferredTapAndClearsDisposedPressState() = runTest {
        val compositionHarness = GestureCompositionHarness(this)
        val layoutHarness = LayoutNodeHarness(coroutineContext + compositionHarness.frameClock)
        var mountedModifier: Modifier? = null
        var mountedOwner: ThreadMessageLongPressGestureOwner? = null
        var disposed = false
        var callbackCount = 0
        var disposedStateWrites = 0
        var layoutNode: Any? = null
        try {
            compositionHarness.setContent {
                GestureRowLifecycleContent(
                    store = compositionHarness.ownerStore,
                    instanceKey = "thread-page:slot-1",
                    messageId = "message-1",
                    revision = 1,
                    onTap = {
                        callbackCount += 1
                        if (disposed) disposedStateWrites += 1
                    },
                    onLifecycle = { event ->
                        if (event == "dispose:message-1:1") disposed = true
                    },
                    onMounted = { modifier, owner ->
                        mountedModifier = modifier
                        mountedOwner = owner
                    }
                )
            }
            compositionHarness.settleWithoutAdvancingTime()
            val removedOwner = assertNotNull(mountedOwner)
            val removedLease = assertNotNull(removedOwner.captureActiveLease())
            layoutNode = layoutHarness.attach(assertNotNull(mountedModifier))
            layoutHarness.flushEndApplyChanges()
            val retainedPointers = layoutHarness.pointerNodes(assertNotNull(layoutNode))

            layoutHarness.dispatchDown(retainedPointers, uptimeMillis = 0L)
            runCurrent()
            advanceTimeBy(150L)
            runCurrent()
            assertTrue(removedOwner.pressed)
            assertNotNull(removedOwner.pressRippleOrigin)

            layoutHarness.dispatchUp(retainedPointers, uptimeMillis = 160L)
            runCurrent()
            assertEquals(0, callbackCount)

            compositionHarness.setContent {
                CompositionLocalProvider(
                    LocalThreadMessageLongPressGestureOwnerStore provides compositionHarness.ownerStore
                ) { }
            }
            compositionHarness.settleWithoutAdvancingTime()

            assertTrue(disposed)

            compositionHarness.sendFrame()
            runCurrent()

            assertEquals(0, callbackCount)
            assertEquals(0, disposedStateWrites)
            assertNull(removedOwner.captureActiveLease())
            assertFalse(removedOwner.pressed)
            assertNull(removedOwner.pressRippleOrigin)

            compositionHarness.setContent {
                GestureRowLifecycleContent(
                    store = compositionHarness.ownerStore,
                    instanceKey = "thread-page:slot-1",
                    messageId = "message-1",
                    revision = 2,
                    onTap = { },
                    onMounted = { modifier, owner ->
                        mountedModifier = modifier
                        mountedOwner = owner
                    }
                )
            }
            compositionHarness.settleWithoutAdvancingTime()
            val remountedOwner = assertNotNull(mountedOwner)
            val remountedLease = assertNotNull(remountedOwner.captureActiveLease())
            assertSame(removedOwner, remountedOwner)
            assertNotEquals(removedLease, remountedLease)
            assertFalse(remountedOwner.isLeaseActive(removedLease))
        } finally {
            layoutNode?.let(layoutHarness::deactivate)
            compositionHarness.close()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun recycledSlotPermanentlyRejectsOldLeaseAndDispatchesOnlyNewMessage() = runTest {
        val compositionHarness = GestureCompositionHarness(this)
        val layoutHarness = LayoutNodeHarness(coroutineContext + compositionHarness.frameClock)
        val taps = mutableListOf<String>()
        var mountedModifier: Modifier? = null
        var mountedOwner: ThreadMessageLongPressGestureOwner? = null
        var layoutNode: Any? = null
        try {
            fun mount(messageId: String) {
                compositionHarness.setContent {
                    GestureRowLifecycleContent(
                        store = compositionHarness.ownerStore,
                        instanceKey = "recycled-slot",
                        messageId = messageId,
                        revision = 1,
                        onTap = { taps += messageId },
                        onMounted = { modifier, owner ->
                            mountedModifier = modifier
                            mountedOwner = owner
                        }
                    )
                }
                compositionHarness.settleWithoutAdvancingTime()
            }

            mount("message-1")
            val oldOwner = assertNotNull(mountedOwner)
            val oldLease = assertNotNull(oldOwner.captureActiveLease())
            layoutNode = layoutHarness.attach(assertNotNull(mountedModifier))
            layoutHarness.flushEndApplyChanges()
            val oldPointers = layoutHarness.pointerNodes(assertNotNull(layoutNode))

            layoutHarness.dispatchDown(oldPointers, uptimeMillis = 0L)
            runCurrent()
            layoutHarness.dispatchUp(oldPointers, uptimeMillis = 20L)
            runCurrent()
            assertTrue(taps.isEmpty())

            mount("message-2")
            val newOwner = assertNotNull(mountedOwner)
            layoutHarness.update(assertNotNull(layoutNode), assertNotNull(mountedModifier))
            val newPointers = layoutHarness.pointerNodes(assertNotNull(layoutNode))

            assertNotSame(oldOwner, newOwner)
            assertFalse(oldOwner.isLeaseActive(oldLease))
            assertNull(oldOwner.captureActiveLease())
            assertEquals(2, newPointers.size)

            compositionHarness.sendFrame()
            runCurrent()
            assertTrue(taps.isEmpty(), "the recycled message must not receive the old deferred tap")

            layoutHarness.dispatchDown(newPointers, uptimeMillis = 40L)
            runCurrent()
            layoutHarness.dispatchUp(newPointers, uptimeMillis = 60L)
            runCurrent()
            compositionHarness.sendFrame()
            runCurrent()

            assertEquals(listOf("message-2"), taps)
        } finally {
            layoutNode?.let(layoutHarness::deactivate)
            compositionHarness.close()
        }
    }

    /**
     * CMP switch: Kuikly's pointer-input element compared equal across recompositions. Upstream
     * `SuspendPointerInputElement.equals` also compares the handler instance (a fresh lambda per
     * composition), but `SuspendingPointerInputModifierNodeImpl.update` only restarts the running
     * gesture coroutine when a key changed or the handler *class* changed. Assert exactly that
     * reset condition, so a recomposition never cancels an in-flight press.
     */
    private fun assertPointerHandlerKeptAcrossUpdate(before: Modifier, after: Modifier) {
        fun Modifier.field(name: String): Any? =
            javaClass.getMethod("get" + name.replaceFirstChar { it.uppercase() }).invoke(this)
        assertEquals(
            "androidx.compose.ui.input.pointer.SuspendPointerInputElement",
            before.javaClass.name
        )
        assertEquals(before.javaClass, after.javaClass)
        assertEquals(before.field("key1"), after.field("key1"))
        assertEquals(before.field("key2"), after.field("key2"))
        assertTrue(
            (before.field("keys") as Array<*>?).contentEquals(after.field("keys") as Array<*>?)
        )
        assertSame(
            before.field("pointerInputEventHandler")!!.javaClass,
            after.field("pointerInputEventHandler")!!.javaClass
        )
    }

    private fun pointerModifier(role: String, owner: ThreadMessageLongPressGestureOwner): Modifier =
        Modifier.threadMessagePointerInput(
            role = role,
            messageId = "message-1",
            pointerSessionKey = 7,
            gestureOwner = owner
        ) { }

    private fun ownerStore(
        scope: CoroutineScope = CoroutineScope(EmptyCoroutineContext),
        maxRetainedOwners: Int = ThreadMessageLongPressGestureOwnerRetentionLimit
    ): ThreadMessageLongPressGestureOwnerStore =
        ThreadMessageLongPressGestureOwnerStore(
            tapDispatchScope = scope,
            maxRetainedOwners = maxRetainedOwners
        )

    private class LayoutNodeHarness(
        private val coroutineContext: CoroutineContext = EmptyCoroutineContext
    ) {
        private val endApplyChangesListeners = mutableListOf<Function0<*>>()
        private val layoutNodeClass = Class.forName("androidx.compose.ui.node.LayoutNode")
        private val ownerClass = Class.forName("androidx.compose.ui.node.Owner")
        // Compose UI 1.11+ attaches every LayoutNode to `Owner.rectManager` (non-null); build the
        // real one through its all-defaults synthetic constructor (mask 0b11 = both parameters).
        private val rectManager: Any by lazy {
            val rectManagerClass = Class.forName("androidx.compose.ui.spatial.RectManager")
            rectManagerClass.declaredConstructors
                .single { it.parameterTypes.size == 4 && it.parameterTypes[2] == Int::class.javaPrimitiveType }
                .newInstance(null, null, 0b11, null)
        }
        private val owner =
            Proxy.newProxyInstance(ownerClass.classLoader, arrayOf(ownerClass)) { _, method, args ->
                when (method.name) {
                    "getRectManager" -> rectManager
                    "getDensity" -> Density(1f)
                    "getLayoutDirection" -> LayoutDirection.Ltr
                    "getCoroutineContext" -> coroutineContext
                    "getViewConfiguration" -> TestViewConfiguration
                    "registerOnEndApplyChangesListener" -> {
                        @Suppress("UNCHECKED_CAST")
                        endApplyChangesListeners += args!![0] as Function0<*>
                        null
                    }
                    "toString" -> "RaftLiveRootAnchorLifecycleTest.Owner"
                    else -> primitiveDefault(method.returnType)
                }
            }

        fun attach(modifier: Modifier): Any =
            layoutNodeClass.getConstructor().newInstance().also { node ->
                layoutNodeClass
                    .getMethod("setModifier", Modifier::class.java)
                    .invoke(node, modifier)
                layoutNodeClass.methods
                    .single { it.name.startsWith("attach$") }
                    .invoke(node, owner)
            }

        fun deactivate(node: Any) {
            layoutNodeClass.getMethod("onDeactivate").invoke(node)
        }

        fun update(node: Any, modifier: Modifier) {
            layoutNodeClass
                .getMethod("setModifier", Modifier::class.java)
                .invoke(node, modifier)
            flushEndApplyChanges()
        }

        fun reuse(node: Any) {
            layoutNodeClass.getMethod("onReuse").invoke(node)
        }

        fun coordinates(node: Any): LayoutCoordinates =
            layoutNodeClass.getMethod("getCoordinates").invoke(node) as LayoutCoordinates

        fun flushEndApplyChanges() {
            val pending = endApplyChangesListeners.toList()
            endApplyChangesListeners.clear()
            pending.forEach { it.invoke() }
        }

        fun pointerNodes(node: Any): List<PointerInputModifierNode> {
            val nodeChain =
                layoutNodeClass.getDeclaredField("nodes").run {
                    isAccessible = true
                    get(node)
                }
            var current =
                nodeChain.javaClass.getDeclaredField("head").run {
                    isAccessible = true
                    get(nodeChain)
                }
            val childField = Modifier.Node::class.java.getDeclaredField("child").apply {
                isAccessible = true
            }
            return buildList {
                while (current is Modifier.Node) {
                    (current as? PointerInputModifierNode)?.let(::add)
                    current = childField.get(current)
                }
            }
        }

        fun dispatchDown(nodes: List<PointerInputModifierNode>, uptimeMillis: Long) {
            dispatch(
                nodes,
                PointerEvent(
                    listOf(
                        PointerInputChange(
                            id = PointerId(1L),
                            uptimeMillis = uptimeMillis,
                            position = Offset(24f, 32f),
                            pressed = true,
                            previousUptimeMillis = uptimeMillis,
                            previousPosition = Offset(24f, 32f),
                            previousPressed = false,
                            isInitiallyConsumed = false
                        )
                    )
                )
            )
        }

        fun dispatchUp(nodes: List<PointerInputModifierNode>, uptimeMillis: Long) {
            dispatch(
                nodes,
                PointerEvent(
                    listOf(
                        PointerInputChange(
                            id = PointerId(1L),
                            uptimeMillis = uptimeMillis,
                            position = Offset(24f, 32f),
                            pressed = false,
                            previousUptimeMillis = uptimeMillis - 1L,
                            previousPosition = Offset(24f, 32f),
                            previousPressed = true,
                            isInitiallyConsumed = false
                        )
                    )
                )
            )
        }

        private fun dispatch(nodes: List<PointerInputModifierNode>, event: PointerEvent) {
            val bounds = IntSize(width = 240, height = 120)
            nodes.forEach { it.onPointerEvent(event, PointerEventPass.Initial, bounds) }
            nodes.asReversed().forEach { it.onPointerEvent(event, PointerEventPass.Main, bounds) }
            nodes.forEach { it.onPointerEvent(event, PointerEventPass.Final, bounds) }
        }

        private fun primitiveDefault(returnType: Class<*>): Any? =
            when (returnType) {
                java.lang.Boolean.TYPE -> false
                java.lang.Byte.TYPE -> 0.toByte()
                java.lang.Short.TYPE -> 0.toShort()
                java.lang.Integer.TYPE -> 0
                java.lang.Long.TYPE -> 0L
                java.lang.Float.TYPE -> 0f
                java.lang.Double.TYPE -> 0.0
                java.lang.Character.TYPE -> '\u0000'
                else -> null
            }
    }

    private companion object {
        val TestViewConfiguration =
            object : ViewConfiguration {
                override val longPressTimeoutMillis: Long = 400L
                override val doubleTapTimeoutMillis: Long = 300L
                override val doubleTapMinTimeMillis: Long = 40L
                override val touchSlop: Float = 16f
                override val minimumTouchTargetSize: DpSize = DpSize.Zero
            }
    }
}

@Composable
private fun GestureRowLifecycleContent(
    store: ThreadMessageLongPressGestureOwnerStore,
    instanceKey: Any,
    messageId: String,
    revision: Int,
    onTap: () -> Unit,
    onLifecycle: (String) -> Unit = {},
    onMounted: (Modifier, ThreadMessageLongPressGestureOwner) -> Unit
) {
    CompositionLocalProvider(LocalThreadMessageLongPressGestureOwnerStore provides store) {
        key(messageId, revision) {
            val owner =
                rememberThreadMessageLongPressGestureOwner(
                    instanceKey = instanceKey,
                    messageId = messageId
                )
            val modifier =
                Modifier.threadMessageLongPressActions(
                    messageId = messageId,
                    pointerSessionKey = 7,
                    gestureOwner = owner,
                    onTap = onTap,
                    onShowActions = { }
                )
            DisposableEffect(messageId, revision) {
                onLifecycle("enter:$messageId:$revision")
                onDispose { onLifecycle("dispose:$messageId:$revision") }
            }
            SideEffect { onMounted(modifier, owner) }
        }
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
private class GestureCompositionHarness(
    private val testScope: kotlinx.coroutines.test.TestScope
) {
    val frameClock = BroadcastFrameClock()
    val ownerStore =
        ThreadMessageLongPressGestureOwnerStore(
            tapDispatchScope = CoroutineScope(testScope.coroutineContext + frameClock)
        )
    private val recomposer = Recomposer(testScope.coroutineContext + frameClock)
    private val composition = Composition(GestureLifecycleUnitApplier(), recomposer)
    private val recomposerJob =
        testScope.launch(frameClock) { recomposer.runRecomposeAndApplyChanges() }

    fun setContent(content: @Composable () -> Unit) {
        composition.setContent(content)
    }

    fun settleWithoutAdvancingTime() {
        testScope.runCurrent()
    }

    fun sendFrame() {
        frameClock.sendFrame(System.nanoTime())
    }

    suspend fun close() {
        composition.dispose()
        testScope.runCurrent()
        recomposer.close()
        recomposerJob.join()
    }
}

private class GestureLifecycleUnitApplier : AbstractApplier<Unit>(Unit) {
    override fun insertBottomUp(index: Int, instance: Unit) = Unit

    override fun insertTopDown(index: Int, instance: Unit) = Unit

    override fun move(from: Int, to: Int, count: Int) = Unit

    override fun onClear() = Unit

    override fun remove(index: Int, count: Int) = Unit
}

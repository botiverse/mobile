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
import build.raft.app.thread.LocalThreadMessageLongPressGestureOwnerStore
import build.raft.app.thread.ThreadMessageLongPressGestureOwner
import build.raft.app.thread.ThreadMessageLongPressGestureOwnerRetentionLimit
import build.raft.app.thread.ThreadMessageLongPressGestureOwnerStore
import build.raft.app.thread.dispatch
import build.raft.app.thread.rememberThreadMessageLongPressGestureOwner
import build.raft.app.thread.threadMessageLongPressActions
import java.lang.reflect.Proxy
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.jvm.functions.Function0
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
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
            assertEquals(3, newPointers.size) // context_click, tap_movement, actions

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

package build.raft.app

import com.tencent.kuikly.compose.ui.Modifier
import com.tencent.kuikly.compose.ui.geometry.Offset
import com.tencent.kuikly.compose.ui.layout.LayoutCoordinates
import com.tencent.kuikly.compose.ui.unit.Density
import com.tencent.kuikly.compose.ui.unit.LayoutDirection
import java.lang.reflect.Proxy
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.jvm.functions.Function0
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Drives Kuikly's real LayoutNode lifecycle without compile-time access to its internal test API.
 * Internal Kotlin declarations remain JVM-public with module-suffixed method names, so reflection
 * lets the app module verify the production Modifier.Node wiring instead of only its helper.
 */
class SlockLiveRootAnchorLifecycleTest {
    @Test
    fun sameOwnerDeactivateReuseRecapturesThroughProductionModifierAttach() {
        val anchorRef = SlockLiveRootAnchorRef(identity = "message:same")
        val endApplyChangesListeners = mutableListOf<Function0<*>>()
        val layoutNodeClass = Class.forName("com.tencent.kuikly.compose.ui.node.LayoutNode")
        val ownerClass = Class.forName("com.tencent.kuikly.compose.ui.node.Owner")
        val layoutNode = layoutNodeClass.getConstructor().newInstance()
        val owner =
            Proxy.newProxyInstance(ownerClass.classLoader, arrayOf(ownerClass)) { _, method, args ->
                when (method.name) {
                    "getDensity" -> Density(1f)
                    "getLayoutDirection" -> LayoutDirection.Ltr
                    "getCoroutineContext" -> EmptyCoroutineContext
                    "registerOnEndApplyChangesListener" -> {
                        @Suppress("UNCHECKED_CAST")
                        endApplyChangesListeners += args!![0] as Function0<*>
                        null
                    }
                    "toString" -> "SlockLiveRootAnchorLifecycleTest.Owner"
                    else -> primitiveDefault(method.returnType)
                }
            }

        layoutNodeClass
            .getMethod("setModifier", Modifier::class.java)
            .invoke(layoutNode, Modifier.slockCaptureLiveRootAnchor(anchorRef))
        layoutNodeClass.methods
            .single { it.name.startsWith("attach\$") }
            .invoke(layoutNode, owner)
        endApplyChangesListeners.flush()
        val coordinates =
            layoutNodeClass.getMethod("getCoordinates").invoke(layoutNode) as LayoutCoordinates
        // Model the initial real onGloballyPositioned capture explicitly. The regression tooth is
        // the later same-owner reattach, which must recover without another positioned callback.
        anchorRef.captureFromLayoutCoordinates(coordinates)
        assertNotNull(anchorRef.resolve(SlockPopoverLocalPoint.Exact(Offset.Zero)))

        layoutNodeClass.getMethod("onDeactivate").invoke(layoutNode)
        assertNull(anchorRef.resolve(SlockPopoverLocalPoint.Exact(Offset.Zero)))

        layoutNodeClass.getMethod("onReuse").invoke(layoutNode)
        assertNull(anchorRef.resolve(SlockPopoverLocalPoint.Exact(Offset.Zero)))
        endApplyChangesListeners.flush()

        assertNotNull(anchorRef.resolve(SlockPopoverLocalPoint.Exact(Offset.Zero)))
    }

    @Test
    fun staleCaptureNodeDetachDoesNotInvalidateNewCaptureNodeForSameOwner() {
        val anchorRef = SlockLiveRootAnchorRef(identity = "message:same")
        val endApplyChangesListeners = mutableListOf<Function0<*>>()
        val layoutNodeClass = Class.forName("com.tencent.kuikly.compose.ui.node.LayoutNode")
        val ownerClass = Class.forName("com.tencent.kuikly.compose.ui.node.Owner")
        val owner =
            Proxy.newProxyInstance(ownerClass.classLoader, arrayOf(ownerClass)) { _, method, args ->
                when (method.name) {
                    "getDensity" -> Density(1f)
                    "getLayoutDirection" -> LayoutDirection.Ltr
                    "getCoroutineContext" -> EmptyCoroutineContext
                    "registerOnEndApplyChangesListener" -> {
                        @Suppress("UNCHECKED_CAST")
                        endApplyChangesListeners += args!![0] as Function0<*>
                        null
                    }
                    "toString" -> "SlockLiveRootAnchorLifecycleTest.Owner"
                    else -> primitiveDefault(method.returnType)
                }
            }

        fun attachCaptureNode(): Any {
            val node = layoutNodeClass.getConstructor().newInstance()
            layoutNodeClass
                .getMethod("setModifier", Modifier::class.java)
                .invoke(node, Modifier.slockCaptureLiveRootAnchor(anchorRef))
            layoutNodeClass.methods
                .single { it.name.startsWith("attach$") }
                .invoke(node, owner)
            endApplyChangesListeners.flush()
            assertNotNull(anchorRef.resolve(SlockPopoverLocalPoint.Exact(Offset.Zero)))
            return node
        }

        val staleNode = attachCaptureNode()
        val currentNode = attachCaptureNode()

        // IME/lazy relayout can attach the replacement for the same semantic message before the
        // prior node's delayed detach. That stale detach must not revoke the replacement's lease.
        layoutNodeClass.getMethod("onDeactivate").invoke(staleNode)
        assertNotNull(anchorRef.resolve(SlockPopoverLocalPoint.Exact(Offset.Zero)))

        layoutNodeClass.getMethod("onDeactivate").invoke(currentNode)
        assertNull(anchorRef.resolve(SlockPopoverLocalPoint.Exact(Offset.Zero)))
    }

    private fun MutableList<Function0<*>>.flush() {
        val pending = toList()
        clear()
        pending.forEach { it.invoke() }
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

package build.raft.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput

internal data class SlockGestureSemanticIdentity(
    val surface: String,
    val value: Any?
)

internal class SlockGestureSession<Callbacks : Any> internal constructor(
    private val owner: SlockGestureOwner<Callbacks>,
    internal val generationToken: Any,
    private val frozenCallbacks: Callbacks
) {
    fun isCurrent(): Boolean = owner.isCurrent(generationToken)

    fun currentCallbacksOrNull(): Callbacks? = frozenCallbacks.takeIf { isCurrent() }
}

/**
 * Owns the current authority read by a long-lived Kuikly pointer-input coroutine.
 *
 * Kuikly compares pointer-input elements by keys and skips the handler update when they are equal.
 * The owner token therefore has two jobs: it stays stable while Compose retains this remember slot so
 * callback changes do not cancel an in-flight gesture, and it changes when this owner is actually
 * disposed/remounted or its semantic identity changes. A retained modifier node then either reads the
 * same owner's updated slot or gets a different pointer key; it cannot retain an orphan callback holder.
 * A handler calls [beginGesture] at DOWN (or drag start) to freeze one callback generation through
 * UP/CANCEL.
 *
 * This owner intentionally does not preserve a gesture across actual composition disposal. A later
 * mount with the same semantic identity gets a new opaque token, while every late read from the disposed
 * owner is rejected. Surfaces that must preserve an in-flight gesture through a real 1 -> 0 -> 1
 * replacement need a retained owner store and lease, not this composition-local API.
 */
internal class SlockGestureOwner<Callbacks : Any> internal constructor(
    val semanticIdentity: SlockGestureSemanticIdentity,
    initialCallbacks: Callbacks
) {
    internal val pointerInputOwnerToken: Any = Any()

    private var currentCallbacks: Callbacks = initialCallbacks
    private var currentGestureToken: Any? = null
    private var active: Boolean = true

    internal fun updateCallbacks(callbacks: Callbacks) {
        if (active) currentCallbacks = callbacks
    }

    fun beginGesture(): SlockGestureSession<Callbacks> {
        val generationToken = Any()
        if (active) currentGestureToken = generationToken
        return SlockGestureSession(this, generationToken, currentCallbacks)
    }

    internal fun isCurrent(generationToken: Any): Boolean =
        active && currentGestureToken === generationToken

    internal fun dispose() {
        // Irreversible: a cancelled old handler must not resurrect this owner via a late beginGesture.
        active = false
        currentGestureToken = null
    }

    internal fun pointerInputKeys(vararg behaviorKeys: Any?): Array<out Any?> =
        arrayOf(pointerInputOwnerToken, *behaviorKeys)
}

@Composable
internal fun <Callbacks : Any> rememberSlockGestureOwner(
    surface: String,
    semanticIdentity: Any?,
    callbacks: Callbacks
): SlockGestureOwner<Callbacks> {
    require(surface.isNotBlank()) { "gesture surface must not be blank" }
    val identity = remember(surface, semanticIdentity) { SlockGestureSemanticIdentity(surface, semanticIdentity) }
    val owner = remember(identity) { SlockGestureOwner(identity, callbacks) }
    SideEffect { owner.updateCallbacks(callbacks) }
    DisposableEffect(owner) {
        onDispose { owner.dispose() }
    }
    return owner
}

internal fun <Callbacks : Any> Modifier.slockGesturePointerInput(
    owner: SlockGestureOwner<Callbacks>,
    vararg behaviorKeys: Any?,
    block: suspend PointerInputScope.(SlockGestureOwner<Callbacks>) -> Unit
): Modifier =
    pointerInput(*owner.pointerInputKeys(*behaviorKeys)) {
        block(owner)
    }

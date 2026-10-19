package build.raft.app

import androidx.compose.runtime.Composable
import build.raft.app.compat.ui.MakeKuiklyComposeNode
import androidx.compose.ui.Modifier
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.size
import androidx.compose.ui.unit.Dp
import com.tencent.kuikly.core.base.Attr
import com.tencent.kuikly.core.base.DeclarativeBaseView
import com.tencent.kuikly.core.base.event.Event

internal class SlockNativePixelAvatarView : DeclarativeBaseView<SlockNativePixelAvatarAttr, Event>() {
    private var appliedBackground: String? = null
    private var appliedSpans: String? = null
    private var appliedCacheKey: String? = null

    override fun createAttr(): SlockNativePixelAvatarAttr = SlockNativePixelAvatarAttr()

    override fun createEvent(): Event = Event()

    override fun viewName(): String = VIEW_NAME

    fun applyAvatarAttrs(
        background: String,
        spans: String,
        cacheKey: String,
        force: Boolean = false
    ) {
        if (
            !force &&
                appliedBackground == background &&
                appliedSpans == spans &&
                appliedCacheKey == cacheKey
        ) {
            return
        }
        appliedBackground = background
        appliedSpans = spans
        appliedCacheKey = cacheKey
        getViewAttr().run {
            background(background)
            spans(spans)
            cacheKey(cacheKey)
        }
    }

    companion object {
        const val VIEW_NAME = "SlockPixelAvatarView"
    }
}

internal class SlockNativePixelAvatarAttr : Attr() {
    fun background(value: String): SlockNativePixelAvatarAttr {
        "background" with value
        return this
    }

    fun spans(value: String): SlockNativePixelAvatarAttr {
        "spans" with value
        return this
    }

    fun cacheKey(value: String): SlockNativePixelAvatarAttr {
        "cacheKey" with value
        return this
    }
}

@Composable
internal fun SlockNativePixelAvatar(
    spec: PixelAvatarSpec,
    modifier: Modifier = Modifier,
    avatarSize: Dp,
    borderWidth: Dp = SlockStroke.Thin
) {
    val background = spec.backgroundArgb
    val spans = spec.nativeSpanPayload
    val cacheKey = spec.nativeCacheKey
    MakeKuiklyComposeNode<SlockNativePixelAvatarView>(
        factory = { SlockNativePixelAvatarView() },
        modifier = modifier
            .size(width = avatarSize, height = avatarSize)
            .border(borderWidth, SlockColor.BorderBlack),
        viewInit = {
            applyAvatarAttrs(
                background = background,
                spans = spans,
                cacheKey = cacheKey,
                force = true
            )
        },
        viewUpdate = { view ->
            view.applyAvatarAttrs(
                background = background,
                spans = spans,
                cacheKey = cacheKey
            )
        }
    )
}

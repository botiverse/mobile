package build.raft.app

import com.tencent.kuikly.compose.ui.geometry.Offset

internal class SlockMutableOffsetRef(
    initialValue: Offset = Offset.Zero
) {
    var value: Offset = initialValue
        private set
    var hasValue: Boolean = false
        private set

    fun update(next: Offset) {
        value = next
        hasValue = true
    }
}

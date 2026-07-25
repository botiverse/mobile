package build.raft.app

import kotlin.test.Test
import kotlin.test.assertEquals

class SharedHomeSavedGesturePolicyTest {
    @Test
    fun trailingActionZoneExclusivelyOwnsUnsaveWhileContentOwnsOpen() {
        assertEquals(
            SharedHomeSavedGestureOwner.Open,
            sharedHomeSavedGestureOwner(tapX = 255f, rowWidthPx = 300f, trailingActionWidthPx = 44f)
        )
        assertEquals(
            SharedHomeSavedGestureOwner.Unsave,
            sharedHomeSavedGestureOwner(tapX = 256f, rowWidthPx = 300f, trailingActionWidthPx = 44f)
        )
        assertEquals(
            SharedHomeSavedGestureOwner.Unsave,
            sharedHomeSavedGestureOwner(tapX = 299f, rowWidthPx = 300f, trailingActionWidthPx = 44f)
        )
    }

    @Test
    fun invalidOrAbsentActionGeometryFailsToRowOpenWithoutInventingUnsave() {
        assertEquals(
            SharedHomeSavedGestureOwner.Open,
            sharedHomeSavedGestureOwner(tapX = 10f, rowWidthPx = 0f, trailingActionWidthPx = 44f)
        )
        assertEquals(
            SharedHomeSavedGestureOwner.Open,
            sharedHomeSavedGestureOwner(tapX = 299f, rowWidthPx = 300f, trailingActionWidthPx = 0f)
        )
    }

    @Test
    fun actionZoneIsClampedToTheMeasuredRowWidth() {
        assertEquals(
            SharedHomeSavedGestureOwner.Unsave,
            sharedHomeSavedGestureOwner(tapX = 0f, rowWidthPx = 32f, trailingActionWidthPx = 44f)
        )
    }
}

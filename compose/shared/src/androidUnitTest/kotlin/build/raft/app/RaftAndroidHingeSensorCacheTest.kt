package build.raft.app

import android.content.Context
import android.content.ContextWrapper
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RaftAndroidHingeSensorCacheTest {
    @Before
    fun setUp() {
        RaftAndroidHingeSensorCache.resetForTest()
    }

    @After
    fun tearDown() {
        RaftAndroidHingeSensorCache.resetForTest()
    }

    @Test
    fun cachesFeatureLookupForSameApplicationContext() {
        val app = RuntimeEnvironment.getApplication()
        var queries = 0

        assertTrue(RaftAndroidHingeSensorCache.hasHingeAngle(app) { queries += 1; true })
        assertTrue(RaftAndroidHingeSensorCache.hasHingeAngle(app) { queries += 1; false })
        assertTrue(RaftAndroidHingeSensorCache.hasHingeAngle(ContextWrapper(app)) { queries += 1; false })
        assertEquals(1, queries)
    }

    @Test
    fun cachesFalseResultAndRequeriesForDistinctContext() {
        val firstContext = isolatedAppContext()
        val secondContext = isolatedAppContext()
        var queries = 0

        assertFalse(RaftAndroidHingeSensorCache.hasHingeAngle(firstContext) { queries += 1; false })
        assertFalse(RaftAndroidHingeSensorCache.hasHingeAngle(firstContext) { queries += 1; true })
        assertEquals(1, queries)

        assertTrue(RaftAndroidHingeSensorCache.hasHingeAngle(secondContext) { queries += 1; true })
        assertEquals(2, queries)
    }

    @Test
    fun doesNotCacheFailedLookup() {
        val context = isolatedAppContext()
        var queries = 0

        assertFalse(
            RaftAndroidHingeSensorCache.hasHingeAngle(context) {
                queries += 1
                error("package manager unavailable")
            }
        )
        assertTrue(RaftAndroidHingeSensorCache.hasHingeAngle(context) { queries += 1; true })
        assertTrue(RaftAndroidHingeSensorCache.hasHingeAngle(context) { queries += 1; false })
        assertEquals(2, queries)
    }

    private fun isolatedAppContext(): Context =
        object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getApplicationContext(): Context = this
        }
}

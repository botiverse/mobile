package build.raft.app.image

import coil3.SingletonImageLoader
import coil3.annotation.DelicateCoilApi
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertSame
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * [raftInstallImageLoader] never throws: when something already created Coil's default singleton
 * (a `SingletonImageLoader.get` first, as another test in the same Robolectric sandbox can do), the
 * install is skipped with a log line instead of Coil's `setSafe` IllegalStateException.
 *
 * Coil's singleton and the Raft install flag are sandbox-global (shared by every test class in the
 * same JVM fork), so each test starts from a clean state and [restoreRaftLoader] leaves the Raft
 * loader installed, never Coil's default loader that screenshot tests would otherwise draw with.
 */
@OptIn(DelicateCoilApi::class)
@RunWith(RobolectricTestRunner::class)
class RaftImageLoaderInstallTest {
    @BeforeTest
    fun startClean() {
        SingletonImageLoader.reset()
        resetRaftImageLoaderForTest()
    }

    @AfterTest
    fun restoreRaftLoader() {
        SingletonImageLoader.reset()
        resetRaftImageLoaderForTest()
        raftInstallImageLoader()
    }

    @Test
    fun installAfterCoilCreatedItsDefaultSingletonDoesNotThrow() {
        val context = RuntimeEnvironment.getApplication()
        val coilDefault = SingletonImageLoader.get(context)

        raftInstallImageLoader()
        // Second call: already handled, returns early.
        raftInstallImageLoader()

        assertSame(coilDefault, SingletonImageLoader.get(context), "the existing singleton is left in place")
        assertNull(raftCurrentImageLoader(), "the Raft loader was never built")
    }

    @Test
    fun installBeforeTheFirstGetMakesTheRaftLoaderTheSingleton() {
        val context = RuntimeEnvironment.getApplication()

        raftInstallImageLoader()
        val singleton = SingletonImageLoader.get(context)

        assertSame(raftCurrentImageLoader(), singleton)
    }
}

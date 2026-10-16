package build.raft.app.shared

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import com.tencent.kuikly.core.render.android.adapter.KuiklyRenderAdapterManager
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exercises the registered production router on Android, with only the final
 * Activity launch/finish effects recorded. No Kuikly page, login session or
 * network fixture is loaded. These cases prove launch-request admission, not
 * real Android task containment, Activity lifecycle delivery or animations.
 */
class AndroidLoginTransitionRoutingConnectedTest {
    private val sources = mutableListOf<RecordingActivity>()

    @Test
    fun accountAndShellRequestsInOneTaskShareThePendingLoginLaunch() = onMain {
        val taskId = nextTaskId.getAndIncrement()
        val account = source(taskId, SlockKuiklyActivity.PAGE_SETTINGS_ACCOUNT)
        val shell = source(taskId, SlockKuiklyActivity.PAGE_APP_SHELL)

        replaceWithLogin(account)
        assertTrue("The first replacement must finish its source", account.isFinishing)
        // No destination Activity has been created: this is the pending-launch
        // interleaving that an active-login-only registry cannot coalesce.
        replaceWithLogin(shell)
        replaceWithLogin(account)
        replaceWithLogin(shell)

        assertEquals(1, account.launches.size)
        assertEquals(0, shell.launches.size)
        assertLoginLaunch(account.launches.single())
    }

    @Test
    fun alreadyFinishingSourceCannotStartALateLoginReplacement() = onMain {
        val account = source(nextTaskId.getAndIncrement(), SlockKuiklyActivity.PAGE_SETTINGS_ACCOUNT)
        account.finish()

        replaceWithLogin(account)
        replaceWithLogin(account)

        assertTrue(account.isFinishing)
        assertTrue("A stale source must not reach startActivity", account.launches.isEmpty())
        assertEquals(0, account.startAttempts)
    }

    @Test
    fun separateTaskIdsKeepIndependentPendingLoginLaunches() = onMain {
        val firstAccount = source(nextTaskId.getAndIncrement(), SlockKuiklyActivity.PAGE_SETTINGS_ACCOUNT)
        val secondAccount = source(nextTaskId.getAndIncrement(), SlockKuiklyActivity.PAGE_SETTINGS_ACCOUNT)

        replaceWithLogin(firstAccount)
        replaceWithLogin(secondAccount)
        replaceWithLogin(firstAccount)
        replaceWithLogin(secondAccount)

        assertEquals(1, firstAccount.launches.size)
        assertEquals(1, secondAccount.launches.size)
        assertLoginLaunch(firstAccount.launches.single())
        assertLoginLaunch(secondAccount.launches.single())
    }

    @Test
    fun failedStartDoesNotFinishSourceAndCanBeRetried() = onMain {
        val account = source(nextTaskId.getAndIncrement(), SlockKuiklyActivity.PAGE_SETTINGS_ACCOUNT)
        account.failNextStart = true

        val failure = runCatching { replaceWithLogin(account) }.exceptionOrNull()

        assertTrue("The launch failure must remain observable", failure is ActivityNotFoundException)
        assertFalse("A failed launch must preserve its source", account.isFinishing)
        assertEquals(0, account.finishRequests)
        assertTrue(account.launches.isEmpty())
        replaceWithLogin(account)
        assertEquals(2, account.startAttempts)
        assertEquals(1, account.launches.size)
        assertTrue(account.isFinishing)
        assertLoginLaunch(account.launches.single())
    }

    @Test
    fun successfulLoginToChooserAllowsANewLogoutCycleInTheSameTask() = onMain {
        val taskId = nextTaskId.getAndIncrement()
        val firstAccount = source(taskId, SlockKuiklyActivity.PAGE_SETTINGS_ACCOUNT)
        replaceWithLogin(firstAccount)
        val firstIntent = firstAccount.launches.single()
        val firstToken = requireNotNull(firstIntent.getStringExtra(AndroidLoginTransitionRouting.KEY_LOGIN_TRANSITION_TOKEN))
        val login = source(taskId, SlockKuiklyActivity.PAGE_LOGIN).apply { intent = Intent(firstIntent) }
        assertEquals(firstToken, AndroidLoginTransitionRouting.bindLogin(login, firstToken))

        requireNotNull(KuiklyRenderAdapterManager.krRouterAdapter).openPage(
            login,
            SlockKuiklyActivity.PAGE_AUTH_CHOOSE_SERVER,
            JSONObject().put("slockReplaceCurrentPage", true),
        )
        assertEquals(1, login.launches.size)
        assertEquals(
            SlockKuiklyActivity.PAGE_AUTH_CHOOSE_SERVER,
            login.launches.single().getStringExtra(SlockKuiklyActivity.KEY_PAGE_NAME),
        )
        assertTrue(login.isFinishing)

        val nextAccount = source(taskId, SlockKuiklyActivity.PAGE_SETTINGS_ACCOUNT)
        replaceWithLogin(nextAccount)
        assertEquals(1, nextAccount.launches.size)
        assertLoginLaunch(nextAccount.launches.single())
        assertFalse(
            "A new authentication cycle must receive a new native transition ticket",
            firstToken == nextAccount.launches.single().getStringExtra(AndroidLoginTransitionRouting.KEY_LOGIN_TRANSITION_TOKEN),
        )
        replaceWithLogin(login)
        replaceWithLogin(firstAccount)
        assertEquals("Old login completion cannot launch again", 1, login.launches.size)
        assertEquals("Old Account completion cannot launch again", 1, firstAccount.launches.size)
    }

    @Test
    fun accountCreatedBeforeLogoutCannotRestartLoginAfterAuthenticationCompletes() = onMain {
        val taskId = nextTaskId.getAndIncrement()
        val shell = source(taskId, SlockKuiklyActivity.PAGE_APP_SHELL)
        val account = source(taskId, SlockKuiklyActivity.PAGE_SETTINGS_ACCOUNT)
        // Account is a real registered host but has emitted no router request.
        replaceWithLogin(shell)
        assertFalse(account.isFinishing)
        val firstIntent = shell.launches.single()
        val firstToken = requireNotNull(firstIntent.getStringExtra(AndroidLoginTransitionRouting.KEY_LOGIN_TRANSITION_TOKEN))
        val login = source(taskId, SlockKuiklyActivity.PAGE_LOGIN).apply { intent = Intent(firstIntent) }
        assertEquals(firstToken, AndroidLoginTransitionRouting.bindLogin(login, firstToken))
        requireNotNull(KuiklyRenderAdapterManager.krRouterAdapter).openPage(
            login,
            SlockKuiklyActivity.PAGE_AUTH_CHOOSE_SERVER,
            JSONObject().put("slockReplaceCurrentPage", true),
        )
        assertEquals(1, login.launches.size)

        // This completion belongs to the pre-authentication host generation,
        // even though no request had previously retired Account individually.
        replaceWithLogin(account)
        assertTrue("A stale registered host may only finish", account.isFinishing)
        assertEquals("Old Account must not start another login after auth succeeds", 0, account.startAttempts)
        assertTrue(account.launches.isEmpty())
        assertEquals(1, shell.launches.size)
        assertEquals(1, login.launches.size)
    }

    @Test
    fun retiredIntentRemainsRetiredOnRecreationButAFreshHostCanStartAgain() = onMain {
        val taskId = nextTaskId.getAndIncrement()
        val oldAccount = source(taskId, SlockKuiklyActivity.PAGE_SETTINGS_ACCOUNT)
        replaceWithLogin(oldAccount)
        val loginIntent = oldAccount.launches.single()
        val token = requireNotNull(loginIntent.getStringExtra(AndroidLoginTransitionRouting.KEY_LOGIN_TRANSITION_TOKEN))

        val recreatedAccount = RecordingActivity(InstrumentationRegistry.getInstrumentation().targetContext, taskId).apply {
            intent = Intent(oldAccount.intent)
            sources += this
        }
        assertFalse(
            "Recreating the retired route Intent must not revive its source",
            AndroidLoginTransitionRouting.observeHost(recreatedAccount),
        )
        replaceWithLogin(recreatedAccount)
        assertTrue(recreatedAccount.isFinishing)
        assertEquals(0, recreatedAccount.startAttempts)

        val login = source(taskId, SlockKuiklyActivity.PAGE_LOGIN).apply { intent = Intent(loginIntent) }
        assertEquals(token, AndroidLoginTransitionRouting.bindLogin(login, token))
        login.finish()
        AndroidLoginTransitionRouting.onDestroyed(login, token, changingConfigurations = false)

        val freshAccount = source(taskId, SlockKuiklyActivity.PAGE_SETTINGS_ACCOUNT)
        assertTrue(
            "A fresh route Intent in the same task must not inherit retirement",
            AndroidLoginTransitionRouting.observeHost(freshAccount),
        )
        replaceWithLogin(freshAccount)
        assertEquals(1, freshAccount.launches.size)
        assertLoginLaunch(freshAccount.launches.single())
    }

    @Test
    fun oldAuthenticationAttemptCannotTakeOverTheSameActivityAfterANewIntent() = onMain {
        val taskId = nextTaskId.getAndIncrement()
        val login = source(taskId, SlockKuiklyActivity.PAGE_LOGIN)
        val token = requireNotNull(AndroidLoginTransitionRouting.bindLogin(login, requestedToken = null))
        val oldAttempt = AndroidLoginTransitionRouting.captureAuthentication(login)
        try {
            login.intent = Intent().putExtra(SlockKuiklyActivity.KEY_PAGE_NAME, SlockKuiklyActivity.PAGE_LOGIN)
            AndroidLoginTransitionRouting.observeNewIntent(login)
            val oldAccepted = AndroidLoginTransitionRouting.afterSuccessfulAuthentication(login, oldAttempt) {
                error("An old authentication completion must not launch after a new Intent owns the Activity")
            }
            assertFalse("Callers need a rejected result so they do not finish the new owner", oldAccepted)
            assertFalse(login.isFinishing)
            assertEquals(0, login.startAttempts)

            val newAccepted = AndroidLoginTransitionRouting.afterSuccessfulAuthentication(login) {
                SlockKuiklyActivity.startAuthChooseServer(login)
            }
            assertTrue("The current Intent's authentication remains usable", newAccepted)
            assertEquals(1, login.launches.size)
            assertEquals(
                SlockKuiklyActivity.PAGE_AUTH_CHOOSE_SERVER,
                login.launches.single().getStringExtra(SlockKuiklyActivity.KEY_PAGE_NAME),
            )
        } finally {
            // If an assertion fails before completion, release this case's
            // manually bound login through the normal ownership API as well.
            val cleanupLogin = RecordingActivity(InstrumentationRegistry.getInstrumentation().targetContext, taskId)
            AndroidLoginTransitionRouting.bindLogin(cleanupLogin, token)?.let { bound ->
                AndroidLoginTransitionRouting.onDestroyed(cleanupLogin, bound, changingConfigurations = false)
            }
        }
    }

    private fun onMain(block: () -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            SlockKuiklyAdapters.init(instrumentation.targetContext.applicationContext)
            checkNotNull(KuiklyRenderAdapterManager.krRouterAdapter) {
                "Production adapter initialization did not register its router"
            }
            try {
                block()
            } finally {
                // Release only this case's opaque, unique task tickets through
                // the same ownership methods used by login host lifecycle.
                // No production singleton reset or test-only clear API exists.
                sources.forEach { source ->
                    source.launches.forEach launch@{ intent ->
                        val token = intent.getStringExtra(AndroidLoginTransitionRouting.KEY_LOGIN_TRANSITION_TOKEN)
                            ?: return@launch
                        val login = RecordingActivity(instrumentation.targetContext, source.taskId)
                        val boundToken = AndroidLoginTransitionRouting.bindLogin(login, token)
                        if (boundToken != null) {
                            AndroidLoginTransitionRouting.onDestroyed(login, boundToken, changingConfigurations = false)
                        }
                    }
                }
                sources.clear()
            }
        }
    }

    private fun source(taskId: Int, pageName: String): RecordingActivity =
        RecordingActivity(InstrumentationRegistry.getInstrumentation().targetContext, taskId).apply {
            intent = Intent().putExtra(SlockKuiklyActivity.KEY_PAGE_NAME, pageName)
            AndroidLoginTransitionRouting.observeHost(this)
            sources += this
        }

    private fun replaceWithLogin(source: Activity) {
        requireNotNull(KuiklyRenderAdapterManager.krRouterAdapter).openPage(
            source,
            SlockKuiklyActivity.PAGE_LOGIN,
            JSONObject().put("slockReplaceCurrentPage", true),
        )
    }

    private fun assertLoginLaunch(intent: Intent) {
        assertEquals(SlockKuiklyActivity::class.java.name, intent.component?.className)
        assertEquals(SlockKuiklyActivity.PAGE_LOGIN, intent.getStringExtra(SlockKuiklyActivity.KEY_PAGE_NAME))
        assertTrue(
            "The accepted launch must carry its native ownership ticket",
            !intent.getStringExtra(AndroidLoginTransitionRouting.KEY_LOGIN_TRANSITION_TOKEN).isNullOrBlank(),
        )
        assertEquals(
            "A replacement from an Activity must preserve that task",
            0,
            intent.flags and (Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
        )
    }

    private class RecordingActivity(
        private val targetContext: Context,
        private val recordedTaskId: Int,
    ) : Activity() {
        val launches = mutableListOf<Intent>()
        var startAttempts = 0
            private set
        var finishRequests = 0
            private set
        var failNextStart = false
        private var finishing = false

        init {
            attachBaseContext(targetContext)
        }

        override fun getApplicationContext(): Context = targetContext.applicationContext
        override fun getTaskId(): Int = recordedTaskId
        override fun isFinishing(): Boolean = finishing

        override fun startActivity(intent: Intent) {
            startAttempts += 1
            if (failNextStart) {
                failNextStart = false
                throw ActivityNotFoundException("Deliberate launch failure for rollback coverage")
            }
            launches += Intent(intent)
        }

        override fun finish() {
            finishRequests += 1
            finishing = true
        }
    }

    private companion object {
        // Never reset the production coordinator or collide with real device
        // tasks / another test invocation in this instrumentation process.
        val nextTaskId = AtomicInteger(2_050_000)
    }
}

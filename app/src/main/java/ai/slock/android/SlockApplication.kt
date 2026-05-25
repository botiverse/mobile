package ai.slock.android

import android.app.Application
import ai.slock.android.core.data.diagnostics.SlockCrashReporter

class SlockApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        SlockCrashReporter.install(this)
    }
}

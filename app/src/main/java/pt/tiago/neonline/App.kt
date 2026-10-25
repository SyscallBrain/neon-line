package pt.tiago.neonline

import android.app.Application
import pt.tiago.neonline.crash.CrashLog

class App : Application() {
    /** One container per process: the catalog and the profile are loaded once and shared by every screen. */
    val container: AppContainer by lazy { AppContainer(this) }

    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
    }
}

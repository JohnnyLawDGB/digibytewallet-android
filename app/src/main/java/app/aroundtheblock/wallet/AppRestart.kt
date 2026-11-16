package app.aroundtheblock.wallet

import android.content.Context

/**
 * The one restart path outside the wipe hand-over: the counter is reset durably (the process is
 * about to end and an apply() could be lost), then the shared main-thread relaunch-then-kill
 * routine runs with no hand-over extras.
 */
fun interface AppRestart {
    fun restart(context: Context)

    companion object : AppRestart {
        override fun restart(context: Context) {
            val app = context.applicationContext ?: context
            BootGuard.markBootHealthy(app, sync = true)
            RelaunchInFreshProcess.startFreshWithoutHandOver(app)
        }
    }
}

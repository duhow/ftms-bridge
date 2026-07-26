package net.duhowpi.ftmsbridge

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class FtmsBridgeApp : Application() {
    /**
     * Process-lifetime IO scope for session DB writes. Unlike an activity's lifecycleScope,
     * it is not cancelled when the activity is destroyed (e.g. task swiped from recents),
     * so samples keep being persisted while the foreground RecordingService keeps the
     * process alive.
     */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
}

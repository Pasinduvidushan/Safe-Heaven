package lk.safeheaven

import android.app.Activity
import android.app.Application
import android.os.Bundle
import lk.safeheaven.data.Prefs
import lk.safeheaven.mesh.MeshScanner
import lk.safeheaven.trip.Notifications
import lk.safeheaven.trip.ReplayController
import lk.safeheaven.trip.TripBus

class SafeHeavenApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Notifications.createChannels(this)
        ReplayController.load(this)
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) { TripBus.startedActivities++ }
            override fun onActivityStopped(activity: Activity) { TripBus.startedActivities-- }
            override fun onActivityCreated(a: Activity, s: Bundle?) {}
            override fun onActivityResumed(a: Activity) {}
            override fun onActivityPaused(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, o: Bundle) {}
            override fun onActivityDestroyed(a: Activity) {}
        })
        // Every installed instance is a relay for strangers' packets (hardware-filtered, screen-off safe).
        if (Prefs.get(this).onboarded) MeshScanner(this).start()
    }
}

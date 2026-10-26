package lk.safeheaven.trip

import android.content.Context
import android.net.Uri

/** Debug "Route Replay" for stage demos: injects a recorded GPX track into [LocationHub]. */
object ReplayController {
    enum class Scenario(val asset: String?, val label: String) {
        CLEAN("demo_route.gpx", "Clean drive (arrives)"),
        DEVIATION("demo_deviation.gpx", "Veers off route"),
        STATIONARY("demo_stationary.gpx", "Stops mid-route 6 min"),
        CUSTOM(null, "Custom GPX file"),
    }

    @Volatile var enabled = false
    @Volatile var scenario = Scenario.DEVIATION
    @Volatile var speed = 5.0
    @Volatile private var planned: List<GpxPoint> = emptyList()
    @Volatile private var custom: List<GpxPoint> = emptyList()
    @Volatile private var scenarioTracks: Map<Scenario, List<GpxPoint>> = emptyMap()

    fun load(context: Context) {
        if (planned.isNotEmpty()) return
        val am = context.assets
        planned = am.open("demo_route.gpx").use(Gpx::parse)
        scenarioTracks = Scenario.entries.filter { it.asset != null }.associateWith { s ->
            am.open(s.asset!!).use(Gpx::parse)
        }
    }

    fun loadCustom(context: Context, uri: Uri): Int {
        custom = context.contentResolver.openInputStream(uri)!!.use(Gpx::parse)
        scenario = Scenario.CUSTOM
        return custom.size
    }

    /** The route the trip is checked against in replay mode (the planned demo route). */
    fun plannedRoute(): List<GpxPoint> = planned

    fun driveTrack(): List<GpxPoint> =
        (if (scenario == Scenario.CUSTOM) custom else scenarioTracks[scenario]).orEmpty().ifEmpty { planned }

    fun plannedEtaSeconds(): Long = (planned.lastOrNull()?.offsetMs ?: 600_000L) / 1000
}

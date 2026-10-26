package lk.safeheaven.mesh

/** Remembers (sender_id, counter) pairs for 10 minutes to break relay loops. */
object SeenCache {
    private const val TTL_MS = 10 * 60_000L
    private val seen = HashMap<Long, Long>()

    @Synchronized
    fun firstTime(senderId: Long, counter: Int, nowMs: Long = System.currentTimeMillis()): Boolean {
        val key = (senderId shl 24) or (counter.toLong() and 0xFFFFFF)
        val it = seen.entries.iterator()
        while (it.hasNext()) if (nowMs - it.next().value > TTL_MS) it.remove()
        if (seen.containsKey(key)) return false
        seen[key] = nowMs
        return true
    }
}

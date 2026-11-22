package pro.vinnikov.kawavpn.domain

object TunnelHealthChecks {
    private val endpoints = listOf(
        "https://www.gstatic.com/generate_204",
        "https://cp.cloudflare.com/generate_204"
    )
    fun verify(probe: (String) -> Boolean, stillCurrent: () -> Boolean = { true }): Boolean {
        for (url in endpoints) {
            if (!stillCurrent()) return false
            if (probe(url)) return stillCurrent()
        }
        return false
    }
}

/** Automatic checks only. A manual latency measurement never consumes this budget. */
class TunnelHealthPolicy(private val graceMillis: Long = 60_000) {
    private var failures = 0
    private var observedChange: Long? = null
    fun record(success: Boolean, now: Long, networkChangedAt: Long?): Boolean {
        if (networkChangedAt != observedChange) {
            failures = 0
            observedChange = networkChangedAt
        }
        if (success || (networkChangedAt != null && now >= networkChangedAt && now - networkChangedAt < graceMillis)) {
            failures = 0
            return false
        }
        return ++failures >= 2
    }
}

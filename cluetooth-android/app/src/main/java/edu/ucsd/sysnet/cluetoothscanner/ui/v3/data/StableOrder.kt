package edu.ucsd.sysnet.cluetoothscanner.ui.v3.data

/**
 * Keeps lists from re-ordering themselves while they are on screen, the way the Scan list does: after
 * [reset] the lists come in their own order (strongest first) for [SETTLE_MS], then that order is held.
 * An item keeps its place whatever its signal does, a new one goes at the end of its list, and one that
 * is gone leaves. Thread safe.
 */
class StableOrder(private val settleMs: Long = SETTLE_MS) {
    private val orders = HashMap<String, List<String>>()
    private var settleUntilMs = Long.MIN_VALUE
    private val listSettleUntilMs = HashMap<String, Long>()

    /** Every list (for [settleMs] from [nowMs]) sets its order again: a page opened, a mode changed. */
    @Synchronized
    fun reset(nowMs: Long) {
        orders.clear()
        listSettleUntilMs.clear()
        settleUntilMs = nowMs + settleMs
    }

    /** Only list [key] sets its order again: the user picked another sort, or opened that list anew. */
    @Synchronized
    fun reset(key: String, nowMs: Long) {
        orders.remove(key)
        listSettleUntilMs[key] = nowMs + settleMs
    }

    /** [items] in the held order of list [key]. */
    @Synchronized
    fun <T> arrange(key: String, items: List<T>, nowMs: Long, id: (T) -> String): List<T> {
        val byId = LinkedHashMap<String, T>()
        items.forEach { byId.putIfAbsent(id(it), it) }
        val order = if (nowMs < maxOf(settleUntilMs, listSettleUntilMs[key] ?: Long.MIN_VALUE)) {
            byId.keys.toList()
        } else {
            val kept = orders[key].orEmpty().filter { it in byId }
            val keptSet = kept.toHashSet()
            kept + byId.keys.filter { it !in keptSet }
        }
        orders[key] = order
        return order.map { byId.getValue(it) }
    }

    companion object {
        /** As the Scan list's first sort: 3 s after the page opens. */
        const val SETTLE_MS = 3_000L
    }
}

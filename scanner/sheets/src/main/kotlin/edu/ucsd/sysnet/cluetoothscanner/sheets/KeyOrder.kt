package edu.ucsd.sysnet.cluetoothscanner.sheets

import edu.ucsd.sysnet.cluetoothscanner.api.core.DeviceKeys

/**
 * G-9: an immutable, append-only order of device keys, for immutable UI state. Every value is a fixed prefix of a
 * shared buffer: [append] on the newest value writes into the buffer without copying the keys before it, and returns
 * a new value; the old value still sees only its own prefix. Appending to an older value copies its prefix first, so
 * no value ever changes. Keys already in the order are skipped (dedup).
 *
 * Values belong to one owner (the UI state holder); they are not shared across threads.
 */
class KeyOrder private constructor(private val store: Store, val size: Int) {
    private class Store(var keys: LongArray, var used: Int, val position: HashMap<Long, Int>)

    /** True when [key] is in this order (in this value's prefix). */
    operator fun contains(key: Long): Boolean = (store.position[key] ?: Int.MAX_VALUE) < size

    /** This order with [more] appended, skipping keys already in it. */
    fun append(more: LongArray): KeyOrder {
        if (more.isEmpty()) return this
        val target = if (store.used == size) store else copyPrefix()
        var n = size
        for (k in more) {
            if ((target.position[k] ?: Int.MAX_VALUE) < n) continue
            if (n == target.keys.size) target.keys = target.keys.copyOf(maxOf(16, n * 2))
            target.keys[n] = k
            target.position[k] = n
            n++
        }
        target.used = n
        return if (n == size) this else KeyOrder(target, n)
    }

    /** The order ∩ [passing], in this order (never [passing]'s). */
    fun visible(passing: DeviceKeys): LongArray {
        val pass = passing.keys.copyOf().also { it.sort() }
        val out = LongArray(minOf(size, pass.size))
        var n = 0
        for (i in 0 until size) {
            val k = store.keys[i]
            if (pass.binarySearch(k) >= 0) out[n++] = k
        }
        return out.copyOf(n)
    }

    fun toLongArray(): LongArray = store.keys.copyOf(size)

    private fun copyPrefix(): Store {
        val position = HashMap<Long, Int>(size * 2)
        for (i in 0 until size) position[store.keys[i]] = i
        return Store(store.keys.copyOf(maxOf(16, size * 2)), size, position)
    }

    companion object {
        val Empty: KeyOrder get() = KeyOrder(Store(LongArray(16), 0, HashMap()), 0)
        fun of(keys: LongArray): KeyOrder = Empty.append(keys)
    }
}

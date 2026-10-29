package edu.ucsd.sysnet.cluetoothscanner.sheets

import edu.ucsd.sysnet.cluetoothscanner.api.core.DeviceKeys

/**
 * G-9: an immutable, append-only order of device keys, for immutable UI state. Every value is a fixed prefix of a
 * shared buffer: [append] on the newest value writes into the buffer without copying the keys before it, and returns
 * a new value; the old value still sees only its own prefix. Appending to an older value copies its prefix first, so
 * no value ever changes. Keys already in the order are skipped (dedup).
 *
 * Memory: the keys (8 bytes each) plus an open-addressing index of positions (an `IntArray`, 4 bytes a slot, at most
 * 3/4 full), with no boxing: about 9 MB at 600 k keys, where a `HashMap<Long, Int>` index alone takes 35-40 MB.
 *
 * Values belong to one owner (the UI state holder); they are not shared across threads.
 */
class KeyOrder private constructor(private val store: Store, val size: Int) {
    /** The shared buffer: keys in order, and [slots] (position + 1 per key, 0 = empty) hashed by key. */
    private class Store(var keys: LongArray, var used: Int, var slots: IntArray) {
        /** The position of [key] in [keys], or -1. */
        fun positionOf(key: Long): Int {
            val mask = slots.size - 1
            var i = mix(key) and mask
            while (true) {
                val p = slots[i]
                if (p == 0) return -1
                if (keys[p - 1] == key) return p - 1
                i = (i + 1) and mask
            }
        }

        /** Records [key] at [position] (keys before it are indexed); grows the table to stay at most 3/4 full. */
        fun index(key: Long, position: Int) {
            if ((position + 1) * 4L > slots.size * 3L) rehash(slots.size * 2, position)
            val mask = slots.size - 1
            var i = mix(key) and mask
            while (slots[i] != 0) i = (i + 1) and mask
            slots[i] = position + 1
        }

        private fun rehash(capacity: Int, upTo: Int) {
            slots = IntArray(capacity)
            val mask = capacity - 1
            for (p in 0 until upTo) {
                var i = mix(keys[p]) and mask
                while (slots[i] != 0) i = (i + 1) and mask
                slots[i] = p + 1
            }
        }

        /** Bytes held by the buffer and the index. */
        fun footprint(): Long = keys.size * 8L + slots.size * 4L
    }

    /** True when [key] is in this order (in this value's prefix). */
    operator fun contains(key: Long): Boolean {
        val p = store.positionOf(key)
        return p in 0 until size
    }

    /** This order with [more] appended, skipping keys already in it. */
    fun append(more: LongArray): KeyOrder {
        if (more.isEmpty()) return this
        val target = if (store.used == size) store else copyPrefix()
        var n = size
        for (k in more) {
            val p = target.positionOf(k)
            if (p in 0 until n) continue
            if (n == target.keys.size) target.keys = target.keys.copyOf(maxOf(16, n * 2))
            target.keys[n] = k
            target.index(k, n)
            target.used = n + 1
            n++
        }
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

    /** Bytes of the buffer and index behind this value (shared with the values it shares them with). */
    internal fun footprintBytes(): Long = store.footprint()

    private fun copyPrefix(): Store {
        val s = Store(store.keys.copyOf(maxOf(16, size * 2)), 0, IntArray(capacityFor(size)))
        for (p in 0 until size) {
            s.index(s.keys[p], p)
            s.used = p + 1
        }
        return s
    }

    companion object {
        val Empty: KeyOrder get() = KeyOrder(Store(LongArray(16), 0, IntArray(32)), 0)

        fun of(keys: LongArray): KeyOrder {
            val s = Store(LongArray(maxOf(16, keys.size)), 0, IntArray(capacityFor(keys.size)))
            return KeyOrder(s, 0).append(keys)
        }

        /** The smallest power of two that holds [n] keys at most 3/4 full. */
        private fun capacityFor(n: Int): Int {
            val need = maxOf(16L, (n * 4L + 2) / 3)
            return Integer.highestOneBit((need - 1).toInt()) shl 1
        }

        /** A 64-bit mix (MurmurHash3 finalizer) folded to an int. */
        private fun mix(key: Long): Int {
            var h = key
            h = (h xor (h ushr 33)) * -0xae502812aa7333L
            h = (h xor (h ushr 33)) * -0x3b314601e57a13adL
            h = h xor (h ushr 33)
            return h.toInt() and Int.MAX_VALUE
        }
    }
}

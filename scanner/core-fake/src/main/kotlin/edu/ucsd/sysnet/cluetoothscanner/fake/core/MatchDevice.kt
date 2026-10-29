package edu.ucsd.sysnet.cluetoothscanner.fake.core

/** SPEC Q-1: `data.js` `matchDevice`, mirrored exactly (the Rust core runs the same \u00A72.11 vectors). */
object MatchDevice {
    /** JS `String.prototype.trim`: WhiteSpace and LineTerminator code points. */
    private fun isJsSpace(c: Char): Boolean = when (c) {
        '\t', '\n', '\u000B', '\u000C', '\r', ' ', '\u00A0', '\u1680', '\u2028', '\u2029', '\u202F', '\u205F',
        '\u3000', '\uFEFF' -> true
        else -> c in '\u2000'..'\u200A'
    }

    private fun strip(text: String) = text.filter { it != ':' && it != '-' }.lowercase()

    /** A blank query is no search (Q-1). */
    fun isBlank(query: String): Boolean = query.trim(::isJsSpace).isEmpty()

    fun matches(query: String, name: String?, addr: String): Boolean {
        val q = query.trim(::isJsSpace)
        if (q.isEmpty()) return false
        if (name != null && name.lowercase().contains(q.lowercase())) return true
        if (q.none { it in '0'..'9' }) return false
        return strip(addr).contains(strip(q))
    }
}

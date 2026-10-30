package edu.ucsd.sysnet.cluetoothscanner.sheets

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** D-15: a single-token value's first selection is the whole token; the handles can narrow it after that. */
class TokenSelectionTest {
    private val mac = "A4:C1:38:9F:0B:2E"

    @Test
    fun `tokens are values without whitespace`() {
        for (token in listOf(mac, "0000fe2c-0000-1000-8000-00805f9b34fb", "4c00100501182a7b", "17", "0xff", "Yes")) assertTrue(token, isToken(token))
        for (text in listOf("", "AirPods Pro", "−56 dBm", "800 ms", "2A19 · read")) assertFalse(text, isToken(text))
    }

    @Test
    fun `a long press picks one word of the token - the selection becomes all of it`() {
        val idle = TextFieldValue(mac)
        val word = idle.copy(selection = TextRange(6, 9)) // "38:"
        assertEquals(TextRange(0, mac.length), wholeToken(idle, word).selection)
    }

    @Test
    fun `after that the handles narrow it, and a tap clears it`() {
        val all = TextFieldValue(mac, TextRange(0, mac.length))
        val narrowed = all.copy(selection = TextRange(3, mac.length))
        assertSame(narrowed, wholeToken(all, narrowed))
        val cleared = all.copy(selection = TextRange(4))
        assertSame(cleared, wholeToken(all, cleared))
        val caret = TextFieldValue(mac, TextRange(2))
        assertSame(caret, wholeToken(TextFieldValue(mac), caret))
    }
}

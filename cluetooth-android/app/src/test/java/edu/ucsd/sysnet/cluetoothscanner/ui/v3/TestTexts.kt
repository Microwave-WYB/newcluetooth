package edu.ucsd.sysnet.cluetoothscanner.ui.v3

import edu.ucsd.sysnet.cluetoothscanner.R
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.data.Texts
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/**
 * [Texts] over the real `res/values/strings.xml`, so tests assert the English copy and a missing
 * or misspelled resource fails them. Resource ids are mapped back to names through the generated R class.
 */
object TestTexts : Texts {
    private val names: Map<Int, String> = buildMap {
        for (clazz in listOf(R.string::class.java, R.plurals::class.java)) {
            clazz.fields.forEach { put(it.getInt(null), it.name) }
        }
    }
    private val strings = HashMap<String, String>()
    private val plurals = HashMap<String, Map<String, String>>()

    init {
        val file = File("src/main/res/values/strings.xml")
        val root = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).documentElement
        val nodes = root.childNodes
        for (i in 0 until nodes.length) {
            val e = nodes.item(i) as? Element ?: continue
            when (e.tagName) {
                "string" -> strings[e.getAttribute("name")] = unescape(e.textContent)
                "plurals" -> {
                    val items = e.getElementsByTagName("item")
                    plurals[e.getAttribute("name")] = (0 until items.length).associate {
                        val item = items.item(it) as Element
                        item.getAttribute("quantity") to unescape(item.textContent)
                    }
                }
            }
        }
    }

    private fun unescape(s: String) = s.replace("\\'", "'").replace("\\\"", "\"").replace("\\n", "\n").replace("\\@", "@")

    private fun name(id: Int) = names[id] ?: error("no resource with id $id")

    override fun string(id: Int, vararg args: Any): String =
        String.format(Locale.US, strings[name(id)] ?: error("missing string ${name(id)}"), *args)

    override fun plural(id: Int, quantity: Int, vararg args: Any): String {
        val forms = plurals[name(id)] ?: error("missing plurals ${name(id)}")
        val form = forms[if (quantity == 1) "one" else "other"] ?: forms.getValue("other")
        return String.format(Locale.US, form, *args)
    }

    override fun monthDay(date: LocalDate): String = date.format(DateTimeFormatter.ofPattern("MMM d", Locale.US))

    override fun fullDate(date: LocalDate): String = date.format(DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US))

    override fun timeOfDay(time: LocalDateTime): String = time.format(DateTimeFormatter.ofPattern("h:mm a", Locale.US))
}

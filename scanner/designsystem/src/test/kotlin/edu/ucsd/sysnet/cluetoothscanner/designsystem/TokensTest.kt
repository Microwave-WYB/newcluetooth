package edu.ucsd.sysnet.cluetoothscanner.designsystem

import androidx.compose.ui.graphics.Color
import edu.ucsd.sysnet.cluetoothscanner.designsystem.tokens.ColorTokens
import edu.ucsd.sysnet.cluetoothscanner.designsystem.tokens.CssEnv
import edu.ucsd.sysnet.cluetoothscanner.designsystem.tokens.Glyphs
import edu.ucsd.sysnet.cluetoothscanner.designsystem.tokens.ShadowTokens
import edu.ucsd.sysnet.cluetoothscanner.designsystem.tokens.TokenShadow
import edu.ucsd.sysnet.cluetoothscanner.designsystem.tokens.Tokens
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** G-1: every tokens.json entry is generated with its value, and no component hard-codes a value. */
class TokensTest {
    private val designDir = File(System.getProperty("cluetooth.designDir")!!)
    private val moduleDir = File(System.getProperty("cluetooth.moduleDir")!!)
    private val tokens: JsonObject =
        Json.parseToJsonElement(designDir.resolve("tokens.json").readText()).jsonObject["tokens"]!!.jsonObject

    private fun argb(hex: String) = Color(hex.removePrefix("#").toLong(16))
    private fun px(v: String) = v.removeSuffix("px").toFloat()
    /** Compose value classes come back unboxed through reflection: Color as its packed Long, Dp as Float. */
    private fun color(obj: Any, name: String) = Color((member(obj, name) as Long).toULong())
    private fun dp(obj: Any, name: String) = member(obj, name) as Float

    private fun member(obj: Any, name: String): Any? =
        obj.javaClass.methods.firstOrNull { it.name == "get" + name.replaceFirstChar(Char::uppercaseChar) && it.parameterCount == 0 }?.invoke(obj)
            ?: obj.javaClass.declaredFields.firstOrNull { it.name == name }?.apply { isAccessible = true }?.get(obj)

    @Test
    fun everyTokenIsGenerated() {
        assertEquals(tokens.keys, Tokens.generated.keys)
    }

    @Test
    fun generatedValuesEqualTokensJson() {
        for ((name, t) in tokens) {
            val o = t.jsonObject
            val k = Tokens.generated.getValue(name)
            val type = o["type"]!!.jsonPrimitive.content
            when (type) {
                "color" -> {
                    assertEquals(name, argb(o["lightArgb"]!!.jsonPrimitive.content), color(Tokens.lightColors, k))
                    assertEquals(name, argb(o["darkArgb"]!!.jsonPrimitive.content), color(Tokens.darkColors, k))
                }
                "shadow" -> for ((scheme, set) in listOf("light" to Tokens.lightShadows, "dark" to Tokens.darkShadows)) {
                    val sh = o["${scheme}Shadow"]!!.jsonObject
                    val got = member(set, k) as TokenShadow
                    assertEquals(name, px(sh["blur"]!!.jsonPrimitive.content), got.blur.value)
                    assertEquals(name, px(sh["y"]!!.jsonPrimitive.content), got.y.value)
                    assertEquals(name, px(sh["x"]!!.jsonPrimitive.content), got.x.value)
                    assertEquals(name, argb(sh["argb"]!!.jsonPrimitive.content), got.color)
                }
                "length" -> assertEquals(name, px(o["light"]!!.jsonPrimitive.content), dp(Tokens, k))
                "number" -> assertEquals(name, o["light"]!!.jsonPrimitive.content.toFloat(), member(Tokens, k) as Float)
                "duration" -> assertEquals(name, o["ms"]!!.jsonPrimitive.content.toDouble().toInt(), member(Tokens, k) as Int)
                "font" -> assertEquals(name, o["light"]!!.jsonPrimitive.content, member(Tokens, k))
                "easing" -> {
                    val b = o["bezier"]!!.jsonArray.map { it.jsonPrimitive.content.toFloat() }
                    // CubicBezierEasing has no accessors; compare transforms at a few points with an equal curve
                    val ref = androidx.compose.animation.core.CubicBezierEasing(b[0], b[1], b[2], b[3])
                    for (x in listOf(0.1f, 0.5f, 0.9f)) assertEquals(name, ref.transform(x), Tokens.ease.transform(x), 1e-6f)
                }
                "expression" -> {} // evaluated below
                else -> error("unknown token type $type")
            }
        }
    }

    @Test
    fun expressionTokens() {
        val e = CssEnv(containerWidth = 412f, containerHeight = 915f, parent = 915f, safeTop = 24f, safeBottom = 16f)
        assertEquals(412f * 0.82f, Tokens.sideW(e).value, 1e-3f)
        assertEquals(915f * 0.67f, Tokens.discoverSheetHalf(e).value, 1e-3f)
        assertEquals(915f - 68f, Tokens.discoverSheetFull(e).value, 1e-3f)
        assertEquals(24f + 12f, Tokens.fadeSolid(e).value, 1e-3f)
        assertEquals(16f, Tokens.safeBottom(e).value, 1e-3f)
        assertEquals(maxOf((915f - 68f - 12f) * 0.45f, 300f), Tokens.panelPartialH(e).value, 1e-3f)
        assertEquals(300f, Tokens.panelPartialH(CssEnv(containerHeight = 500f)).value, 1e-3f)
    }

    @Test
    fun colorAndShadowClassesHoldOnlyTokens() {
        val colorTokens = tokens.filterValues { it.jsonObject["type"]!!.jsonPrimitive.content == "color" }.size
        val shadowTokens = tokens.filterValues { it.jsonObject["type"]!!.jsonPrimitive.content == "shadow" }.size
        val own = { c: Class<*> -> c.declaredFields.count { !java.lang.reflect.Modifier.isStatic(it.modifiers) } }
        assertEquals(colorTokens, own(ColorTokens::class.java))
        assertEquals(shadowTokens, own(ShadowTokens::class.java))
    }

    @Test
    fun everyAppJsIconIsGenerated() {
        val block = Regex("""const ICONS = \{([\s\S]*?)\n\s*\};""").find(designDir.resolve("app.js").readText())!!.groupValues[1]
        val names = Regex("""^\s*(\w+):""", RegexOption.MULTILINE).findAll(block).map { it.groupValues[1] }.toList()
        assertEquals(names, Glyphs.fromAppJs)
        assertTrue(Glyphs.all.map { it.name }.containsAll(names))
    }

    /**
     * No hex color, dp, sp or em literal (`12.dp`, `Dp(4f)`) in hand-written main code of :designsystem and :chrome;
     * generated code, the gallery and SpecValues aside. Zero is allowed.
     */
    @Test
    fun noLiteralValuesOutsideGeneratedCode() {
        val sources = listOf(moduleDir, moduleDir.resolveSibling("chrome")).map { it.resolve("src/main/kotlin") }
        assertTrue(sources.all { it.isDirectory })
        val allowed = setOf("SpecValues.kt")
        val literal = Regex("""(?<![\w.])(?:\d+\.)?\d+\.(?:dp|sp|em)\b|\bDp\(\s*[\d.]+f?\s*\)|Color\(0x[0-9A-Fa-f]+|#[0-9A-Fa-f]{6,8}\b""")
        val zero = Regex("""^(?:0\.dp|Dp\(\s*0f?\s*\))$""")
        val offenders = sources.flatMap { it.walkTopDown().toList() }
            .filter { it.isFile && it.extension == "kt" && it.name !in allowed && "gallery" !in it.path }
            .flatMap { f ->
                f.readLines().withIndex()
                    .filter { (_, line) -> !line.trimStart().startsWith("*") && !line.trimStart().startsWith("//") }
                    .flatMap { (i, line) ->
                        literal.findAll(line.substringBefore("//")).map { it.value }.filterNot { zero.matches(it) }
                            .map { "${f.name}:${i + 1}: $it" }
                    }
            }.toList()
        assertTrue("literal values outside generated code:\n${offenders.joinToString("\n")}", offenders.isEmpty())
    }
}

import groovy.json.JsonSlurper

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// G-1: the theme is generated from the prototype at build time; nothing is copied by hand. The three sources are
// tokens.json (exported from tokens.css by design/ui-v2/export.mjs), components.css (component literals that resolve
// from tokens alone) and app.js ICONS (the stroke glyphs, C-17), plus this module's extra category glyphs.
val designDir = rootProject.layout.projectDirectory.dir("design/ui-v2")
val generatedDir = layout.buildDirectory.dir("generated/designsystem/kotlin")

val generateDesignSystem by tasks.registering(GenerateDesignSystem::class) {
    tokensJson.set(designDir.file("tokens.json"))
    componentsCss.set(designDir.file("components.css"))
    appJs.set(designDir.file("app.js"))
    extraIcons.set(layout.projectDirectory.file("icons/extra-icons.json"))
    outputDir.set(generatedDir)
}

android {
    namespace = "edu.ucsd.sysnet.cluetoothscanner.designsystem"
    compileSdk = 35

    defaultConfig {
        minSdk = 24
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions { jvmTarget = "11" }
    buildFeatures { compose = true }
    sourceSets["main"].kotlin.srcDir(generatedDir)
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all { test ->
                // The token, string and lint tests read the prototype sources and this module's own sources.
                test.systemProperty("cluetooth.designDir", designDir.asFile.absolutePath)
                test.systemProperty("cluetooth.moduleDir", layout.projectDirectory.asFile.absolutePath)
                test.systemProperty("cluetooth.generatedDir", generatedDir.get().asFile.absolutePath)
                // Roborazzi: -Pcluetooth.record=true rewrites the references under src/test/screenshots; the
                // default compares against them.
                val record = providers.gradleProperty("cluetooth.record").orNull == "true"
                test.systemProperty("roborazzi.test.record", record.toString())
                test.systemProperty("roborazzi.test.verify", (!record).toString())
                test.inputs.dir(layout.projectDirectory.dir("src/test/screenshots")).optional()
                test.maxHeapSize = "2g"
            }
        }
    }
}

tasks.named("preBuild") { dependsOn(generateDesignSystem) }
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach { dependsOn(generateDesignSystem) }

dependencies {
    val bom = platform(libs.androidx.compose.bom)
    api(bom)
    api(libs.androidx.ui)
    api(libs.androidx.ui.graphics)
    api(libs.androidx.ui.text)
    api(libs.androidx.foundation)
    implementation(libs.androidx.ui.tooling.preview)
    debugImplementation(libs.androidx.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.serialization.json)
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.roborazzi.junit.rule)
    testImplementation(bom)
    testImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.test.manifest)
}

// ------------------------------------------------------------------------------------------------------------------
// Generator
// ------------------------------------------------------------------------------------------------------------------

@CacheableTask
abstract class GenerateDesignSystem : DefaultTask() {
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val tokensJson: RegularFileProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val componentsCss: RegularFileProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val appJs: RegularFileProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val extraIcons: RegularFileProperty
    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    @TaskAction
    fun generate() {
        @Suppress("UNCHECKED_CAST")
        val tokens = (JsonSlurper().parse(tokensJson.get().asFile) as Map<String, Any>)["tokens"] as Map<String, Map<String, Any>>
        val out = outputDir.get().asFile.resolve(PKG.replace('.', '/'))
        out.deleteRecursively()
        out.mkdirs()
        out.resolve("Tokens.kt").writeText(tokensKt(tokens))
        out.resolve("Css.kt").writeText(cssKt(componentsCss.get().asFile.readText(), tokens))
        @Suppress("UNCHECKED_CAST")
        val extra = JsonSlurper().parse(extraIcons.get().asFile) as Map<String, Any>
        out.resolve("Glyphs.kt").writeText(glyphsKt(appJs.get().asFile.readText(), extra))
    }

    private companion object {
        const val PKG = "edu.ucsd.sysnet.cluetoothscanner.designsystem.tokens"
        const val HEADER = "// GENERATED by :designsystem generateDesignSystem from scanner/design/ui-v2. Do not edit.\n"

        fun fail(msg: String): Nothing = throw GradleException("generateDesignSystem: $msg")

        /** `--rssi-strong` → `rssiStrong`; `--t-fast` → `tFast`. */
        fun camel(name: String): String {
            val parts = name.trimStart('-').split('-').filter { it.isNotEmpty() }
            return parts.first() + parts.drop(1).joinToString("") { it.replaceFirstChar(Char::uppercaseChar) }
        }

        fun num(s: String): String {
            val d = s.toDouble()
            val r = if (d == Math.rint(d) && Math.abs(d) < 1e9) d.toLong().toString() else d.toString()
            return r
        }

        fun f(s: String) = "${num(s)}f"

        fun px(v: String): String {
            val m = Regex("""^(-?[\d.]+)px$""").matchEntire(v.trim()) ?: fail("not a px length: $v")
            return m.groupValues[1]
        }

        fun argb(hex: String): String {
            val h = hex.removePrefix("#")
            if (h.length != 8) fail("not an ARGB color: $hex")
            return "Color(0x$h)"
        }

        fun kstr(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("$", "\\$") + "\""

        // ---- tokens.json → Tokens.kt ----

        fun shadow(s: Map<*, *>) =
            "TokenShadow(x = ${num(px(s["x"] as String))}.dp, y = ${num(px(s["y"] as String))}.dp, " +
                "blur = ${num(px(s["blur"] as String))}.dp, spread = ${num(px(s["spread"] as String))}.dp, " +
                "color = ${argb(s["argb"] as String)})"

        /** A CSS expression over container and safe-area units as Kotlin over a [CssEnv], in dp. */
        fun expression(css: String): String {
            val tok = Regex("""\s*(env\([^)]*\)|[a-z]+\(|-?[\d.]+(px|cqw|cqh|%)?|[-+*/(),])""")
            var i = 0
            val sb = StringBuilder()
            while (i < css.length) {
                val m = tok.find(css, i)?.takeIf { it.range.first == i } ?: fail("unknown expression form: $css")
                val t = m.groupValues[1]
                sb.append(
                    when {
                        t == "env(safe-area-inset-top, 0px)" -> "e.safeTop"
                        t == "env(safe-area-inset-bottom, 0px)" -> "e.safeBottom"
                        t.startsWith("env(") -> fail("unknown env(): $t")
                        t == "calc(" -> "("
                        t == "max(" -> "maxOf("
                        t == "min(" -> "minOf("
                        t.endsWith("(") && t.length > 1 -> fail("unknown function $t in $css")
                        t.endsWith("cqw") -> "(e.containerWidth * ${f(t.removeSuffix("cqw"))} / 100f)"
                        t.endsWith("cqh") -> "(e.containerHeight * ${f(t.removeSuffix("cqh"))} / 100f)"
                        t.endsWith("%") -> "(e.parent * ${f(t.removeSuffix("%"))} / 100f)"
                        t.endsWith("px") -> f(t.removeSuffix("px"))
                        t.first().isDigit() || (t.length > 1 && t.first() == '-') -> f(t)
                        t == "," -> ", "
                        t in setOf("+", "-", "*", "/") -> " $t "
                        else -> t
                    },
                )
                i = m.range.last + 1
            }
            return sb.toString()
        }

        fun tokensKt(tokens: Map<String, Map<String, Any>>): String {
            val colors = tokens.filterValues { it["type"] == "color" }
            val shadows = tokens.filterValues { it["type"] == "shadow" }
            val other = tokens.filterValues { it["type"] != "color" && it["type"] != "shadow" }
            for ((name, t) in other) if (t["light"] != t["dark"]) fail("$name differs between light and dark")
            val sb = StringBuilder(HEADER)
            sb.append("package $PKG\n\n")
            sb.append("import androidx.compose.animation.core.CubicBezierEasing\n")
            sb.append("import androidx.compose.runtime.Immutable\n")
            sb.append("import androidx.compose.ui.graphics.Color\n")
            sb.append("import androidx.compose.ui.unit.Dp\n")
            sb.append("import androidx.compose.ui.unit.dp\n\n")

            sb.append("/** Every color token of tokens.css, for one scheme. */\n@Immutable\nclass ColorTokens(\n")
            for (name in colors.keys) sb.append("    /** `$name` */\n    val ${camel(name)}: Color,\n")
            sb.append(")\n\n")
            sb.append("/** Every shadow token of tokens.css, for one scheme. */\n@Immutable\nclass ShadowTokens(\n")
            for (name in shadows.keys) sb.append("    /** `$name` */\n    val ${camel(name)}: TokenShadow,\n")
            sb.append(")\n\n")

            sb.append("object Tokens {\n")
            for (scheme in listOf("light", "dark")) {
                sb.append("    val ${scheme}Colors = ColorTokens(\n")
                for ((name, t) in colors) sb.append("        ${camel(name)} = ${argb(t["${scheme}Argb"] as String)},\n")
                sb.append("    )\n")
                sb.append("    val ${scheme}Shadows = ShadowTokens(\n")
                for ((name, t) in shadows) sb.append("        ${camel(name)} = ${shadow(t["${scheme}Shadow"] as Map<*, *>)},\n")
                sb.append("    )\n")
            }
            val all = mutableListOf<Pair<String, String>>()
            for ((name, t) in other) {
                val v = t["light"] as String
                val k = camel(name)
                when (t["type"]) {
                    "length" -> { sb.append("    /** `$name` */\n    val $k: Dp = ${num(px(v))}.dp\n"); all += name to k }
                    "number" -> { sb.append("    /** `$name` */\n    const val $k: Float = ${f(v)}\n"); all += name to k }
                    "duration" -> {
                        sb.append("    /** `$name` in ms */\n    const val ${k}Ms: Int = ${num((t["ms"] as Number).toString())}\n")
                        all += name to "${k}Ms"
                    }
                    "easing" -> {
                        @Suppress("UNCHECKED_CAST")
                        val b = (t["bezier"] as List<Number>).map { f(it.toString()) }
                        sb.append("    /** `$name` */\n    val $k = CubicBezierEasing(${b.joinToString(", ")})\n")
                        all += name to k
                    }
                    "font" -> { sb.append("    /** `$name` */\n    const val $k: String = ${kstr(v)}\n"); all += name to k }
                    "expression" -> {
                        sb.append("    /** `$name`: `$v` */\n    fun $k(e: CssEnv): Dp = (${expression(v)}).dp\n")
                        all += name to k
                    }
                    else -> fail("unknown token type ${t["type"]} of $name")
                }
            }
            sb.append("\n    /** Every tokens.json entry (CSS name) and the Kotlin member generated for it. */\n")
            sb.append("    val generated: Map<String, String> = mapOf(\n")
            for (name in colors.keys + shadows.keys) sb.append("        ${kstr(name)} to ${kstr(camel(name))},\n")
            for ((name, k) in all) sb.append("        ${kstr(name)} to ${kstr(k)},\n")
            sb.append("    )\n}\n")
            return sb.toString()
        }

        // ---- components.css → Css.kt ----

        /** A numeric CSS value: px lengths and unitless numbers, var() of length/number tokens, calc() of those. */
        class Num(val v: Double, val unit: String)

        /** A small recursive-descent evaluator over px / unitless / em terms; null for anything else. */
        class Calc(val toks: List<String>) {
            var p = 0
            fun atom(): Num? {
                val t = toks.getOrNull(p++) ?: return null
                return when {
                    t == "calc(" || t == "(" -> expr().also { if (toks.getOrNull(p++) != ")") return null }
                    t.endsWith("px") -> Num(t.removeSuffix("px").toDouble(), "px")
                    t.endsWith("em") -> Num(t.removeSuffix("em").toDouble(), "em")
                    t.toDoubleOrNull() != null -> Num(t.toDouble(), "")
                    else -> null
                }
            }
            fun mul(): Num? {
                var a = atom() ?: return null
                while (toks.getOrNull(p) == "*" || toks.getOrNull(p) == "/") {
                    val op = toks[p++]
                    val b = atom() ?: return null
                    if (a.unit.isNotEmpty() && b.unit.isNotEmpty()) return null
                    a = Num(if (op == "*") a.v * b.v else a.v / b.v, a.unit.ifEmpty { b.unit })
                }
                return a
            }
            fun expr(): Num? {
                var a = mul() ?: return null
                while (toks.getOrNull(p) == "+" || toks.getOrNull(p) == "-") {
                    val op = toks[p++]
                    val b = mul() ?: return null
                    val unit = if (a.v == 0.0 && a.unit.isEmpty()) b.unit else a.unit
                    if (b.unit != unit && !(b.v == 0.0 && b.unit.isEmpty())) return null
                    a = Num(if (op == "+") a.v + b.v else a.v - b.v, unit)
                }
                return a
            }
        }

        fun evalTerm(src: String, tokens: Map<String, Map<String, Any>>): Num? {
            var s = src.trim()
            val varRe = Regex("""var\((--[\w-]+)\)""")
            var unresolved = false
            s = varRe.replace(s) { m ->
                val t = tokens[m.groupValues[1]]
                if (t == null || (t["type"] != "length" && t["type"] != "number")) { unresolved = true; "0" }
                else t["light"] as String
            }
            if (unresolved || "var(" in s) return null
            val toks = Regex("""calc\(|-?\d*\.?\d+(?:px|em)?|[-+*/()]""").findAll(s).map { it.value }.toList()
            if (toks.joinToString("").replace(" ", "") != s.replace(" ", "")) return null
            val parser = Calc(toks)
            val r = parser.expr()
            return if (parser.p == toks.size) r else null
        }

        /** Splits a value on top-level spaces (not inside parentheses). */
        fun splitSpaces(v: String): List<String> {
            val out = mutableListOf<String>()
            var depth = 0
            val cur = StringBuilder()
            for (c in v.trim()) {
                if (c == '(') depth++
                if (c == ')') depth--
                if (c.isWhitespace() && depth == 0) {
                    if (cur.isNotEmpty()) out += cur.toString()
                    cur.clear()
                } else cur.append(c)
            }
            if (cur.isNotEmpty()) out += cur.toString()
            return out
        }

        fun selectorName(sel: String): String {
            val s = sel.trim()
                .replace(Regex("""\[([\w-]+)(?:="([^"]*)")?]""")) { " ${it.groupValues[1]} ${it.groupValues[2]} ".replace(' ', '-') }
                .replace("::", ":")
            val parts = s.split(Regex("""\s*[ >+~]\s*""")).filter { it.isNotEmpty() }
            return parts.joinToString("_") { part ->
                part.split(Regex("""[^A-Za-z0-9]+""")).filter { it.isNotEmpty() }
                    .joinToString("") { w -> if (w == "*") "All" else w.replaceFirstChar(Char::uppercaseChar) }
                    .ifEmpty { "All" }
            }.let { if (it.first().isDigit()) "S$it" else it }
        }

        fun propName(p: String): String =
            if (p.startsWith("--")) "v" + camel(p).replaceFirstChar(Char::uppercaseChar) else camel(p)

        val BOX = setOf("padding", "margin", "inset")

        fun isLength(prop: String) = prop.startsWith("--") || prop in BOX || prop.startsWith("padding-") ||
            prop.startsWith("margin-") || prop in setOf(
                "gap", "top", "left", "right", "bottom", "width", "height", "min-width", "min-height", "max-width",
                "max-height", "border-radius", "font-size", "line-height", "outline-offset",
            )

        fun cssKt(css: String, tokens: Map<String, Map<String, Any>>): String {
            val src = css.replace(Regex("""/\*[\s\S]*?\*/"""), "")
            // top-level rules only; @media / @keyframes blocks are skipped whole
            val rules = linkedMapOf<String, LinkedHashMap<String, String>>()
            var i = 0
            while (i < src.length) {
                val open = src.indexOf('{', i)
                if (open < 0) break
                val prelude = src.substring(i, open).trim()
                var depth = 1
                var j = open + 1
                while (j < src.length && depth > 0) {
                    if (src[j] == '{') depth++ else if (src[j] == '}') depth--
                    j++
                }
                val body = src.substring(open + 1, j - 1)
                i = j
                if (prelude.startsWith("@")) continue
                for (sel in prelude.split(',')) {
                    val obj = selectorName(sel)
                    val props = rules.getOrPut(obj) { linkedMapOf() }
                    for (decl in body.split(';')) {
                        val k = decl.substringBefore(':', "").trim()
                        val v = decl.substringAfter(':', "").replace("!important", "").trim()
                        if (k.isEmpty() || v.isEmpty()) continue
                        val all = splitSpaces(v)
                        // keywords and color tokens beside lengths (`box-shadow: 0 0 0 2px var(--panel)`,
                        // `border: 1px solid var(--div)`): the lengths alone, as `…Lengths`
                        val isColorOrKeyword = { t: String ->
                            t in setOf("solid", "inset", "dashed") ||
                                Regex("""var\((--[\w-]+)\)""").matchEntire(t)?.let { tokens[it.groupValues[1]]?.get("type") == "color" } == true
                        }
                        val terms = all.filterNot(isColorOrKeyword)
                        val lengthsOnly = terms.size != all.size
                        if (terms.isEmpty()) continue
                        val nums = terms.map { evalTerm(it, tokens) }
                        if (nums.any { it == null }) continue
                        val n = nums.filterNotNull()
                        val lit = when {
                            lengthsOnly -> if (n.all { it.unit == "px" || it.v == 0.0 }) {
                                "listOf(${n.joinToString(", ") { "${num(it.v.toString())}.dp" }})"
                            } else null
                            n.all { it.unit == "px" || (it.unit == "" && it.v == 0.0) } &&
                                (n.any { it.unit == "px" } || isLength(k)) -> {
                                val dps = n.map { "${num(it.v.toString())}.dp" }
                                when {
                                    k in BOX -> {
                                        val (t, r, b, l) = when (dps.size) {
                                            1 -> listOf(dps[0], dps[0], dps[0], dps[0])
                                            2 -> listOf(dps[0], dps[1], dps[0], dps[1])
                                            3 -> listOf(dps[0], dps[1], dps[2], dps[1])
                                            4 -> dps
                                            else -> fail("bad $k in $sel")
                                        }
                                        "CssBox(top = $t, right = $r, bottom = $b, left = $l)"
                                    }
                                    dps.size == 1 -> dps[0]
                                    else -> "listOf(${dps.joinToString(", ")})"
                                }
                            }
                            n.size == 1 && n[0].unit == "" -> f(n[0].v.toString())
                            n.size == 1 && n[0].unit == "em" -> f(n[0].v.toString()) // em
                            else -> null
                        } ?: continue
                        val kn = propName(k) + when {
                            lengthsOnly -> "Lengths"
                            n.size == 1 && n[0].unit == "em" -> "Em"
                            else -> ""
                        }
                        props[kn] = "$lit // $k: $v"
                    }
                }
            }
            val sb = StringBuilder(HEADER)
            sb.append("package $PKG\n\n")
            sb.append("import androidx.compose.ui.unit.dp\n\n")
            sb.append("/**\n * Every components.css declaration whose value resolves from literals and tokens alone, by selector\n")
            sb.append(" * (`.rec-row .ib` → `RecRow_Ib`) and property (`padding-top` → `paddingTop`, `--x` → `vX`). Lengths are dp,\n")
            sb.append(" * padding/margin/inset a [CssBox], unitless values Float, `em` values Float (`…Em`). Declarations that\n")
            sb.append(" * use runtime variables (`--corner-r`, `--peek`, …) are not here; they are computed in code from tokens.\n */\n")
            sb.append("@Suppress(\"ClassName\", \"ObjectPropertyName\")\nobject Css {\n")
            for ((obj, props) in rules) {
                if (props.isEmpty()) continue
                sb.append("    object $obj {\n")
                for ((k, v) in props) sb.append("        val $k = $v\n")
                sb.append("    }\n")
            }
            sb.append("}\n")
            return sb.toString()
        }

        // ---- app.js ICONS (+ extra-icons.json) → Glyphs.kt ----

        fun attrs(tag: String): Map<String, String> =
            Regex("""([\w-]+)="([^"]*)"""").findAll(tag).associate { it.groupValues[1] to it.groupValues[2] }

        fun d(v: Double) = num(v.toString())

        /** One SVG fragment as (path data, solid) pairs: path, circle, rect, line, polyline and polygon. */
        fun svgPaths(svg: String, where: String): List<Pair<String, Boolean>> {
            val els = Regex("""<(\w+)\s([^>]*?)/>""").findAll(svg).toList()
            val rest = Regex("""<(\w+)\s([^>]*?)/>""").replace(svg, "").trim()
            if (rest.isNotEmpty()) fail("$where: unsupported SVG content: $rest")
            return els.map { m ->
                val a = attrs(m.groupValues[2])
                val g = { k: String -> a[k]?.toDouble() ?: 0.0 }
                val path = when (m.groupValues[1]) {
                    "path" -> a["d"] ?: fail("$where: path without d")
                    "circle" -> {
                        val (cx, cy, r) = Triple(g("cx"), g("cy"), g("r"))
                        "M${d(cx - r)} ${d(cy)}a${d(r)} ${d(r)} 0 1 0 ${d(2 * r)} 0a${d(r)} ${d(r)} 0 1 0 ${d(-2 * r)} 0Z"
                    }
                    "rect" -> {
                        val (x, y, w, h) = listOf(g("x"), g("y"), g("width"), g("height"))
                        val rx = a["rx"]?.toDouble() ?: a["ry"]?.toDouble() ?: 0.0
                        if (rx == 0.0) "M${d(x)} ${d(y)}h${d(w)}v${d(h)}h${d(-w)}Z"
                        else "M${d(x + rx)} ${d(y)}h${d(w - 2 * rx)}a${d(rx)} ${d(rx)} 0 0 1 ${d(rx)} ${d(rx)}" +
                            "v${d(h - 2 * rx)}a${d(rx)} ${d(rx)} 0 0 1 ${d(-rx)} ${d(rx)}h${d(-(w - 2 * rx))}" +
                            "a${d(rx)} ${d(rx)} 0 0 1 ${d(-rx)} ${d(-rx)}v${d(-(h - 2 * rx))}" +
                            "a${d(rx)} ${d(rx)} 0 0 1 ${d(rx)} ${d(-rx)}Z"
                    }
                    "line" -> "M${d(g("x1"))} ${d(g("y1"))}L${d(g("x2"))} ${d(g("y2"))}"
                    "polyline", "polygon" -> {
                        val pts = (a["points"] ?: fail("$where: no points")).trim().split(Regex("""[\s,]+"""))
                        "M" + pts.chunked(2).joinToString("L") { "${it[0]} ${it[1]}" } +
                            if (m.groupValues[1] == "polygon") "Z" else ""
                    }
                    else -> fail("$where: unsupported element <${m.groupValues[1]}>")
                }
                path to (a["class"] == "solid")
            }
        }

        fun glyphsKt(appJs: String, extra: Map<String, Any>): String {
            val block = Regex("""const ICONS = \{([\s\S]*?)\n\s*\};""").find(appJs)?.groupValues?.get(1)
                ?: fail("app.js has no ICONS block")
            val icons = linkedMapOf<String, Pair<String, String>>() // name → (viewBox, svg)
            for (line in block.lines()) {
                val l = line.trim()
                if (l.isEmpty()) continue
                val m = Regex("""^(\w+): '(.*)',?$""").matchEntire(l) ?: fail("unexpected ICONS line: $l")
                icons[m.groupValues[1]] = "0 0 24 24" to m.groupValues[2]
            }
            @Suppress("UNCHECKED_CAST")
            for ((name, v) in extra["icons"] as Map<String, Any>) {
                if (name in icons) fail("extra icon $name shadows an app.js icon")
                val e = v as Map<String, String>
                icons[name] = (e["viewBox"] ?: "0 0 24 24") to (e["svg"] ?: fail("extra icon $name has no svg"))
            }
            val sb = StringBuilder(HEADER)
            sb.append("package $PKG\n\n")
            sb.append("/** The stroke glyphs (C-17): app.js ICONS, then designsystem/icons/extra-icons.json. */\n")
            sb.append("object Glyphs {\n")
            for ((name, v) in icons) {
                val vb = v.first.split(' ').map { it.toDouble() }
                val paths = svgPaths(v.second, name)
                sb.append("    val $name = Glyph(${kstr(name)}, ${f(vb[2].toString())}, ${f(vb[3].toString())}, listOf(\n")
                for ((p, solid) in paths) sb.append("        GlyphPath(${kstr(p)}, solid = $solid),\n")
                sb.append("    ))\n")
            }
            sb.append("\n    val all: List<Glyph> = listOf(${icons.keys.joinToString(", ")})\n")
            sb.append("    /** The app.js ICONS names, for the coverage test. */\n")
            val fromApp = icons.keys.filter { it !in (extra["icons"] as Map<*, *>).keys }
            sb.append("    val fromAppJs: List<String> = listOf(${fromApp.joinToString(", ") { kstr(it) }})\n")
            sb.append("}\n")
            return sb.toString()
        }
    }
}

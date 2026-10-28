package edu.ucsd.sysnet.cluetoothscanner.local

import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet

/**
 * An in-memory SQLite behind [SqlDatabase]. Query arguments are bound as text, like
 * Android's rawQuery, so SQL that only works with typed binds fails here too.
 */
class JdbcSqlDatabase(
    private val connection: Connection = DriverManager.getConnection("jdbc:sqlite::memory:"),
    createSchema: Boolean = true,
) : SqlDatabase {
    private var depth = 0

    init {
        if (createSchema) LocalSchema.CREATE.forEach { sql -> connection.createStatement().use { it.execute(sql) } }
    }

    @Synchronized
    override fun execute(sql: String, args: List<Any?>): Int =
        connection.prepareStatement(sql).use { statement ->
            args.forEachIndexed { i, value -> statement.setObject(i + 1, value) }
            statement.executeUpdate()
        }

    @Synchronized
    override fun insert(sql: String, args: List<Any?>): Long {
        execute(sql, args)
        return connection.createStatement().use { s -> s.executeQuery("SELECT last_insert_rowid()").use { it.next(); it.getLong(1) } }
    }

    @Synchronized
    override fun <T> query(sql: String, args: List<Any?>, map: (SqlRow) -> T): List<T> =
        connection.prepareStatement(sql).use { statement ->
            args.forEachIndexed { i, value -> statement.setObject(i + 1, value?.toString()) }
            statement.executeQuery().use { rs ->
                val row = ResultRow(rs)
                buildList { while (rs.next()) add(map(row)) }
            }
        }

    @Synchronized
    override fun <T> transaction(block: () -> T): T {
        if (depth > 0) return block()
        depth++
        connection.autoCommit = false
        try {
            val result = block()
            connection.commit()
            return result
        } catch (error: Throwable) {
            connection.rollback()
            throw error
        } finally {
            depth--
            connection.autoCommit = true
        }
    }

    /** Hubs close their database; tests that reopen the same one keep it alive. */
    override fun close() = connection.close()

    class ResultRow(private val rs: ResultSet) : SqlRow {
        override fun long(index: Int) = rs.getLong(index + 1)
        override fun longOrNull(index: Int) = rs.getLong(index + 1).takeUnless { rs.wasNull() }
        override fun double(index: Int) = rs.getDouble(index + 1)
        override fun doubleOrNull(index: Int) = rs.getDouble(index + 1).takeUnless { rs.wasNull() }
        override fun string(index: Int): String = rs.getString(index + 1)
        override fun stringOrNull(index: Int): String? = rs.getString(index + 1)
        override fun blob(index: Int): ByteArray = rs.getBytes(index + 1)
    }
}

/** A database whose [close] does nothing, so a second hub can reopen the same data. */
class UncloseableDatabase(private val inner: SqlDatabase) : SqlDatabase by inner {
    override fun close() = Unit
}

/** Counts the queries whose SQL contains one of the given fragments. */
class CountingDatabase(private val inner: SqlDatabase) : SqlDatabase by inner {
    val counts = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicInteger>()

    fun count(fragment: String): Int = counts.entries.filter { fragment in it.key }.sumOf { it.value.get() }

    override fun <T> query(sql: String, args: List<Any?>, map: (SqlRow) -> T): List<T> {
        counts.getOrPut(sql) { java.util.concurrent.atomic.AtomicInteger() }.incrementAndGet()
        return inner.query(sql, args, map)
    }

    override fun close() = Unit
}

/** A matcher whose rules are "the advertisement contains this ASCII text". */
class FakeMatcher(
    override val bundleVersion: String,
    private val rules: Map<String, String>,
    private val addressRules: Map<String, String> = emptyMap(),
    override val engineVersion: Int = 1,
) : AdvertisementMatcher {
    var failAfter: Int? = null
    private var attempts = 0

    /** Fails this many calls, then works (a transient fault). */
    var failFirst: Int = 0
    private var failed = 0

    /** How many times [match] ran. */
    val calls = java.util.concurrent.atomic.AtomicInteger()

    override val usesAddressRules: Boolean = addressRules.isNotEmpty()

    override fun match(raw: ByteArray, addr: String?, addrType: AddressType?): List<String> {
        calls.incrementAndGet()
        if (failed < failFirst) {
            failed++
            error("matcher failed")
        }
        failAfter?.let { if (++attempts > it) error("matcher failed") }
        val text = String(raw, Charsets.ISO_8859_1)
        return rules.filterValues { text.contains(it) }.keys.toList() +
            addressRules.filterValues { addr != null && addr.startsWith(it) }.keys
    }
}

fun adv(name: String) = byteArrayOf(2, 1, 6, (name.length + 1).toByte(), 9) + name.toByteArray()

fun bundleJson(version: String, vararg pages: Triple<String, String, List<String>>): String =
    bundleJson(version, version, pages.toList())

/** [rulesVersion] changes only when the rules do; [version] with anything. */
fun bundleJson(version: String, rulesVersion: String, pages: List<Triple<String, String, List<String>>>): String {
    val pagesJson = pages.joinToString(",") { (id, title, categories) ->
        val cats = categories.joinToString(",") { "\"$it\"" }
        """{"id":"$id","title":"$title","kind":"device","one_line":"$title product","categories":[$cats],"entity":null,"has_rule":true,"rule":{"name":{"prefix":"x"}}}"""
    }
    return """{"schema":1,"version":"$version","rules_version":"$rulesVersion","categories":[
        {"id":"audio","label":"Audio","label_zh":"音频"},{"id":"lighting","label":"Lighting","label_zh":"照明"}],
        "pages":[$pagesJson]}"""
}

val PAGES = arrayOf(
    Triple("device--buds", "Buds", listOf("audio")),
    Triple("device--bulb", "Bulb", listOf("lighting")),
    Triple("device--multi", "Multi", listOf("audio", "lighting")),
    Triple("platform--proto", "Proto", emptyList()),
)

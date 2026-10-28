package edu.ucsd.sysnet.cluetoothscanner.local

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class WikiCatalogTest {
    // Unit tests run in the module directory (cluetooth-android/app).
    private val asset = File("src/main/assets/wiki-bundle.json")

    @Test
    fun theCheckedInAssetParsesAndIsConsistent() {
        val catalog = WikiCatalog.parse(asset.readText())
        assertEquals(1, catalog.schema)
        assertTrue(catalog.version.isNotBlank())
        assertEquals(40, catalog.pages.size)
        assertEquals(27, catalog.pages.count { it.hasRule })
        assertEquals(catalog.pages.size, catalog.pages.map { it.id }.toSet().size)
        for (page in catalog.pages) {
            assertTrue(page.id, "--" in page.id)
            for (c in page.categories) assertNotNull("${page.id}: unknown category $c", catalog.category(c))
        }
        // the old snapshot has no categories of its own: the checked-in mapping gives devices theirs
        assertEquals(listOf("audio-device"), catalog.page("product_family--jabra-name-tokens")?.categories)
        assertEquals(PageKind.PLATFORM, catalog.page("protocol--ibeacon")?.kind)
        assertTrue(catalog.categories.all { it.label.isNotBlank() && it.labelZh.isNotBlank() })
        assertNull(catalog.page("nope"))
    }

    @Test
    fun anUnknownSchemaIsRefused() {
        try {
            WikiCatalog.parse("""{"schema":2,"version":"x","categories":[],"pages":[]}""")
            fail()
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("schema 2"))
        }
    }

    @Test
    fun unknownFieldsAreIgnoredSoTheBundleCanGrow() {
        val catalog = WikiCatalog.parse(
            """{"schema":1,"version":"x","extra":1,"categories":[],"pages":[
                {"id":"device--a","title":"A","kind":"device","one_line":"x","has_rule":false,"future":true}]}""",
        )
        assertEquals(emptyList<String>(), catalog.pages.single().categories)
        assertNull(catalog.pages.single().entity)
    }
}

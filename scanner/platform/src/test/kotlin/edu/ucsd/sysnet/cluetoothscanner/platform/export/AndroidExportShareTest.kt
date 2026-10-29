package edu.ucsd.sysnet.cluetoothscanner.platform.export

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import edu.ucsd.sysnet.cluetoothscanner.api.core.PreparedExport
import edu.ucsd.sysnet.cluetoothscanner.api.platform.PlatformError
import edu.ucsd.sysnet.cluetoothscanner.api.platform.PlatformResult
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AndroidExportShareTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val acknowledged = mutableListOf<String>()
    private val written = mutableListOf<Pair<String, ByteArray>>()
    private var failWrite = false

    private val share = AndroidExportShare(
        context,
        acknowledge = { acknowledged += it },
        scope = CoroutineScope(Dispatchers.Unconfined),
        io = Dispatchers.Unconfined,
        downloads = { name, _, source ->
            if (failWrite) throw IOException("disk full")
            written += name to source.readBytes()
            name
        },
    )

    /**
     * FileProvider caches each authority's roots in a static map, but Robolectric gives every test a new data dir:
     * without this, only the first test that shares finds its root.
     */
    @Before
    fun clearFileProviderCache() {
        val cache = FileProvider::class.java.getDeclaredField("sCache").apply { isAccessible = true }
        (cache.get(null) as MutableMap<*, *>).clear()
    }

    private fun export(id: String): PreparedExport {
        val file = File(context.filesDir, "cluetooth-core/export-temp/$id/cluetooth-$id.zip").apply {
            parentFile!!.mkdirs()
            writeBytes(byteArrayOf(0x50, 0x4b, 3, 4))
        }
        return PreparedExport(id, file.path, "cluetooth-$id.zip", fileCount = 1, sizeBytes = file.length())
    }

    @Test
    fun `O-1 share hands the ZIP to the system share sheet through the export provider`() {
        assertEquals(PlatformResult.Ok(Unit), share.share(export("e1")))
        val chooser = shadowOf(context).nextStartedActivity
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        @Suppress("DEPRECATION")
        val send = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertEquals(Intent.ACTION_SEND, send.action)
        assertEquals(AndroidExportShare.ZIP, send.type)
        @Suppress("DEPRECATION")
        val uri = send.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)!!
        assertEquals("content", uri.scheme)
        assertEquals(AndroidExportShare.authority(context), uri.authority)
        assertTrue(send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertTrue("the receiving app may still read it", acknowledged.isEmpty())
    }

    @Test
    fun `section 4_5 a shared export is acknowledged at the next export`() = runBlocking {
        share.share(export("e1"))
        share.share(export("e2"))
        assertEquals(listOf("e1"), acknowledged)
        share.saveToDownloads(export("e3"))
        assertEquals(listOf("e1", "e2", "e3"), acknowledged)
    }

    @Test
    fun `section 4_5 a shared export is acknowledged at the next app start`() = runBlocking {
        share.share(export("e1"))
        // A new process: the outstanding id survives in prefs.
        val next = AndroidExportShare(context, { acknowledged += it }, CoroutineScope(Dispatchers.Unconfined), Dispatchers.Unconfined) { n, _, _ -> n }
        next.acknowledgeStale()
        assertEquals(listOf("e1"), acknowledged)
        next.acknowledgeStale()
        assertEquals("only once", listOf("e1"), acknowledged)
    }

    @Test
    fun `O-1 save to Downloads copies the ZIP under its name, then acknowledges`() = runBlocking {
        share.saveToDownloads(export("e1"))
        assertEquals("cluetooth-e1.zip", written.single().first)
        assertTrue(written.single().second.contentEquals(byteArrayOf(0x50, 0x4b, 3, 4)))
        assertEquals(listOf("e1"), acknowledged)
    }

    @Test
    fun `a failed save is a typed Io failure and keeps the export pending for a retry`() = runBlocking {
        failWrite = true
        assertEquals(PlatformResult.Err(PlatformError.Io("disk full")), share.saveToDownloads(export("e1")))
        assertTrue(acknowledged.isEmpty())
        assertTrue("the ZIP is still there for a retry", File(export("e1").localPath).exists())
        failWrite = false
        share.saveToDownloads(export("e1"))
        assertEquals(listOf("e1"), acknowledged)
    }

    @Test
    fun `a ZIP outside the provider's roots is a typed failure, not a crash`() {
        val outside = PreparedExport("e9", folder.newFile("elsewhere.zip").path, "elsewhere.zip", fileCount = 1, sizeBytes = 0)
        assertTrue(share.share(outside) is PlatformResult.Err)
    }

    @Test
    fun `a failed acknowledgement stays pending and is retried at the next app start`() = runBlocking {
        var refuse = true
        val flaky = AndroidExportShare(
            context,
            { id -> if (refuse) throw IllegalStateException("core busy") else acknowledged += id },
            CoroutineScope(Dispatchers.Unconfined),
            Dispatchers.Unconfined,
        ) { n, _, _ -> n }
        flaky.saveToDownloads(export("e1"))
        assertTrue("the core refused it", acknowledged.isEmpty())
        refuse = false
        AndroidExportShare(context, { acknowledged += it }, CoroutineScope(Dispatchers.Unconfined), Dispatchers.Unconfined) { n, _, _ -> n }
            .acknowledgeStale()
        assertEquals(listOf("e1"), acknowledged)
    }

    @Test
    fun `API 24-28 Downloads never overwrite an existing file`() {
        val dir = folder.newFolder("Download")
        val source = folder.newFile("src.zip").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val legacy = LegacyDownloads(context) { dir }
        assertEquals("cluetooth.zip", legacy.write("cluetooth.zip", AndroidExportShare.ZIP, source))
        assertEquals("cluetooth (1).zip", legacy.write("cluetooth.zip", AndroidExportShare.ZIP, source))
        assertEquals("cluetooth (2).zip", legacy.write("cluetooth.zip", AndroidExportShare.ZIP, source))
        assertTrue(File(dir, "cluetooth (2).zip").readBytes().contentEquals(byteArrayOf(1, 2, 3)))
        assertFalse(File(dir, "cluetooth (3).zip").exists())
    }

    @Test
    fun `the Downloads writer follows the API level`() {
        assertTrue(Build.VERSION.SDK_INT >= 29)
        assertTrue(AndroidExportShare.defaultDownloads(context) is MediaStoreDownloads)
    }
}

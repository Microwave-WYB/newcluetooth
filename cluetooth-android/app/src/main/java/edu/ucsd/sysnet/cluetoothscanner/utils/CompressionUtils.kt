package edu.ucsd.sysnet.cluetoothscanner.utils

import com.github.luben.zstd.Zstd
import java.io.IOException
import android.util.Log

object CompressionUtils {

    private const val TAG = "CompressionUtils"

    @Throws(IOException::class)
    fun compressData(data: ByteArray): ByteArray {
        return try {
            Log.d(TAG, "Starting compression for ${data.size} bytes")
            val result = Zstd.compress(data)
            Log.d(TAG, "Compression successful: ${data.size} -> ${result.size} bytes")
            result
        } catch (e: Exception) {
            Log.e(TAG, "Compression failed for ${data.size} bytes", e)
            throw IOException("Failed to compress data", e)
        }
    }

    @Throws(IOException::class)
    fun decompressData(compressedData: ByteArray): ByteArray {
        return try {
            Log.d(TAG, "Starting decompression for ${compressedData.size} bytes")
            // Get the original size from zstd frame (if available)
            val decompressedSize = Zstd.decompressedSize(compressedData)
            Log.d(TAG, "Zstd decompressed size: $decompressedSize")

            val result = if (decompressedSize > 0) {
                Log.d(TAG, "Using known decompressed size: $decompressedSize")
                Zstd.decompress(compressedData, decompressedSize.toInt())
            } else {
                Log.d(TAG, "Unknown decompressed size, using estimation")
                // Fallback: estimate size and retry if needed
                var estimatedSize = compressedData.size * 4
                var result: ByteArray? = null
                while (result == null && estimatedSize < compressedData.size * 20) {
                    try {
                        Log.d(TAG, "Trying decompression with estimated size: $estimatedSize")
                        result = Zstd.decompress(compressedData, estimatedSize)
                    } catch (e: Exception) {
                        Log.d(TAG, "Estimation $estimatedSize failed, trying ${estimatedSize * 2}")
                        estimatedSize *= 2
                    }
                }
                result ?: throw IOException("Failed to decompress data - unable to determine size")
            }
            Log.d(TAG, "Decompression successful: ${compressedData.size} -> ${result.size} bytes")
            result
        } catch (e: Exception) {
            Log.e(TAG, "Decompression failed for ${compressedData.size} bytes", e)
            throw IOException("Failed to decompress data", e)
        }
    }

    @Throws(IOException::class)
    fun compressString(text: String): ByteArray {
        Log.d(TAG, "Compressing string of length: ${text.length}")
        val bytes = text.toByteArray(Charsets.UTF_8)
        Log.d(TAG, "String converted to ${bytes.size} bytes")
        return compressData(bytes)
    }

    @Throws(IOException::class)
    fun decompressString(compressedData: ByteArray): String {
        Log.d(TAG, "Decompressing to string from ${compressedData.size} bytes")
        val decompressed = decompressData(compressedData)
        val result = String(decompressed, Charsets.UTF_8)
        Log.d(TAG, "Decompressed to string of length: ${result.length}")
        return result
    }

    fun getCompressionRatio(originalSize: Int, compressedSize: Int): Double {
        return if (originalSize > 0) {
            compressedSize.toDouble() / originalSize.toDouble()
        } else {
            0.0
        }
    }
}

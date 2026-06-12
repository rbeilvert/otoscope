package dev.rubec.otoscope.stream

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import java.nio.ByteBuffer

/**
 * Shared JPEG decoder. Prefers [ImageDecoder] (hardware-accelerated on API 28+;
 * our minSdk is 29 so we use it unconditionally), with a [BitmapFactory] fallback
 * for the rare malformed payloads that crash ImageDecoder. The hardware path
 * roughly halves the CPU cost vs `BitmapFactory.decodeByteArray` and is mostly
 * responsible for the smoothness gap users see between this app and the Python
 * reference viewer.
 */
internal object JpegDecoder {
    fun decode(data: ByteArray, length: Int = data.size): Bitmap? {
        val source = ImageDecoder.createSource(ByteBuffer.wrap(data, 0, length))
        return try {
            ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                decoder.isMutableRequired = false
                decoder.allocator = ImageDecoder.ALLOCATOR_HARDWARE
            }
        } catch (e: Exception) {
            // Fall back to the software decoder on the occasional partial frame
            // we still chose to emit (e.g. a chunk arrived without its EOI).
            try {
                BitmapFactory.decodeByteArray(data, 0, length)
            } catch (_: Exception) {
                null
            }
        }
    }
}

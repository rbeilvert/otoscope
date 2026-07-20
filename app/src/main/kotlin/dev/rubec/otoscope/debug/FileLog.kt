package dev.rubec.otoscope.debug

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Debug-build file logger. Drop-in-compatible signatures with [android.util.Log]
 * so files can swap `import android.util.Log` for
 * `import dev.rubec.otoscope.debug.FileLog as Log` and every existing call gets
 * teed to both logcat AND a file inside the app's external storage.
 *
 * The file lives at `getExternalFilesDir(null)/otoscope-debug.log` on the device,
 * accessible via `adb pull /sdcard/Android/data/<pkg>/files/otoscope-debug.log`
 * without any permissions. Reset on every [init] call, i.e. every app cold start.
 *
 * Used to diagnose scenarios where `adb logcat` output is unreliable (some
 * hardened Android variants, custom ROMs, or GrapheneOS-style filtering).
 */
object FileLog {
    @Volatile private var writer: PrintWriter? = null
    @Volatile private var path: String? = null
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.ROOT)

    /** Open (and truncate) the log file. Safe to call multiple times, later
     *  calls just reset. Failures fall back to logcat-only. */
    fun init(context: Context) {
        try {
            val dir = context.getExternalFilesDir(null) ?: context.filesDir
            val file = File(dir, "otoscope-debug.log")
            file.parentFile?.mkdirs()
            file.writeText("=== otoscope-debug ${Date()} ===\n")
            writer = PrintWriter(FileWriter(file, /* append */ true), /* autoFlush */ true)
            path = file.absolutePath
            Log.i(TAG, "logging to $path")
        } catch (e: Exception) {
            Log.e(TAG, "FileLog init failed", e)
        }
    }

    /** Absolute path of the log file, once [init] has succeeded. */
    fun path(): String? = path

    fun v(tag: String, msg: String): Int {
        write('V', tag, msg)
        return Log.v(tag, msg)
    }

    fun d(tag: String, msg: String): Int {
        write('D', tag, msg)
        return Log.d(tag, msg)
    }

    fun i(tag: String, msg: String): Int {
        write('I', tag, msg)
        return Log.i(tag, msg)
    }

    fun w(tag: String, msg: String): Int {
        write('W', tag, msg)
        return Log.w(tag, msg)
    }

    fun w(tag: String, msg: String, tr: Throwable): Int {
        write('W', tag, "$msg\n${Log.getStackTraceString(tr)}")
        return Log.w(tag, msg, tr)
    }

    fun e(tag: String, msg: String): Int {
        write('E', tag, msg)
        return Log.e(tag, msg)
    }

    fun e(tag: String, msg: String, tr: Throwable): Int {
        write('E', tag, "$msg\n${Log.getStackTraceString(tr)}")
        return Log.e(tag, msg, tr)
    }

    /** Pass-through; some callers use this to render exceptions manually. */
    fun getStackTraceString(t: Throwable): String = Log.getStackTraceString(t)

    private fun write(priority: Char, tag: String, msg: String) {
        val w = writer ?: return
        try {
            w.println("${fmt.format(Date())} $priority/$tag: $msg")
        } catch (_: Exception) {
            // Logging must never break the app.
        }
    }

    private const val TAG = "FileLog"
}

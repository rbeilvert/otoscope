package dev.rubec.otoscope.vm

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/** Stream view options persisted across app launches. Each one is a plain
 *  [MutableStateFlow] seeded from [SharedPreferences] and written back on
 *  every change, so callers use it like any other state. */
class StreamSettings(context: Context, private val scope: CoroutineScope) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    val flipEnabled = persisted(KEY_FLIP_ENABLED, true, SharedPreferences::getBoolean, SharedPreferences.Editor::putBoolean)
    val autoRotateEnabled = persisted(KEY_AUTO_ROTATE, true, SharedPreferences::getBoolean, SharedPreferences.Editor::putBoolean)
    val manualRotation = persisted(KEY_MANUAL_ROTATION, 0f, SharedPreferences::getFloat, SharedPreferences.Editor::putFloat)

    private fun <T> persisted(
        key: String,
        default: T,
        read: SharedPreferences.(String, T) -> T,
        write: SharedPreferences.Editor.(String, T) -> SharedPreferences.Editor,
    ): MutableStateFlow<T> = MutableStateFlow(prefs.read(key, default)).also { flow ->
        // Undispatched so the collector subscribes before any write can land;
        // drop(1) skips the value we just read back.
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            flow.drop(1).collect { value -> prefs.edit { write(key, value) } }
        }
    }

    private companion object {
        const val PREFS_NAME = "otoscope_settings"
        const val KEY_FLIP_ENABLED = "flip_enabled"
        const val KEY_AUTO_ROTATE = "auto_rotate_enabled"
        const val KEY_MANUAL_ROTATION = "manual_rotation"
    }
}

package StarBase.Android.Forum.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Multipliers over the app's typography, in addition to Android's font scale. */
data class ReaderPreferences(
    val fontScale: Float = 1f,
    val lineHeightScale: Float = 1f
) {
    fun normalized() = copy(
        fontScale = if (fontScale.isFinite()) fontScale.coerceIn(0.85f, 1.5f) else 1f,
        lineHeightScale = if (lineHeightScale.isFinite()) lineHeightScale.coerceIn(0.85f, 1.4f) else 1f
    )
}

class ReaderPreferenceStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("reader_preferences", Context.MODE_PRIVATE)

    var value by mutableStateOf(
        ReaderPreferences(
            fontScale = runCatching { prefs.getFloat("font_scale", 1f) }.getOrDefault(1f),
            lineHeightScale = runCatching { prefs.getFloat("line_height_scale", 1f) }.getOrDefault(1f)
        ).normalized()
    )
        private set

    fun read(): ReaderPreferences = value

    fun update(preferences: ReaderPreferences) {
        value = preferences.normalized()
        prefs.edit()
            .putFloat("font_scale", value.fontScale)
            .putFloat("line_height_scale", value.lineHeightScale)
            .apply()
    }
}

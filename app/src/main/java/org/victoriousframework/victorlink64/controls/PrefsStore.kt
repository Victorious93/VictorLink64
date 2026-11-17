package org.victoriousframework.victorlink64.controls

import android.content.Context
import android.content.SharedPreferences

/** [KeyValueStore] over SharedPreferences. One file for every overlay setting, layout and key binding. */
class PrefsStore(private val sp: SharedPreferences) : KeyValueStore {
    override fun getString(key: String): String? = sp.getString(key, null)
    override fun putString(key: String, value: String) = sp.edit().putString(key, value).apply()
    override fun remove(key: String) = sp.edit().remove(key).apply()
}

object ControlsPrefs {
    private const val FILE = "vl64_controls"

    fun store(context: Context): KeyValueStore =
        PrefsStore(context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE))

    fun settings(context: Context) = OverlaySettings(store(context))
    fun layouts(context: Context) = LayoutStore(store(context))
    fun remaps(context: Context) = RemapStore(store(context))
}

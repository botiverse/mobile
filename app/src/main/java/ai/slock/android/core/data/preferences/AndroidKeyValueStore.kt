package ai.slock.android.core.data.preferences

import android.content.Context
import ai.slock.compose.data.SharedPreferenceStore

class AndroidSharedPreferencesKeyValueStore(
    context: Context,
    preferencesName: String
) : SharedPreferenceStore {
    private val preferences = context.applicationContext.getSharedPreferences(
        preferencesName,
        Context.MODE_PRIVATE
    )

    override fun getString(key: String): String? =
        preferences.getString(key, null)

    override fun putString(key: String, value: String?) {
        val editor = preferences.edit()
        if (value == null) {
            editor.remove(key)
        } else {
            editor.putString(key, value)
        }
        editor.apply()
    }
}

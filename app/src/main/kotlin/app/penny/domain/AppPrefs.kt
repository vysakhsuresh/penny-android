package app.penny.domain

import android.content.Context
import app.penny.core.event.Actor
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** Device-local settings that are not user data: which profile is open and this device's id. */
@Singleton
class AppPrefs @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences("penny.app", Context.MODE_PRIVATE)
    private val _profileId = MutableStateFlow(prefs.getString(KEY_PROFILE, null))
    val currentProfileId: StateFlow<String?> = _profileId

    fun setCurrentProfile(id: String) {
        prefs.edit().putString(KEY_PROFILE, id).apply()
        _profileId.value = id
    }

    val deviceId: String by lazy {
        prefs.getString(KEY_DEVICE, null) ?: UUID.randomUUID().toString().also { prefs.edit().putString(KEY_DEVICE, it).apply() }
    }

    /** M0 has no login (US-01 local-only mode): the local account id is per install. */
    val localAccountId: String by lazy {
        prefs.getString(KEY_ACCOUNT, null) ?: UUID.randomUUID().toString().also { prefs.edit().putString(KEY_ACCOUNT, it).apply() }
    }

    fun actor() = Actor(localAccountId, deviceId)

    private companion object {
        const val KEY_PROFILE = "current_profile"
        const val KEY_DEVICE = "device_id"
        const val KEY_ACCOUNT = "local_account_id"
    }
}

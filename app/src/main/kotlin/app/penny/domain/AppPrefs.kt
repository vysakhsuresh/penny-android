package app.penny.domain

import android.content.Context
import app.penny.core.event.Actor
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

enum class ThemeMode { SYSTEM, LIGHT, DARK, BLACK, HIGH_CONTRAST }

/** ST-02 appearance and other device-local preferences (not user data, never synced). */
data class Appearance(
    val theme: ThemeMode = ThemeMode.SYSTEM,
    /** 1.0 follows the system font scale; the app multiplies on top of it (up to 2.0 for 200%). */
    val textScale: Float = 1f,
    val dense: Boolean = false,
    val haptics: Boolean = true,
    val reduceMotion: Boolean = false,
    /** ARGB accent override; 0 uses the Penny accent. */
    val accent: Long = 0,
    /** SE-04: hide amounts until tapped. */
    val hideAmounts: Boolean = false,
)

data class SecurityPrefs(
    val lockEnabled: Boolean = false,
    val biometric: Boolean = true,
    /** Seconds in background before the app locks again. */
    val autoLockSeconds: Int = 30,
    val blockScreenshots: Boolean = false,
    /** PBKDF2 hashes "salt:hash" in base64; empty when unset. */
    val pinHash: String = "",
    val duressPinHash: String = "",
    val duressProfileId: String? = null,
)

/** Device-local settings: which profile is open, this device's id, appearance, security and onboarding hints. */
@Singleton
class AppPrefs @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences("penny.app", Context.MODE_PRIVATE)
    private val _profileId = MutableStateFlow(prefs.getString(KEY_PROFILE, null))
    val currentProfileId: StateFlow<String?> = _profileId

    fun setCurrentProfile(id: String?) {
        prefs.edit().putString(KEY_PROFILE, id).apply()
        _profileId.value = id
    }

    val deviceId: String by lazy {
        prefs.getString(KEY_DEVICE, null) ?: UUID.randomUUID().toString().also { prefs.edit().putString(KEY_DEVICE, it).apply() }
    }

    /** Local-only mode (US-01): the local account id is per install until the user signs in. */
    val localAccountId: String
        get() = prefs.getString(KEY_ACCOUNT, null) ?: UUID.randomUUID().toString().also { prefs.edit().putString(KEY_ACCOUNT, it).apply() }

    fun setAccountId(id: String) { prefs.edit().putString(KEY_ACCOUNT, id).apply() }

    fun actor() = Actor(localAccountId, deviceId)

    private val _appearance = MutableStateFlow(readAppearance())
    val appearance: StateFlow<Appearance> = _appearance

    private fun readAppearance() = Appearance(
        theme = runCatching { ThemeMode.valueOf(prefs.getString("ap.theme", ThemeMode.SYSTEM.name)!!) }.getOrDefault(ThemeMode.SYSTEM),
        textScale = prefs.getFloat("ap.text", 1f),
        dense = prefs.getBoolean("ap.dense", false),
        haptics = prefs.getBoolean("ap.haptics", true),
        reduceMotion = prefs.getBoolean("ap.motion", false),
        accent = prefs.getLong("ap.accent", 0),
        hideAmounts = prefs.getBoolean("ap.hide", false),
    )

    fun setAppearance(a: Appearance) {
        prefs.edit().putString("ap.theme", a.theme.name).putFloat("ap.text", a.textScale).putBoolean("ap.dense", a.dense)
            .putBoolean("ap.haptics", a.haptics).putBoolean("ap.motion", a.reduceMotion).putLong("ap.accent", a.accent)
            .putBoolean("ap.hide", a.hideAmounts).apply()
        _appearance.value = a
    }

    private val _security = MutableStateFlow(readSecurity())
    val security: StateFlow<SecurityPrefs> = _security

    private fun readSecurity() = SecurityPrefs(
        lockEnabled = prefs.getBoolean("sec.lock", false),
        biometric = prefs.getBoolean("sec.bio", true),
        autoLockSeconds = prefs.getInt("sec.auto", 30),
        blockScreenshots = prefs.getBoolean("sec.shots", false),
        pinHash = prefs.getString("sec.pin", "").orEmpty(),
        duressPinHash = prefs.getString("sec.duress", "").orEmpty(),
        duressProfileId = prefs.getString("sec.duressProfile", null),
    )

    fun setSecurity(s: SecurityPrefs) {
        prefs.edit().putBoolean("sec.lock", s.lockEnabled).putBoolean("sec.bio", s.biometric).putInt("sec.auto", s.autoLockSeconds)
            .putBoolean("sec.shots", s.blockScreenshots).putString("sec.pin", s.pinHash).putString("sec.duress", s.duressPinHash)
            .putString("sec.duressProfile", s.duressProfileId).apply()
        _security.value = s
    }

    fun flag(key: String): Boolean = prefs.getBoolean("flag.$key", false)
    fun setFlag(key: String, value: Boolean = true) { prefs.edit().putBoolean("flag.$key", value).apply() }
    fun string(key: String): String? = prefs.getString("s.$key", null)
    fun setString(key: String, value: String?) { prefs.edit().putString("s.$key", value).apply() }
    /** SE-05: Penny is showing the decoy book because the duress PIN was used. */
    fun inDuress(): Boolean = _security.value.duressProfileId?.let { it == _profileId.value } == true

    fun long(key: String): Long = prefs.getLong("l.$key", 0)
    fun setLong(key: String, value: Long) { prefs.edit().putLong("l.$key", value).apply() }

    /** Analytics opt-out (SE-10): counts only, and only when this is true. Default off until the user opts in. */
    var analyticsOptIn: Boolean
        get() = prefs.getBoolean("analytics", false)
        set(v) { prefs.edit().putBoolean("analytics", v).apply() }

    fun clearAll() {
        prefs.edit().clear().apply()
        _profileId.value = null
        _appearance.value = Appearance()
        _security.value = SecurityPrefs()
    }

    private companion object {
        const val KEY_PROFILE = "current_profile"
        const val KEY_DEVICE = "device_id"
        const val KEY_ACCOUNT = "local_account_id"
    }
}

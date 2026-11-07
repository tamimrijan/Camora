package com.tamimrijan.camora.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.tamimrijan.camora.camera.AspectSetting
import com.tamimrijan.camora.camera.FlashSetting
import com.tamimrijan.camora.engine.ColorProfileId
import com.tamimrijan.camora.engine.HdrMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

enum class PhotoFormat(val label: String, val mime: String, val ext: String) {
    JPEG("JPEG", "image/jpeg", "jpg"), HEIF("HEIF (smaller files)", "image/heic", "heic")
}

enum class Level(val label: String, val factor: Float) { LOW("Low", 0.55f), NATURAL("Natural", 1f), HIGH("High", 1.4f) }

enum class ThemeSetting(val label: String) { SYSTEM("System default"), LIGHT("Light"), DARK("Dark") }

data class AppSettings(
    // look & processing
    val profile: ColorProfileId = ColorProfileId.NATURAL,
    val profileIntensity: Float = 1f,
    val hdr: HdrMode = HdrMode.AUTO,
    val faceExposure: Boolean = true,
    val detail: Level = Level.NATURAL,
    val noiseReduction: Level = Level.NATURAL,
    val naturalPreview: Boolean = true,
    // portrait & selfie
    val beautySmoothing: Float = 0f,
    val beautyEvenTone: Float = 0f,
    val faceLight: Float = 0.6f,
    val portraitBlur: Float = 0.6f,
    val mirrorFront: Boolean = true,
    // camera & photo
    val aspect: AspectSetting = AspectSetting.R4_3,
    val photoResolution: String = "",  // "WxH" or "" for best standard size
    val highRes: Boolean = false,
    val format: PhotoFormat = PhotoFormat.JPEG,
    val jpegQuality: Int = 95,
    val shutterSound: Boolean = true,
    val grid: Boolean = false,
    val volumeKeyShutter: Boolean = true,
    val flash: FlashSetting = FlashSetting.OFF,
    // app
    val theme: ThemeSetting = ThemeSetting.SYSTEM,
    val dynamicColor: Boolean = false,
    val onboardingDone: Boolean = false,
)

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsRepository(private val context: Context) {
    private object K {
        val profile = stringPreferencesKey("profile")
        val intensity = floatPreferencesKey("intensity")
        val hdr = stringPreferencesKey("hdr")
        val faceExposure = booleanPreferencesKey("face_exposure")
        val detail = stringPreferencesKey("detail")
        val nr = stringPreferencesKey("nr")
        val naturalPreview = booleanPreferencesKey("natural_preview")
        val smoothing = floatPreferencesKey("smoothing")
        val evenTone = floatPreferencesKey("even_tone")
        val faceLight = floatPreferencesKey("face_light")
        val portraitBlur = floatPreferencesKey("portrait_blur")
        val mirror = booleanPreferencesKey("mirror_front")
        val aspect = stringPreferencesKey("aspect")
        val photoRes = stringPreferencesKey("photo_res")
        val highRes = booleanPreferencesKey("high_res")
        val format = stringPreferencesKey("format")
        val quality = intPreferencesKey("quality")
        val sound = booleanPreferencesKey("shutter_sound")
        val grid = booleanPreferencesKey("grid")
        val volume = booleanPreferencesKey("volume_keys")
        val flash = stringPreferencesKey("flash")
        val theme = stringPreferencesKey("theme")
        val dynamic = booleanPreferencesKey("dynamic_color")
        val onboarding = booleanPreferencesKey("onboarding_done")
    }

    private inline fun <reified E : Enum<E>> Preferences.enum(key: Preferences.Key<String>, def: E): E =
        this[key]?.let { v -> enumValues<E>().firstOrNull { it.name == v } } ?: def

    val settings: Flow<AppSettings> = context.dataStore.data.map { read(it) }.distinctUntilChanged()

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        context.dataStore.edit { p ->
            val n = transform(read(p))
            p[K.profile] = n.profile.name
            p[K.intensity] = n.profileIntensity
            p[K.hdr] = n.hdr.name
            p[K.faceExposure] = n.faceExposure
            p[K.detail] = n.detail.name
            p[K.nr] = n.noiseReduction.name
            p[K.naturalPreview] = n.naturalPreview
            p[K.smoothing] = n.beautySmoothing
            p[K.evenTone] = n.beautyEvenTone
            p[K.faceLight] = n.faceLight
            p[K.portraitBlur] = n.portraitBlur
            p[K.mirror] = n.mirrorFront
            p[K.aspect] = n.aspect.name
            p[K.photoRes] = n.photoResolution
            p[K.highRes] = n.highRes
            p[K.format] = n.format.name
            p[K.quality] = n.jpegQuality
            p[K.sound] = n.shutterSound
            p[K.grid] = n.grid
            p[K.volume] = n.volumeKeyShutter
            p[K.flash] = n.flash.name
            p[K.theme] = n.theme.name
            p[K.dynamic] = n.dynamicColor
            p[K.onboarding] = n.onboardingDone
        }
    }

    private fun read(p: Preferences): AppSettings {
        val d = AppSettings()
        return AppSettings(
            profile = p.enum(K.profile, d.profile), profileIntensity = p[K.intensity] ?: d.profileIntensity,
            hdr = p.enum(K.hdr, d.hdr), faceExposure = p[K.faceExposure] ?: d.faceExposure,
            detail = p.enum(K.detail, d.detail), noiseReduction = p.enum(K.nr, d.noiseReduction),
            naturalPreview = p[K.naturalPreview] ?: d.naturalPreview,
            beautySmoothing = p[K.smoothing] ?: d.beautySmoothing, beautyEvenTone = p[K.evenTone] ?: d.beautyEvenTone,
            faceLight = p[K.faceLight] ?: d.faceLight, portraitBlur = p[K.portraitBlur] ?: d.portraitBlur,
            mirrorFront = p[K.mirror] ?: d.mirrorFront,
            aspect = p.enum(K.aspect, d.aspect), photoResolution = p[K.photoRes] ?: d.photoResolution,
            highRes = p[K.highRes] ?: d.highRes, format = p.enum(K.format, d.format), jpegQuality = p[K.quality] ?: d.jpegQuality,
            shutterSound = p[K.sound] ?: d.shutterSound, grid = p[K.grid] ?: d.grid, volumeKeyShutter = p[K.volume] ?: d.volumeKeyShutter,
            flash = p.enum(K.flash, d.flash), theme = p.enum(K.theme, d.theme), dynamicColor = p[K.dynamic] ?: d.dynamicColor,
            onboardingDone = p[K.onboarding] ?: d.onboardingDone,
        )
    }
}

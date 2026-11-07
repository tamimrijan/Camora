package com.tamimrijan.camora

import android.app.Application
import com.tamimrijan.camora.data.MediaRepository
import com.tamimrijan.camora.data.SettingsRepository
import com.tamimrijan.camora.processing.CapturePipeline
import com.tamimrijan.camora.camera.CameraMode
import kotlinx.coroutines.flow.MutableStateFlow

/** Tiny service locator; the app is small enough not to need a DI framework. */
class CamoraApp : Application() {
    val settings by lazy { SettingsRepository(this) }
    val media by lazy { MediaRepository(this) }
    val pipeline by lazy { CapturePipeline(this, media) }
    val updater by lazy { com.tamimrijan.camora.data.UpdateManager(this) }

    /** Camera mode asked for by a widget or app shortcut; the camera applies it, then clears it. */
    val launchMode = MutableStateFlow<CameraMode?>(null)

    /** True while Camora is shown over the lock screen: only the camera is open, everything else asks to unlock. */
    val lockedSession = MutableStateFlow(false)

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    companion object {
        lateinit var instance: CamoraApp
            private set

        const val DEVELOPER = "Tamim Ahmed Rijan"
        const val GITHUB = "https://github.com/tamimrijan"
        const val INSTAGRAM = "https://www.instagram.com/tamim_ahmed_rijan/"
    }
}

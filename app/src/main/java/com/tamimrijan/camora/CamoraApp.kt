package com.tamimrijan.camora

import android.app.Application
import com.tamimrijan.camora.data.MediaRepository
import com.tamimrijan.camora.data.SettingsRepository
import com.tamimrijan.camora.processing.CapturePipeline

/** Tiny service locator; the app is small enough not to need a DI framework. */
class CamoraApp : Application() {
    val settings by lazy { SettingsRepository(this) }
    val media by lazy { MediaRepository(this) }
    val pipeline by lazy { CapturePipeline(this, media) }

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

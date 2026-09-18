package org.perchance.app

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate

/**
 * Perchance is a browser-like app, so it follows the system light/dark setting
 * rather than the (deprecated) app-local night mode toggle.
 */
class PerchanceApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
    }
}

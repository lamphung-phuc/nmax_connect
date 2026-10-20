package com.lampp.nmaxconnect

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.lampp.nmaxconnect.core.bike.BikeLinkRepository
import com.lampp.nmaxconnect.core.bike.hasBluetoothPermission
import com.lampp.nmaxconnect.core.places.SharedLocationInbox
import com.lampp.nmaxconnect.core.settings.AppSettingsRepository
import com.lampp.nmaxconnect.core.settings.AppTheme
import com.lampp.nmaxconnect.ui.navigation.NmaxApp
import com.lampp.nmaxconnect.ui.theme.NmaxConnectTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var sharedLocations: SharedLocationInbox

    @Inject lateinit var settingsRepository: AppSettingsRepository

    @Inject lateinit var linkRepository: BikeLinkRepository

    private val themeFlow by lazy { settingsRepository.settings.map { it.theme } }

    /** Settings (onboarding done, theme) are read; until then the app has nothing to draw. */
    @Volatile private var settingsRead = false

    override fun onCreate(savedInstanceState: Bundle?) {
        // The wordmark stays up until the first screen is known, instead of a blank frame.
        installSplashScreen().setKeepOnScreenCondition { !settingsRead }
        super.onCreate(savedInstanceState)
        lifecycleScope.launch {
            settingsRepository.settings.first()
            settingsRead = true
        }
        // Only a fresh launch: after a rotation or process restore the same intent was already taken.
        if (savedInstanceState == null) {
            offerShared(intent)
            autoConnect()
        }
        setContent {
            val theme by themeFlow.collectAsStateWithLifecycle(AppTheme.SYSTEM)
            val dark = when (theme) {
                AppTheme.SYSTEM -> isSystemInDarkTheme()
                AppTheme.LIGHT -> false
                AppTheme.DARK -> true
            }
            // Status / nav bar icons follow the app theme, not the system one.
            DisposableEffect(dark) {
                val style = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark }
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                onDispose {}
            }
            NmaxConnectTheme(darkTheme = dark) { NmaxApp() }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        offerShared(intent)
    }

    /** Without Bluetooth permission the Home card asks for it; the service would fail to start anyway. */
    private fun autoConnect() = lifecycleScope.launch {
        val settings = settingsRepository.settings.first()
        val bike = settings.bike
        if (settings.autoConnect && bike != null && hasBluetoothPermission(this@MainActivity)) linkRepository.start(bike.mac)
    }

    /** Google Maps "Share" sends text with a link; "Open with" / other apps send a `geo:` URI. */
    private fun offerShared(intent: Intent) = when (intent.action) {
        Intent.ACTION_SEND -> sharedLocations.offer(intent.getStringExtra(Intent.EXTRA_TEXT))
        Intent.ACTION_VIEW -> sharedLocations.offer(intent.dataString)
        else -> Unit
    }
}

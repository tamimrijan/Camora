package com.tamimrijan.camora

import android.Manifest
import android.app.KeyguardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.tamimrijan.camora.data.AppSettings
import com.tamimrijan.camora.ui.camera.CameraScreen
import com.tamimrijan.camora.ui.camera.CameraViewModel
import com.tamimrijan.camora.ui.editor.EditorScreen
import com.tamimrijan.camora.ui.editor.EditorViewModel
import com.tamimrijan.camora.ui.gallery.GalleryScreen
import com.tamimrijan.camora.ui.gallery.GalleryViewModel
import com.tamimrijan.camora.ui.gallery.ViewerScreen
import com.tamimrijan.camora.ui.settings.CapabilitiesScreen
import com.tamimrijan.camora.ui.settings.SettingsScreen
import com.tamimrijan.camora.ui.theme.CamoraTheme
import com.tamimrijan.camora.ui.theme.CameraTheme
import com.tamimrijan.camora.ui.theme.isAppDark
import com.tamimrijan.camora.camera.CameraMode
import com.tamimrijan.camora.widget.Launch
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.receiveAsFlow

class MainActivity : ComponentActivity() {
    private val shutterKeys = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    @Volatile private var volumeShutter = true
    @Volatile private var onCamera = true

    /** Screens asked for by a widget or shortcut, delivered once each. */
    private val navRequests = Channel<String>(Channel.BUFFERED)
    private val app get() = application as CamoraApp

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        updateLockScreen()
        if (savedInstanceState == null) handleLaunch(intent)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        setContent {
            val app = application as CamoraApp
            // null until DataStore has loaded, so returning users never see the tour flash.
            val loaded by app.settings.settings.collectAsStateWithLifecycle(null)
            val settings = loaded ?: AppSettings()
            volumeShutter = settings.volumeKeyShutter
            CamoraTheme(settings.theme, settings.dynamicColor) {
                when {
                    loaded == null -> Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black))
                    !settings.onboardingDone -> com.tamimrijan.camora.ui.onboarding.WelcomeTour(
                        onFinish = { lifecycleScope.launch { app.settings.update { it.copy(onboardingDone = true) } } },
                    )
                    else -> PermissionGate { AppNav(settings) }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        updateLockScreen()
        handleLaunch(intent)
    }

    override fun onResume() {
        super.onResume()
        // Unlocked while Camora was open over the lock screen: back to a normal session.
        val km = getSystemService(KeyguardManager::class.java)
        if (app.lockedSession.value && km?.isKeyguardLocked == false) {
            app.lockedSession.value = false
            setShowWhenLocked(false)
        }
    }

    /**
     * Opened from the lock screen (Samsung lock screen shortcut, side key, lock screen widget or
     * the secure camera intent): show the camera over the lock screen without unlocking. The
     * manifest declares showWhenLocked so the system knows this before launching and doesn't ask
     * for the PIN first. Photos already on the phone stay private until the phone is unlocked:
     * only the camera is reachable, and the gallery, editor and settings ask to unlock.
     * Opened normally (phone unlocked): behave like any app, so locking the phone hides Camora.
     */
    private fun updateLockScreen() {
        val km = getSystemService(KeyguardManager::class.java) ?: return
        if (km.isKeyguardLocked) {
            setShowWhenLocked(true)
            if (!app.lockedSession.value) {
                app.lockedSession.value = true
                navRequests.trySend("camera") // never resume on the gallery over the lock screen
            }
        } else {
            setShowWhenLocked(false)
            app.lockedSession.value = false
        }
    }

    private fun handleLaunch(i: Intent?) {
        i ?: return
        val mode = i.getStringExtra(Launch.EXTRA_MODE)?.let { m -> CameraMode.entries.firstOrNull { it.name == m } }
        val open = i.getStringExtra(Launch.EXTRA_OPEN)
        if (mode != null) {
            app.launchMode.value = mode
            navRequests.trySend("camera")
        }
        when (open) {
            Launch.OPEN_LATEST -> navRequests.trySend("viewer/0")
            Launch.OPEN_GALLERY -> navRequests.trySend("gallery")
        }
        // Don't re-apply the same request after a configuration change or process restore.
        i.removeExtra(Launch.EXTRA_MODE)
        i.removeExtra(Launch.EXTRA_OPEN)
    }

    /** Runs [then] right away, or after the user unlocks when Camora is open over the lock screen. */
    private fun requireUnlock(then: () -> Unit) {
        val km = getSystemService(KeyguardManager::class.java)
        if (!app.lockedSession.value || km == null || !km.isKeyguardLocked) {
            app.lockedSession.value = false
            then()
            return
        }
        km.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
            override fun onDismissSucceeded() {
                app.lockedSession.value = false
                setShowWhenLocked(false)
                then()
            }
        })
    }

    @Composable
    private fun AppNav(settings: AppSettings) {
        val nav = rememberNavController()
        val gallery: GalleryViewModel = viewModel()
        val darkApp = isAppDark(settings.theme)
        LaunchedEffect(Unit) {
            navRequests.receiveAsFlow().collect { route ->
                if (route == "camera") nav.popBackStack("camera", inclusive = false)
                else requireUnlock {
                    nav.popBackStack("camera", inclusive = false)
                    nav.navigate(route)
                }
            }
        }
        NavHost(
            nav, startDestination = "camera",
            enterTransition = { fadeIn() }, exitTransition = { fadeOut() },
            popEnterTransition = { fadeIn() }, popExitTransition = { fadeOut() },
        ) {
            composable("camera") {
                SystemBars(lightIcons = true, keepScreenOn = true)
                val vm: CameraViewModel = viewModel()
                DisposableEffect(Unit) {
                    onCamera = true
                    onDispose { onCamera = false }
                }
                CameraTheme {
                    CameraScreen(
                        vm = vm,
                        shutterKeys = shutterKeys,
                        onOpenLatest = { requireUnlock { nav.navigate("viewer/0") } },
                        onOpenGallery = { requireUnlock { nav.navigate("gallery") } },
                        onOpenSettings = { requireUnlock { nav.navigate("settings") } },
                    )
                }
            }
            composable("gallery") {
                SystemBars(lightIcons = darkApp)
                GalleryScreen(
                    gallery, onBack = { nav.popBackStack() },
                    onOpen = { i -> nav.navigate("viewer/$i") },
                    onEdit = { uri -> nav.navigate("editor?uri=" + Uri.encode(uri.toString())) },
                )
            }
            composable("viewer/{index}", arguments = listOf(navArgument("index") { type = NavType.IntType })) { e ->
                SystemBars(lightIcons = true)
                CameraTheme {
                    ViewerScreen(
                        gallery, e.arguments?.getInt("index") ?: 0,
                        onBack = { if (!nav.popBackStack()) nav.navigate("gallery") },
                        onEdit = { uri -> nav.navigate("editor?uri=" + Uri.encode(uri.toString())) },
                    )
                }
            }
            composable(
                "editor?uri={uri}",
                arguments = listOf(navArgument("uri") { type = NavType.StringType; nullable = true; defaultValue = null }),
            ) { e ->
                SystemBars(lightIcons = true)
                val vm: EditorViewModel = viewModel()
                val uri = e.arguments?.getString("uri")?.let { Uri.parse(it) }
                CameraTheme {
                    if (uri != null) EditorScreen(vm, uri, onBack = { nav.popBackStack() })
                }
            }
            composable("settings") {
                SystemBars(lightIcons = darkApp)
                SettingsScreen(onBack = { nav.popBackStack() }, onOpenReport = { nav.navigate("report") })
            }
            composable("report") {
                SystemBars(lightIcons = darkApp)
                CapabilitiesScreen(onBack = { nav.popBackStack() })
            }
        }
    }

    @Composable
    private fun SystemBars(lightIcons: Boolean, keepScreenOn: Boolean = false) {
        DisposableEffect(lightIcons, keepScreenOn) {
            val c = WindowCompat.getInsetsController(window, window.decorView)
            c.isAppearanceLightStatusBars = !lightIcons
            c.isAppearanceLightNavigationBars = !lightIcons
            if (keepScreenOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            onDispose { if (keepScreenOn) window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (onCamera && volumeShutter && (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP)) {
            if (event.repeatCount == 0) shutterKeys.tryEmit(Unit)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }
}

/** Asks for the camera permission (the only one Camora needs) with a clear explanation. */
@Composable
private fun PermissionGate(content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    fun granted() = ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    var hasCamera by remember { mutableStateOf(granted()) }
    var asked by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        hasCamera = ok || granted()
        asked = true
    }
    LaunchedEffect(Unit) { if (!hasCamera) launcher.launch(Manifest.permission.CAMERA) }
    if (hasCamera) {
        content()
        return
    }
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 420.dp).padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Image(painterResource(R.drawable.camora_logo), "Camora logo", Modifier.size(96.dp))
            Spacer(Modifier.height(24.dp))
            Text("Welcome to Camora", color = MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.Center, style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(10.dp))
            Text(
                "Camora needs camera access to take photos. Everything is processed on this phone and saved to your gallery. Nothing is uploaded.",
                color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyLarge,
            )
            Spacer(Modifier.height(28.dp))
            Button(onClick = {
                if (asked) ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", ctx.packageName, null)))
                else launcher.launch(Manifest.permission.CAMERA)
            }) { Text(if (asked) "Open app settings" else "Allow camera") }
            if (asked) {
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = { hasCamera = granted() }) { Text("I've allowed it") }
            }
        }
    }
}

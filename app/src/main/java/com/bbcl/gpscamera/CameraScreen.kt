// The main screen: camera preview with the live stamp, GPS status and capture controls, in
// photo or video mode.
package com.bbcl.gpscamera

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.HapticFeedbackConstants
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Business
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.FlashAuto
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.GpsNotFixed
import androidx.compose.material.icons.filled.LocationDisabled
import androidx.compose.material.icons.filled.LocationOff
import androidx.compose.material.icons.filled.NoPhotography
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay

private val Accent = Color(STAMP_ACCENT)

@Composable
fun CameraScreen(model: CameraModel, onOpenGallery: () -> Unit) {
    val context = LocalContext.current
    val view = LocalView.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val gps by model.gps.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var editingBrand by remember { mutableStateOf(false) }
    var firstLaunchBrand by rememberSaveable { mutableStateOf(false) }
    var showAbout by remember { mutableStateOf(false) }

    // Camera and location first; the branding prompt follows so the dialogs don't overlap.
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        model.bind()
        model.gps.start()
        if (model.brand.isEmpty && !brandPromptShown(context)) {
            firstLaunchBrand = true
            editingBrand = true
        }
    }
    val audioPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}

    LaunchedEffect(Unit) {
        val wanted = buildList {
            add(Manifest.permission.CAMERA)
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        permissions.launch(wanted.toTypedArray())
    }
    LaunchedEffect(Unit) {
        while (true) {
            model.tick()
            delay(1000)
        }
    }
    LaunchedEffect(gps) { model.onPosition(gps) }
    LaunchedEffect(model.message) {
        model.message?.let {
            snackbar.showSnackbar(it)
            model.message = null
        }
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> model.gps.start()
                Lifecycle.Event.ON_STOP -> {
                    if (model.isRecording) model.stopRecording()
                    model.gps.stop()
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            TopBar(
                gps = gps,
                flash = model.flash,
                flashEnabled = model.cameraReady,
                brandSet = !model.brand.isEmpty,
                brandEnabled = !model.isRecording,
                onGps = {
                    val intent = if (gps.status == GpsStatus.DISABLED) Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
                    else appSettings(context.packageName)
                    context.startActivity(intent)
                },
                onFlash = model::cycleFlash,
                onAbout = { showAbout = true },
                onBrand = { editingBrand = true },
            )
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Preview(model, lifecycleOwner)
            }
            ModeSwitch(model.videoMode, enabled = !model.isRecording) { video ->
                if (video && context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    audioPermission.launch(Manifest.permission.RECORD_AUDIO)
                }
                model.setMode(video)
            }
            Row(
                Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 20.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Thumbnail(model, onClick = { if (!model.isRecording) onOpenGallery() })
                Shutter(model.videoMode, model.isRecording, model.capturing) {
                    view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    model.shutter()
                }
                FilledTonalIconButton(
                    onClick = model::switchCamera,
                    enabled = model.canSwitch && !model.isRecording,
                    modifier = Modifier.size(52.dp),
                ) {
                    Icon(Icons.Filled.Cameraswitch, contentDescription = "Switch camera", modifier = Modifier.size(28.dp))
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = 120.dp))
    }

    if (editingBrand) {
        BrandDialog(
            current = model.brand,
            firstLaunch = firstLaunchBrand,
            onDismiss = {
                if (firstLaunchBrand) markBrandPromptShown(context)
                firstLaunchBrand = false
                editingBrand = false
            },
            onSave = {
                model.updateBrand(it)
                if (firstLaunchBrand) markBrandPromptShown(context)
                firstLaunchBrand = false
                editingBrand = false
            },
        )
    }
    if (showAbout) AboutDialog { showAbout = false }
}

fun appSettings(packageName: String) =
    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))

@Composable
private fun TopBar(
    gps: GpsState,
    flash: Flash,
    flashEnabled: Boolean,
    brandSet: Boolean,
    brandEnabled: Boolean,
    onGps: () -> Unit,
    onFlash: () -> Unit,
    onAbout: () -> Unit,
    onBrand: () -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) { GpsChip(gps, onGps) }
        IconButton(onClick = onFlash, enabled = flashEnabled) {
            Icon(
                when (flash) {
                    Flash.AUTO -> Icons.Filled.FlashAuto
                    Flash.ON -> Icons.Filled.FlashOn
                    Flash.OFF -> Icons.Filled.FlashOff
                },
                contentDescription = "Flash: ${flash.name.lowercase()}",
                tint = Color.White,
            )
        }
        IconButton(onClick = onAbout) { Icon(Icons.Outlined.Info, contentDescription = "About", tint = Color.White) }
        IconButton(onClick = onBrand, enabled = brandEnabled) {
            Icon(Icons.Filled.Business, contentDescription = "Company, event and note", tint = if (brandSet) Accent else Color.White)
        }
    }
}

@Composable
private fun GpsChip(gps: GpsState, onTap: () -> Unit) {
    val green = Color(0xFF69F0AE)
    val orange = Color(0xFFFF9800)
    val red = Color(0xFFFF5252)
    val (icon, color, text) = when (gps.status) {
        GpsStatus.FIXED -> Triple(Icons.Filled.GpsFixed, green, "GPS ±${gps.position!!.accuracy.toInt()} m")
        GpsStatus.SEARCHING, GpsStatus.STARTING -> Triple(Icons.Filled.GpsNotFixed, orange, "Searching GPS…")
        GpsStatus.DISABLED -> Triple(Icons.Filled.LocationOff, red, "Location is off — tap to turn on")
        GpsStatus.PERMISSION_DENIED -> Triple(Icons.Filled.LocationDisabled, red, "Location permission needed — tap")
    }
    val actionable = gps.status == GpsStatus.DISABLED || gps.status == GpsStatus.PERMISSION_DENIED
    Row(
        Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(color.copy(alpha = 0.15f))
            .border(1.dp, color.copy(alpha = 0.6f), RoundedCornerShape(20.dp))
            .then(if (actionable) Modifier.clickable(onClick = onTap) else Modifier)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(text, color = color, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun Preview(model: CameraModel, lifecycleOwner: androidx.lifecycle.LifecycleOwner) {
    val context = LocalContext.current
    val error = model.cameraError
    if (error != null) {
        Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Filled.NoPhotography, contentDescription = null, tint = Color.White.copy(alpha = 0.55f), modifier = Modifier.size(48.dp))
            Spacer(Modifier.height(12.dp))
            Text(error, color = Color.White, textAlign = TextAlign.Center)
            Spacer(Modifier.height(12.dp))
            Button(onClick = { context.startActivity(appSettings(context.packageName)) }) { Text("Open app settings") }
            TextButton(onClick = model::bind) { Text("Try again") }
        }
        return
    }
    // The photo has exactly the preview's framing; the stamp is drawn to the same scale.
    // As large as fits both ways: a 9:16 video frame is often taller than the space left.
    Box(Modifier.aspectRatio(if (model.videoMode) 9f / 16f else 3f / 4f)) {
        AndroidView(
            factory = { ctx ->
                PreviewView(ctx).apply {
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                    implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                    model.attach(lifecycleOwner, this)
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
        if (!model.cameraReady) CircularProgressIndicator(Modifier.align(Alignment.Center), color = Color.White)

        @Suppress("UNUSED_VARIABLE") val tick = model.now // redraw the stamp every second
        val info = model.stampNow()
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val density = LocalDensity.current
            val w = constraints.maxWidth.toFloat()
            val stampH = with(density) { stampHeight(info, w).toDp() }
            val eventH = with(density) { eventStripHeight(info, w).toDp() }
            Canvas(Modifier.fillMaxWidth().height(stampH).align(Alignment.BottomCenter)) {
                drawIntoCanvas { paintStamp(it.nativeCanvas, size.width, size.height, info) }
            }
            if (eventH > 0.dp) {
                Canvas(Modifier.fillMaxWidth().height(eventH).align(Alignment.TopStart)) {
                    drawIntoCanvas { paintEventBadge(it.nativeCanvas, size.width, info) }
                }
            }
        }
        if (model.isRecording) RecordingChip(model.elapsedMs, Modifier.align(Alignment.TopCenter).padding(top = 10.dp))
        val overlay by animateFloatAsState(if (model.flashOverlay) 0.7f else 0f, label = "flash")
        if (overlay > 0f) Box(Modifier.fillMaxSize().background(Color.White.copy(alpha = overlay)))
    }
}

@Composable
private fun RecordingChip(elapsedMs: Long, modifier: Modifier) {
    val s = elapsedMs / 1000
    val text = if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%02d:%02d".format(s / 60, s % 60)
    Row(
        modifier.clip(RoundedCornerShape(16.dp)).background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 12.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Circle, contentDescription = null, tint = Accent, modifier = Modifier.size(10.dp))
        Spacer(Modifier.width(6.dp))
        Text(text, color = Color.White, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun ModeSwitch(video: Boolean, enabled: Boolean, onSelect: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.Center) {
        listOf("PHOTO" to false, "VIDEO" to true).forEach { (label, v) ->
            val selected = video == v
            TextButton(onClick = { onSelect(v) }, enabled = enabled) {
                Text(
                    label,
                    color = if (selected) Color(0xFFFFC107) else Color.White.copy(alpha = 0.7f),
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                    letterSpacing = 1.2.sp,
                )
            }
        }
    }
}

@Composable
private fun Shutter(video: Boolean, recording: Boolean, capturing: Boolean, onClick: () -> Unit) {
    // A red square while recording, like other camera apps.
    val inner by animateDpAsState(if (recording) 30.dp else 60.dp, label = "shutter")
    val radius by animateDpAsState(if (recording) 6.dp else 30.dp, label = "radius")
    val color = when {
        video -> Accent
        capturing -> Color.White.copy(alpha = 0.55f)
        else -> Color.White
    }
    Box(
        Modifier
            .size(78.dp)
            .clip(CircleShape)
            .border(4.dp, Color.White, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(inner).clip(RoundedCornerShape(radius)).background(color))
    }
}

@Composable
private fun Thumbnail(model: CameraModel, onClick: () -> Unit) {
    val context = LocalContext.current
    val file = model.lastMedia
    Box(
        Modifier
            .size(52.dp)
            .clip(RoundedCornerShape(10.dp))
            .border(2.dp, Color.White.copy(alpha = 0.7f), RoundedCornerShape(10.dp))
            .background(Color.White.copy(alpha = 0.1f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        val image = file?.let { rememberThumb(Media.coverImage(context, it), 160) }
        if (image != null) {
            Image(image, contentDescription = "Open photos and videos", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Icon(Icons.Outlined.PhotoLibrary, contentDescription = "Open photos and videos", tint = Color.White.copy(alpha = 0.7f))
        }
        if (file != null && Media.isVideo(file)) {
            Icon(
                Icons.Filled.PlayCircle,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.align(Alignment.BottomEnd).padding(2.dp).size(18.dp),
            )
        }
        if (model.pending > 0) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(22.dp), color = Color.White, strokeWidth = 2.5.dp)
            }
        }
    }
}

@Composable
private fun AboutDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        icon = { Icon(Icons.Filled.GpsFixed, contentDescription = null, tint = Accent) },
        title = { Text("$APP_NAME 1.0.0") },
        text = {
            Text(
                "Stamps your photos and videos with the address, a satellite mini map, the GPS position " +
                    "and the time. The address and the map need an internet connection; without one, the " +
                    "stamp still has the coordinates and time.\n\n" +
                    "Satellite imagery: $MAP_ATTRIBUTION (Esri World Imagery). " +
                    "Addresses: the phone's built-in Android geocoder, or OpenStreetMap Nominatim " +
                    "(© OpenStreetMap contributors, ODbL). Plus codes: Open Location Code (open source).",
            )
        },
    )
}

// State and actions behind the camera screen: CameraX in photo or video mode, the live stamp
// (GPS, address, mini map, branding) and the background queue that turns captures into the
// final photos and videos, one after another, so the user can keep shooting.
package com.bbcl.gpscamera

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.location.Location
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Size
import androidx.camera.core.AspectRatio
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.LifecycleOwner
import java.io.File
import java.util.concurrent.Executors

enum class Flash { OFF, AUTO, ON }

class CameraModel(context: Context) {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val processor = Executors.newSingleThreadExecutor()
    private val io = Executors.newFixedThreadPool(2)

    val gps = LocationTracker(app)

    // ---- camera ----
    var videoMode by mutableStateOf(false)
        private set
    var lensFacing by mutableIntStateOf(CameraSelector.LENS_FACING_BACK)
        private set
    var flash by mutableStateOf(Flash.OFF)
        private set
    var cameraError by mutableStateOf<String?>(null)
        private set
    var cameraReady by mutableStateOf(false)
        private set
    var canSwitch by mutableStateOf(false)
        private set
    var capturing by mutableStateOf(false)
        private set
    var flashOverlay by mutableStateOf(false)
        private set
    var pending by mutableIntStateOf(0)
        private set
    var lastMedia by mutableStateOf<File?>(null)
    var message by mutableStateOf<String?>(null)

    private var owner: LifecycleOwner? = null
    private var previewView: PreviewView? = null
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null

    // ---- video ----
    private var recording: Recording? = null
    private var track: StampTrack? = null
    private var recordStart = 0L
    var isRecording by mutableStateOf(false)
        private set
    var elapsedMs by mutableLongStateOf(0)
        private set

    // ---- stamp ----
    var now by mutableLongStateOf(System.currentTimeMillis())
        private set
    var brand by mutableStateOf(Brand())
        private set
    var logo by mutableStateOf<Bitmap?>(null)
        private set
    private var map by mutableStateOf<Bitmap?>(null)
    private var address by mutableStateOf<Address?>(null)
    private val maps = MiniMapLoader()
    private var mapAt: Location? = null
    private var addressAt: Location? = null
    private var addressTime = 0L
    private var mapLoading = false
    private var addressLoading = false

    init {
        brand = loadBrand(app)
        logo = decodeLogo(brand.logoPath)
        lastMedia = Media.list(app).firstOrNull()
    }

    // ---------------------------------------------------------------------------------------
    // Camera
    // ---------------------------------------------------------------------------------------

    fun attach(owner: LifecycleOwner, view: PreviewView) {
        this.owner = owner
        previewView = view
        bind()
    }

    /** (Re)binds CameraX for the current mode and lens. */
    fun bind() {
        val owner = owner ?: return
        val view = previewView ?: return
        if (app.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            cameraError = "Camera permission is needed."
            return
        }
        val future = ProcessCameraProvider.getInstance(app)
        future.addListener({
            try {
                val provider = future.get()
                provider.unbindAll()
                cameraReady = false
                val ratio = if (videoMode) AspectRatio.RATIO_16_9 else AspectRatio.RATIO_4_3
                val aspect = AspectRatioStrategy(ratio, AspectRatioStrategy.FALLBACK_RULE_AUTO)
                val back = CameraSelector.DEFAULT_BACK_CAMERA
                val front = CameraSelector.DEFAULT_FRONT_CAMERA
                canSwitch = provider.hasCamera(back) && provider.hasCamera(front)
                var selector = if (lensFacing == CameraSelector.LENS_FACING_FRONT) front else back
                if (!provider.hasCamera(selector)) selector = if (selector == back) front else back
                if (!provider.hasCamera(selector)) {
                    cameraError = "No camera found on this phone."
                    return@addListener
                }
                val preview = Preview.Builder()
                    .setResolutionSelector(ResolutionSelector.Builder().setAspectRatioStrategy(aspect).build())
                    .build()
                    .also { it.surfaceProvider = view.surfaceProvider }
                camera = if (videoMode) {
                    // 1080p keeps stamping the video quick.
                    val recorder = Recorder.Builder()
                        .setQualitySelector(QualitySelector.from(Quality.FHD, FallbackStrategy.lowerQualityOrHigherThan(Quality.FHD)))
                        .build()
                    val vc = VideoCapture.withOutput(recorder)
                    videoCapture = vc
                    imageCapture = null
                    provider.bindToLifecycle(owner, selector, preview, vc)
                } else {
                    // Up to 12 MP: sharp, and small enough to stamp in memory on any phone.
                    val ic = ImageCapture.Builder()
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                        .setResolutionSelector(
                            ResolutionSelector.Builder()
                                .setAspectRatioStrategy(aspect)
                                .setResolutionStrategy(
                                    ResolutionStrategy(Size(4000, 3000), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)
                                )
                                .build()
                        )
                        .setFlashMode(flashModeFor(flash))
                        .build()
                    imageCapture = ic
                    videoCapture = null
                    provider.bindToLifecycle(owner, selector, preview, ic)
                }
                applyTorch()
                cameraError = null
                cameraReady = true
            } catch (e: Exception) {
                cameraError = "Could not start the camera (${e.message})."
            }
        }, app.mainExecutor)
    }

    fun switchCamera() {
        if (!canSwitch || isRecording) return
        lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
        bind()
    }

    fun setMode(video: Boolean) {
        if (video == videoMode || isRecording) return
        videoMode = video
        flash = Flash.OFF
        bind()
    }

    fun cycleFlash() {
        // Videos can only use the flash as a steady light.
        val order = if (videoMode) listOf(Flash.OFF, Flash.ON) else listOf(Flash.OFF, Flash.AUTO, Flash.ON)
        flash = order[(order.indexOf(flash) + 1) % order.size]
        imageCapture?.flashMode = flashModeFor(flash)
        applyTorch()
    }

    private fun applyTorch() {
        if (camera?.cameraInfo?.hasFlashUnit() != true) return
        camera?.cameraControl?.enableTorch(videoMode && flash == Flash.ON)
    }

    private fun flashModeFor(f: Flash) = when (f) {
        Flash.OFF -> ImageCapture.FLASH_MODE_OFF
        Flash.AUTO -> ImageCapture.FLASH_MODE_AUTO
        Flash.ON -> ImageCapture.FLASH_MODE_ON
    }

    // ---------------------------------------------------------------------------------------
    // Capture
    // ---------------------------------------------------------------------------------------

    fun shutter() {
        when {
            !videoMode -> takePhoto()
            isRecording -> stopRecording()
            else -> startRecording()
        }
    }

    private fun takePhoto() {
        val ic = imageCapture ?: return
        if (capturing || !cameraReady) return
        capturing = true
        flashOverlay = true
        main.postDelayed({ flashOverlay = false }, 120)
        val stamp = stampNow()
        ic.takePicture(app.mainExecutor, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                val buffer = image.planes[0].buffer
                val bytes = ByteArray(buffer.remaining()).also(buffer::get)
                val rotation = image.imageInfo.rotationDegrees
                image.close()
                capturing = false
                pending++
                processor.execute {
                    try {
                        val file = Media.savePhoto(app, bytes, rotation, stamp)
                        main.post { lastMedia = file }
                    } catch (e: Throwable) {
                        main.post { message = "Could not save the photo: ${e.message}" }
                    } finally {
                        main.post { pending-- }
                    }
                }
            }

            override fun onError(exception: ImageCaptureException) {
                capturing = false
                message = "Could not take the photo (${exception.message})."
            }
        })
    }

    @SuppressLint("MissingPermission") // audio is only enabled when the permission is granted
    private fun startRecording() {
        val vc = videoCapture ?: return
        if (isRecording || !cameraReady) return
        val raw = File(app.cacheDir, "rec_${System.currentTimeMillis()}.mp4")
        val t = StampTrack(app)
        val pendingRecording = vc.output.prepareRecording(app, FileOutputOptions.Builder(raw).build()).apply {
            if (app.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) withAudioEnabled()
        }
        try {
            recording = pendingRecording.start(app.mainExecutor) { event ->
                if (event is VideoRecordEvent.Finalize) finishRecording(event, raw, t)
            }
        } catch (e: Exception) {
            t.discard()
            message = "Could not start recording (${e.message})."
            return
        }
        track = t
        recordStart = SystemClock.elapsedRealtime()
        elapsedMs = 0
        t.add(stampNow(), 0)
        isRecording = true
    }

    fun stopRecording() {
        recording?.stop()
        recording = null
        track = null
        isRecording = false
    }

    private fun finishRecording(event: VideoRecordEvent.Finalize, raw: File, t: StampTrack) {
        if (event.hasError() && (!raw.exists() || raw.length() == 0L)) {
            t.discard()
            raw.delete()
            message = "Could not finish the recording (error ${event.error})."
            return
        }
        pending++
        processor.execute {
            try {
                val out = File(Media.photosDir(app), Media.fileName(t.started) + ".mp4")
                t.burnInto(app, raw, out, Media.thumbFor(app, out))
                Media.toGallery(app, out, video = true)
                main.post { lastMedia = out }
            } catch (e: Throwable) {
                main.post { message = "Could not save the video: ${e.message}" }
            } finally {
                t.discard()
                raw.delete()
                main.post { pending-- }
            }
        }
    }

    // ---------------------------------------------------------------------------------------
    // Stamp
    // ---------------------------------------------------------------------------------------

    /** Called every second: keeps the time on the stamp ticking and the video's stamp up to date. */
    fun tick() {
        now = System.currentTimeMillis()
        if (isRecording) {
            elapsedMs = SystemClock.elapsedRealtime() - recordStart
            track?.add(stampNow(), elapsedMs)
        }
    }

    fun stampNow(): StampInfo {
        val g = gps.state.value
        val p = if (g.hasFix) g.position else null
        return StampInfo(
            latitude = p?.latitude,
            longitude = p?.longitude,
            altitude = p?.takeIf { it.hasAltitude() }?.altitude,
            time = System.currentTimeMillis(),
            note = brand.note,
            title = if (p == null) null else address?.title,
            addressLine = if (p == null) null else address?.line,
            inIndia = address?.countryCode == "IN",
            map = if (p == null) null else map,
            company = brand.company,
            logo = logo,
            event = brand.event,
        )
    }

    /**
     * Fetches a new mini map after 25 m of movement, and a new address after 40 m (or two
     * minutes, in case the first lookup failed for lack of signal).
     */
    fun onPosition(state: GpsState) {
        val p = state.position?.takeIf { state.hasFix } ?: return
        if (!mapLoading && (map == null || (mapAt?.distanceTo(p) ?: Float.MAX_VALUE) > 25)) {
            mapLoading = true
            io.execute {
                val img = maps.load(p.latitude, p.longitude)
                main.post {
                    mapLoading = false
                    if (img != null) {
                        map = img
                        mapAt = p
                    }
                }
            }
        }
        val stale = System.currentTimeMillis() - addressTime > 2 * 60_000
        if (!addressLoading && ((addressAt?.distanceTo(p) ?: Float.MAX_VALUE) > 40 || (address == null && stale))) {
            addressLoading = true
            addressTime = System.currentTimeMillis()
            io.execute {
                val a = lookupAddress(app, p.latitude, p.longitude)
                main.post {
                    addressLoading = false
                    if (a != null) {
                        address = a
                        addressAt = p
                    }
                }
            }
        }
    }

    /** Shows the new branding on the stamp straight away and keeps it on the phone. */
    fun updateBrand(b: Brand) {
        if (b.logoPath != brand.logoPath) logo = decodeLogo(b.logoPath)
        brand = b
        saveBrand(app, b)
    }

    fun refreshLastMedia() {
        lastMedia = Media.list(app).firstOrNull()
    }
}

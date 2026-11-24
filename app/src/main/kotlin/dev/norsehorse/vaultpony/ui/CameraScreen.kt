package dev.norsehorse.vaultpony.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.ZoomState
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FileDescriptorOutputOptions
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.Observer
import dev.norsehorse.vaultpony.CameraCapture
import dev.norsehorse.vaultpony.CameraZoom
import dev.norsehorse.vaultpony.R
import dev.norsehorse.vaultpony.i18n.findActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uniffi.vault_ffi.VaultSession
import java.util.Date
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.roundToInt

/*
 * The in-app camera (2.0.0 plan 5.6, D10): photos and videos straight into
 * the vault folder the browser shows, through CameraCapture. CAMERA is asked
 * for when the camera first opens, RECORD_AUDIO only when Video is chosen.
 * Nothing is written to the gallery, MediaStore or the app's cache.
 *
 * Zoom and focus (2.1.0 plan 1): pinch on the preview or tap a zoom step,
 * within the range the bound camera reports; tap the preview to focus there.
 */

/** Finalize results whose file the muxer completed: kept. Anything else
 *  (encoder failure, no data, invalid output...) is discarded. A recording
 *  ended by the camera closing (SOURCE_INACTIVE) or by the size limit is
 *  still a complete file. */
private val KEEP_FINALIZE = setOf(
    VideoRecordEvent.Finalize.ERROR_NONE,
    VideoRecordEvent.Finalize.ERROR_SOURCE_INACTIVE,
    VideoRecordEvent.Finalize.ERROR_FILE_SIZE_LIMIT_REACHED,
    VideoRecordEvent.Finalize.ERROR_DURATION_LIMIT_REACHED,
)

// How long a "Saved to" or warning line stays up.
private const val NOTE_MS = 2_500L

// How long the zoom readout stays after a pinch, and the focus ring after a tap.
private const val ZOOM_READOUT_MS = 1_200L
private const val FOCUS_RING_MS = 1_000L

// A step counts as the current zoom within this much of its ratio.
private const val ZOOM_STEP_SLACK = 0.05f

private fun granted(context: Context, permission: String): Boolean =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

/**
 * Full-screen camera writing into the vault folder [dir] of [session].
 * [folderLabel] names it in "Saved to %1$s". [startVideo]: open in Video.
 * Stays open for more captures until [onClose]. A lock takes the browser,
 * and this screen with it, away: the recording stops, and because the vault
 * is no longer open its in-memory file is closed without being copied in
 * (CameraCapture.finishVideo).
 */
@Composable
internal fun CameraScreen(
    session: VaultSession,
    dir: String,
    folderLabel: String,
    startVideo: Boolean,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val lifecycleOwner = remember(context) { context.findActivity() as? LifecycleOwner }
    val savedTo = stringResource(R.string.camera_saved_to, folderLabel)
    val videoFailed = stringResource(R.string.camera_video_failed)
    val limitReached = stringResource(R.string.camera_limit_reached)
    val noSound = stringResource(R.string.camera_no_sound)
    val failed = stringResource(R.string.common_op_failed)

    var video by rememberSaveable { mutableStateOf(startVideo && CameraCapture.videoSupported) }
    var front by rememberSaveable { mutableStateOf(false) }
    var hasPermission by remember { mutableStateOf(granted(context, Manifest.permission.CAMERA)) }
    var denied by remember { mutableStateOf(false) }
    var audioAsked by rememberSaveable { mutableStateOf(false) }
    var noSoundSaid by rememberSaveable { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    var noteSeq by remember { mutableIntStateOf(0) }
    var provider by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    var hasBack by remember { mutableStateOf(true) }
    var hasFront by remember { mutableStateOf(false) }
    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    var videoCapture by remember { mutableStateOf<VideoCapture<Recorder>?>(null) }
    // The bound camera: its control sets zoom and focus. Null while unbound.
    var camera by remember { mutableStateOf<Camera?>(null) }
    // The zoom ratio asked for. Kept across Photo and Video, 1x after a
    // switch between the front and back camera.
    var zoom by rememberSaveable { mutableFloatStateOf(1f) }
    var zoomMin by remember { mutableFloatStateOf(1f) }
    var zoomMax by remember { mutableFloatStateOf(1f) }
    // Counts every time the camera starts over at 1x: a new binding, and the
    // app coming back to the foreground.
    var bindSeq by remember { mutableIntStateOf(0) }
    var pinchSeq by remember { mutableIntStateOf(0) }
    var pinching by remember { mutableStateOf(false) }
    var focusAt by remember { mutableStateOf<Offset?>(null) }
    var focusSeq by remember { mutableIntStateOf(0) }
    var recording by remember { mutableStateOf<Recording?>(null) }
    // A photo being taken or a recording being set up.
    var busy by remember { mutableStateOf(false) }
    // False once this screen is gone; a recording set up after that is dropped.
    val alive = remember { AtomicBoolean(true) }
    val previewView = remember {
        PreviewView(context).apply {
            // A TextureView inside the window, so "Hide screen contents"
            // (FLAG_SECURE) covers the preview too.
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }

    fun say(text: String) {
        note = text
        noteSeq++
    }

    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        hasPermission = ok
        denied = !ok
    }
    val audioPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (!ok && !noSoundSaid) {
            noSoundSaid = true
            say(noSound)
        }
    }

    // The camera permission when the camera first opens...
    LaunchedEffect(Unit) {
        if (!hasPermission) cameraPermission.launch(Manifest.permission.CAMERA)
    }
    // ...and the microphone only once Video is chosen (D10).
    LaunchedEffect(video, hasPermission) {
        if (video && hasPermission && !audioAsked && !granted(context, Manifest.permission.RECORD_AUDIO)) {
            audioAsked = true
            audioPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
    LaunchedEffect(noteSeq) {
        if (note != null) {
            delay(NOTE_MS)
            note = null
        }
    }

    // The camera provider, once the permission is there.
    DisposableEffect(hasPermission) {
        var disposed = false
        if (hasPermission) {
            val future = ProcessCameraProvider.getInstance(context)
            future.addListener({
                if (disposed) return@addListener
                val p = runCatching { future.get() }.getOrNull()
                if (p == null) {
                    say(failed)
                    return@addListener
                }
                hasBack = runCatching { p.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) }.getOrDefault(false)
                hasFront = runCatching { p.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) }.getOrDefault(false)
                provider = p
            }, ContextCompat.getMainExecutor(context))
        }
        onDispose { disposed = true }
    }

    // Preview plus the use case of the chosen mode, bound to the activity:
    // CameraX closes the camera when the app leaves the foreground and opens
    // it again on return.
    DisposableEffect(provider, video, front, lifecycleOwner) {
        val p = provider
        val owner = lifecycleOwner
        if (p == null || owner == null) return@DisposableEffect onDispose {}
        val selector = if ((front && hasFront) || !hasBack) {
            CameraSelector.DEFAULT_FRONT_CAMERA
        } else {
            CameraSelector.DEFAULT_BACK_CAMERA
        }
        val preview = Preview.Builder().build()
        preview.setSurfaceProvider(previewView.surfaceProvider)
        try {
            p.unbindAll()
            if (video) {
                val vc = VideoCapture.withOutput(Recorder.Builder().build())
                camera = p.bindToLifecycle(owner, selector, preview, vc)
                videoCapture = vc
                imageCapture = null
            } else {
                val ic = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build()
                camera = p.bindToLifecycle(owner, selector, preview, ic)
                imageCapture = ic
                videoCapture = null
            }
            bindSeq++
        } catch (e: Exception) {
            camera = null
            imageCapture = null
            videoCapture = null
            say(failed)
        }
        onDispose {
            camera = null
            p.unbindAll()
        }
    }

    // The zoom range of the camera just bound. A new binding starts at 1x,
    // and so does the camera after the app was in the background, so the
    // ratio asked for is set again once, inside the range. CameraX hands back
    // the same Camera for the same lens, hence bindSeq as a key. The ratio
    // the camera reports at that moment can still be the one from before its
    // reset, so it is only trusted when 1x is what is wanted.
    DisposableEffect(camera, bindSeq, lifecycleOwner) {
        val cam = camera
        val owner = lifecycleOwner
        if (cam == null || owner == null) return@DisposableEffect onDispose {}
        var applied = false
        val observer = Observer<ZoomState> { state ->
            zoomMin = state.minZoomRatio
            zoomMax = maxOf(state.minZoomRatio, state.maxZoomRatio)
            if (!applied) {
                applied = true
                val wanted = zoom.coerceIn(zoomMin, zoomMax)
                zoom = wanted
                if (wanted != 1f || abs(wanted - state.zoomRatio) > 0.005f) {
                    cam.cameraControl.setZoomRatio(wanted)
                }
            }
        }
        val live = cam.cameraInfo.zoomState
        live.observe(owner, observer)
        onDispose { live.removeObserver(observer) }
    }

    LaunchedEffect(pinchSeq) {
        if (pinchSeq > 0) {
            pinching = true
            delay(ZOOM_READOUT_MS)
            pinching = false
        }
    }
    LaunchedEffect(focusSeq) {
        if (focusAt != null) {
            delay(FOCUS_RING_MS)
            focusAt = null
        }
    }

    // App to the background: a recording stops and is saved if the vault is
    // still unlocked when it is finished. Back from the system settings with
    // the camera allowed: open it.
    DisposableEffect(lifecycleOwner) {
        val owner = lifecycleOwner ?: return@DisposableEffect onDispose {}
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                recording?.stop()
            } else if (event == Lifecycle.Event.ON_START) {
                bindSeq++
            } else if (event == Lifecycle.Event.ON_RESUME &&
                !hasPermission && granted(context, Manifest.permission.CAMERA)
            ) {
                hasPermission = true
                denied = false
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }

    // Leaving the camera (close, back, or a lock taking the browser away)
    // stops a recording; its Finalize event saves or discards it.
    DisposableEffect(Unit) {
        onDispose {
            alive.set(false)
            recording?.stop()
        }
    }

    fun close() {
        recording?.stop()
        onClose()
    }

    // False when there is no camera or it has no zoom range.
    fun setZoom(ratio: Float): Boolean {
        val cam = camera ?: return false
        if (zoomMax <= zoomMin) return false
        val next = ratio.coerceIn(zoomMin, zoomMax)
        zoom = next
        cam.cameraControl.setZoomRatio(next)
        return true
    }

    // Focus and exposure on the tapped point of the preview. Cameras that
    // can't meter on a point (many front cameras) show no ring.
    fun focusOn(at: Offset) {
        val cam = camera ?: return
        runCatching {
            val point = previewView.meteringPointFactory.createPoint(at.x, at.y)
            val action = FocusMeteringAction.Builder(point)
                .setAutoCancelDuration(3, TimeUnit.SECONDS)
                .build()
            if (cam.cameraInfo.isFocusMeteringSupported(action)) {
                cam.cameraControl.startFocusAndMetering(action)
                focusAt = at
                focusSeq++
            }
        }
    }

    fun takePhoto() {
        val ic = imageCapture ?: return
        if (busy) return
        busy = true
        val at = Date()
        // In memory only: no OutputFileOptions, which would write a temp
        // file in the app's cache.
        ic.takePicture(
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    val rotation = image.imageInfo.rotationDegrees
                    val jpeg = try {
                        val buffer = image.planes[0].buffer
                        buffer.rewind()
                        ByteArray(buffer.remaining()).also { buffer.get(it) }
                    } finally {
                        image.close()
                    }
                    CameraCapture.scope.launch {
                        val result = runCatching {
                            CameraCapture.savePhoto(session, dir, CameraCapture.uprightJpeg(jpeg, rotation), at)
                        }
                        jpeg.fill(0)
                        withContext(Dispatchers.Main) {
                            busy = false
                            result.onSuccess { say(savedTo) }.onFailure {
                                say(vaultFailureText(context.resources, it, failed, session, "camera photo"))
                            }
                        }
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    busy = false
                    say(failed)
                }
            },
        )
    }

    // RECORD_AUDIO is checked right here; without it the video has no sound.
    @SuppressLint("MissingPermission")
    fun startRecording() {
        val vc = videoCapture ?: return
        if (busy || recording != null || !CameraCapture.videoSupported) return
        busy = true
        val started = Date()
        val withSound = granted(context, Manifest.permission.RECORD_AUDIO)
        CameraCapture.scope.launch {
            val opened = runCatching { CameraCapture.openVideo(appContext, session) }
            withContext(Dispatchers.Main) {
                busy = false
                val sink = opened.getOrElse {
                    say(vaultFailureText(context.resources, it, videoFailed, session, "camera video"))
                    return@withContext
                }
                if (!alive.get() || videoCapture !== vc) {
                    CameraCapture.scope.launch { CameraCapture.finishVideo(sink, dir, started, keep = false) }
                    return@withContext
                }
                try {
                    val options = FileDescriptorOutputOptions.Builder(sink.pfd)
                        .setFileSizeLimit(sink.sizeLimit)
                        .build()
                    var pending = vc.output.prepareRecording(appContext, options)
                    if (withSound) pending = pending.withAudioEnabled()
                    recording = pending.start(ContextCompat.getMainExecutor(appContext)) { event ->
                        if (event is VideoRecordEvent.Finalize) {
                            recording = null
                            val keep = event.error in KEEP_FINALIZE
                            val atLimit = event.error == VideoRecordEvent.Finalize.ERROR_FILE_SIZE_LIMIT_REACHED
                            CameraCapture.scope.launch {
                                val name = CameraCapture.finishVideo(sink, dir, started, keep)
                                withContext(Dispatchers.Main) {
                                    say(
                                        when {
                                            name == null -> videoFailed
                                            atLimit -> limitReached
                                            else -> savedTo
                                        },
                                    )
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    CameraCapture.scope.launch { CameraCapture.finishVideo(sink, dir, started, keep = false) }
                    say(videoFailed)
                }
            }
        }
    }

    BackHandler { close() }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (hasPermission) {
            AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
            // Over the preview and under the controls, the same size as the
            // preview, so a tap's position is a position in the preview.
            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) { detectTapGestures(onTap = { focusOn(it) }) }
                    .pointerInput(Unit) {
                        detectTransformGestures { _, _, factor, _ ->
                            if (factor != 1f && setZoom(zoom * factor)) pinchSeq++
                        }
                    },
            )
            focusAt?.let { at ->
                Box(
                    Modifier
                        .align(AbsoluteAlignment.TopLeft)
                        .absoluteOffset {
                            val r = 32.dp.toPx()
                            IntOffset((at.x - r).roundToInt(), (at.y - r).roundToInt())
                        }
                        .size(64.dp)
                        .border(1.dp, Color.White, CircleShape),
                )
            }
        } else if (denied) {
            Column(
                Modifier.align(Alignment.Center).padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    stringResource(R.string.camera_denied),
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodyLarge,
                )
                Button(onClick = {
                    val intent = Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", context.packageName, null),
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    runCatching { context.startActivity(intent) }
                }) { Text(stringResource(R.string.camera_open_settings)) }
            }
        }

        IconButton(onClick = { close() }, modifier = Modifier.align(Alignment.TopStart).padding(8.dp)) {
            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.camera_close), tint = Color.White)
        }

        note?.let {
            Text(
                it,
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(start = 56.dp, end = 56.dp, top = 16.dp)
                    .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(10.dp))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }

        if (hasPermission) {
            val idle = recording == null && !busy
            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(bottom = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                val locale = context.resources.configuration.locales[0]
                val steps = remember(zoomMin, zoomMax) { CameraZoom.steps(zoomMin, zoomMax) }
                if (pinching) {
                    Text(
                        stringResource(R.string.camera_zoom_step, CameraZoom.number(zoom, locale)),
                        color = Color.White,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier
                            .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(10.dp))
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                    )
                    Spacer(Modifier.height(8.dp))
                }
                if (steps.size > 1) {
                    Row(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        steps.forEach { step ->
                            val label = stringResource(R.string.camera_zoom_step, CameraZoom.number(step, locale))
                            ZoomStep(
                                label = label,
                                description = stringResource(R.string.camera_zoom_desc, label),
                                selected = abs(zoom - step) < ZOOM_STEP_SLACK,
                            ) { setZoom(step) }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ModeTab(stringResource(R.string.camera_photo), selected = !video, enabled = idle) { video = false }
                    if (CameraCapture.videoSupported) {
                        ModeTab(stringResource(R.string.camera_video), selected = video, enabled = idle) { video = true }
                    }
                }
                Spacer(Modifier.height(18.dp))
                Box(Modifier.fillMaxWidth()) {
                    val rec = recording != null
                    IconButton(
                        onClick = {
                            when {
                                !video -> takePhoto()
                                rec -> recording?.stop()
                                else -> startRecording()
                            }
                        },
                        enabled = rec || !busy,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .size(76.dp)
                            .background(Color.White.copy(alpha = 0.92f), CircleShape),
                    ) {
                        Icon(
                            when {
                                !video -> Icons.Filled.PhotoCamera
                                rec -> Icons.Filled.Stop
                                else -> Icons.Filled.FiberManualRecord
                            },
                            contentDescription = stringResource(
                                when {
                                    !video -> R.string.camera_take_photo
                                    rec -> R.string.common_stop
                                    else -> R.string.camera_record_video
                                },
                            ),
                            tint = if (video) Color(0xFFD32F2F) else Color.Black,
                            modifier = Modifier.size(36.dp),
                        )
                    }
                    if (hasBack && hasFront) {
                        IconButton(
                            onClick = {
                                zoom = 1f
                                front = !front
                            },
                            enabled = idle,
                            modifier = Modifier.align(Alignment.CenterEnd).padding(end = 28.dp),
                        ) {
                            Icon(
                                Icons.Filled.Cameraswitch,
                                contentDescription = stringResource(R.string.camera_switch),
                                tint = Color.White,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** "Photo" / "Video". */
@Composable
private fun ModeTab(label: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Text(
        label,
        color = if (selected) Color.Black else Color.White,
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier
            .background(
                if (selected) Color.White else Color.Black.copy(alpha = 0.45f),
                RoundedCornerShape(16.dp),
            )
            .selectable(selected = selected, enabled = enabled, role = Role.Tab, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

/** One zoom step, "1x": a 48dp target around a 40dp disc, which widens
 *  rather than clips its label at large font sizes. */
@Composable
private fun ZoomStep(label: String, description: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .heightIn(min = 48.dp)
            .widthIn(min = 48.dp)
            .clip(CircleShape)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .height(40.dp)
                .widthIn(min = 40.dp)
                .background(if (selected) Color.White else Color.Black.copy(alpha = 0.45f), CircleShape)
                .padding(horizontal = 4.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                label,
                color = if (selected) Color.Black else Color.White,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.clearAndSetSemantics {},
            )
        }
    }
}

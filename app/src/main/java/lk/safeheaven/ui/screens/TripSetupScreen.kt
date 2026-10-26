package lk.safeheaven.ui.screens

import android.graphics.Bitmap
import android.location.Geocoder
import android.widget.Toast
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.AspectRatio
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import lk.safeheaven.core.PanicState
import lk.safeheaven.core.EvidenceChain
import lk.safeheaven.core.Geo
import lk.safeheaven.core.LatLng
import lk.safeheaven.core.PlateNormalizer
import lk.safeheaven.data.Prefs
import lk.safeheaven.net.RouteClient
import lk.safeheaven.net.SupabaseApi
import lk.safeheaven.ocr.ImageUtil
import lk.safeheaven.ocr.PlateScanner
import lk.safeheaven.trip.Gpx
import lk.safeheaven.trip.LocationHub
import lk.safeheaven.trip.ReplayController
import lk.safeheaven.trip.TripBus
import lk.safeheaven.trip.TripService
import lk.safeheaven.trip.TripSession
import lk.safeheaven.ui.components.GhostButton
import lk.safeheaven.ui.components.PrimaryButton
import lk.safeheaven.ui.components.ShCard
import lk.safeheaven.ui.theme.Sh
import java.io.File
import java.util.UUID

@Composable
fun TripSetupScreen(onStarted: () -> Unit, onCancel: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var photo by remember { mutableStateOf<Bitmap?>(null) }
    var jpeg by remember { mutableStateOf<ByteArray?>(null) }
    var plate by remember { mutableStateOf("") }
    var ocrNote by remember { mutableStateOf("") }
    var scanning by remember { mutableStateOf(false) }
    var dest by remember { mutableStateOf("") }
    var starting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    if (photo == null) {
        CameraStage(
            scanning = scanning,
            onCaptured = { bmp ->
                scanning = true
                scope.launch {
                    val cropped = ImageUtil.cropToGuide(bmp)
                    jpeg = withContext(Dispatchers.Default) { ImageUtil.toJpeg(cropped) }
                    photo = cropped
                    runCatching { PlateScanner.scan(cropped) }
                        .onSuccess { s ->
                            plate = s.result.display ?: PlateNormalizer.display(s.result.candidate)
                            ocrNote = if (s.result.plate != null) "Read “${s.rawText.replace('\n', ' ').trim()}”. Check it matches the plate."
                            else "Couldn't read a valid Sri Lankan plate. Type it in below."
                            if (s.result.plate == null) plate = ""
                        }
                        .onFailure { ocrNote = "OCR failed (${it.message}). Type the plate in below."; plate = "" }
                    scanning = false
                }
            },
            onCancel = onCancel,
        )
        return
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Confirm your trip", style = MaterialTheme.typography.headlineLarge)
        Image(photo!!.asImageBitmap(), "Plate", Modifier.fillMaxWidth().height(110.dp), contentScale = ContentScale.Fit)
        Text(ocrNote, style = MaterialTheme.typography.bodySmall, color = Sh.Muted)
        OutlinedTextField(
            plate, { plate = it.uppercase() }, label = { Text("Vehicle plate (edit if wrong)") }, singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Sh.Teal, unfocusedBorderColor = Sh.Outline, focusedLabelColor = Sh.Teal),
        )
        if (ReplayController.enabled) {
            ShCard {
                Text("Route Replay is ON", style = MaterialTheme.typography.titleMedium, color = Sh.Amber)
                Text("The destination and route come from the demo GPX (${ReplayController.scenario.label}).", style = MaterialTheme.typography.bodySmall, color = Sh.Muted)
            }
        } else {
            OutlinedTextField(
                dest, { dest = it }, label = { Text("Destination (address, or lat,lon)") }, singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Sh.Teal, unfocusedBorderColor = Sh.Outline, focusedLabelColor = Sh.Teal),
            )
        }
        error?.let { Text(it, color = Sh.Danger, style = MaterialTheme.typography.bodyMedium) }
        PrimaryButton(
            if (starting) "Starting…" else "Start trip",
            enabled = !starting && PlateNormalizer.isAcceptable(plate) && (ReplayController.enabled || dest.isNotBlank()),
        ) {
            starting = true; error = null
            scope.launch {
                runCatching { startTrip(ctx, PlateNormalizer.clean(plate), jpeg!!, dest) }
                    .onSuccess {
                        // Give the service a moment to leave IDLE before the active screen appears.
                        withTimeoutOrNull(3_000) { TripBus.state.first { it != PanicState.IDLE } }
                        onStarted()
                    }
                    .onFailure { error = it.message ?: "Couldn't start the trip"; starting = false }
            }
        }
        GhostButton("Retake photo") { photo = null; plate = ""; ocrNote = "" }
        GhostButton("Cancel", onClick = onCancel)
    }
}

/** Route fetch, Supabase upload, "Trip started" text, then TripService (activity is on screen). */
private suspend fun startTrip(ctx: android.content.Context, plateClean: String, jpeg: ByteArray, destInput: String) {
    val prefs = Prefs.get(ctx)
    val api = SupabaseApi.get(ctx)
    val tripId = UUID.randomUUID().toString()

    val destination: LatLng
    val label: String
    val polyline: String
    val eta: Long
    if (ReplayController.enabled) {
        ReplayController.load(ctx)
        val planned = ReplayController.plannedRoute()
        destination = LatLng(planned.last().lat, planned.last().lon)
        label = "Demo destination"
        polyline = Geo.encodePolyline(Gpx.toLatLng(planned))
        eta = ReplayController.plannedEtaSeconds()
    } else {
        destination = resolveDestination(ctx, destInput) ?: error("Couldn't find that destination. Try “6.9271, 79.8612”.")
        label = destInput
        val here = LocationHub.lastKnown(ctx) ?: error("Waiting for a GPS fix — step outside or enable Location, then retry.")
        val route = RouteClient.fetch(LatLng(here.lat, here.lon), destination)
        polyline = route.polyline
        eta = route.etaSeconds
    }

    // Plate photo = evidence item h0.
    val photoFile = withContext(Dispatchers.IO) {
        File(ctx.filesDir, "trips/$tripId").apply { mkdirs() }.resolve("plate.jpg").also { it.writeBytes(jpeg) }
    }
    val h0 = EvidenceChain.hex(EvidenceChain.h0(jpeg))
    val session = TripSession(
        tripId = tripId, plate = plateClean, photoPath = photoFile.absolutePath, h0Hex = h0,
        destination = destination, destinationLabel = label, polyline = polyline, etaSeconds = eta,
        startMs = System.currentTimeMillis(),
    )

    // Upload { plate, photo, route_polyline, eta } and notify contacts. Offline is non-fatal.
    if (api.configured && api.isOnline()) {
        runCatching {
            api.ensureRegistered()
            val path = api.uploadPlatePhoto(tripId, jpeg)
            api.insertTrip(
                tripId, plateClean, path, destination.lat, destination.lon, polyline,
                java.time.Instant.now().plusSeconds(eta).toString(),
            )
            val here = LocationHub.lastKnown(ctx)
            api.notifyContacts("trip_started", plateClean, here?.lat, here?.lon)
            TripBus.log("trip uploaded, contacts notified")
        }.onFailure {
            TripBus.log("trip upload failed: ${it.message}")
            withContext(Dispatchers.Main) { Toast.makeText(ctx, "Offline: trip not uploaded yet", Toast.LENGTH_LONG).show() }
        }
    }
    TripService.start(ctx, session)
}

@Suppress("DEPRECATION")
private suspend fun resolveDestination(ctx: android.content.Context, input: String): LatLng? {
    Regex("""^\s*(-?\d+(?:\.\d+)?)\s*,\s*(-?\d+(?:\.\d+)?)\s*$""").matchEntire(input)?.let {
        return LatLng(it.groupValues[1].toDouble(), it.groupValues[2].toDouble())
    }
    return withContext(Dispatchers.IO) {
        runCatching {
            Geocoder(ctx).getFromLocationName(input, 1)?.firstOrNull()?.let { LatLng(it.latitude, it.longitude) }
        }.getOrNull()
    }
}

@Composable
private fun CameraStage(scanning: Boolean, onCaptured: (Bitmap) -> Unit, onCancel: () -> Unit) {
    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val capture = remember {
        ImageCapture.Builder().setTargetAspectRatio(AspectRatio.RATIO_4_3)
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY).build()
    }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Scan the number plate", style = MaterialTheme.typography.headlineMedium)
        Text("Fit the plate inside the frame, then tap capture. Works offline.", style = MaterialTheme.typography.bodySmall, color = Sh.Muted)
        Box(Modifier.fillMaxWidth().aspectRatio(3f / 4f)) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { c ->
                    PreviewView(c).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }.also { pv ->
                        val future = ProcessCameraProvider.getInstance(c)
                        future.addListener({
                            val provider = future.get()
                            val preview = Preview.Builder().setTargetAspectRatio(AspectRatio.RATIO_4_3).build()
                                .also { it.setSurfaceProvider(pv.surfaceProvider) }
                            provider.unbindAll()
                            provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, capture)
                        }, ContextCompat.getMainExecutor(c))
                    }
                },
            )
            Canvas(Modifier.fillMaxSize()) {
                val w = size.width * ImageUtil.GUIDE_WIDTH
                val h = size.height * ImageUtil.GUIDE_HEIGHT
                val tl = Offset((size.width - w) / 2, (size.height - h) / 2)
                drawRoundRect(Color.Black.copy(alpha = 0.45f), Offset.Zero, Size(size.width, tl.y))
                drawRoundRect(Color.Black.copy(alpha = 0.45f), Offset(0f, tl.y + h), Size(size.width, size.height - tl.y - h))
                drawRoundRect(Sh.Teal, tl, Size(w, h), CornerRadius(16f, 16f), Stroke(width = 5f))
            }
        }
        PrimaryButton(if (scanning) "Reading plate…" else "Capture", enabled = !scanning) {
            capture.takePicture(
                ContextCompat.getMainExecutor(ctx),
                object : ImageCapture.OnImageCapturedCallback() {
                    override fun onCaptureSuccess(image: ImageProxy) {
                        val bmp = ImageUtil.upright(image)
                        image.close()
                        onCaptured(bmp)
                    }
                    override fun onError(exception: ImageCaptureException) {
                        Toast.makeText(ctx, "Capture failed: ${exception.message}", Toast.LENGTH_SHORT).show()
                    }
                },
            )
        }
        GhostButton("Cancel", onClick = onCancel)
    }
}

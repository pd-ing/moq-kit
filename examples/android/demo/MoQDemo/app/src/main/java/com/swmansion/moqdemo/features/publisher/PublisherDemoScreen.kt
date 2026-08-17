package com.swmansion.moqdemo.features.publisher

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.SurfaceTexture
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.TextureView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.swmansion.moqdemo.BuildConfig
import com.swmansion.moqkit.Session
import com.swmansion.moqkit.publish.PublishedTrackState
import com.swmansion.moqkit.publish.PublisherState
import com.swmansion.moqkit.publish.encoder.AudioCodec
import com.swmansion.moqkit.publish.encoder.VideoCodec
import com.swmansion.moqkit.publish.source.CameraPosition

@Composable
fun PublisherDemoScreen(
    initialRelayUrl: String,
    vm: PublisherViewModel = viewModel(),
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val context = LocalContext.current
    // Relay URL lives in the ViewModel (AND-V43-007): a screen-scoped
    // rememberSaveable is disposed when the Publisher screen is re-entered,
    // which reset the field to the public default mid-test session.
    LaunchedEffect(initialRelayUrl) { vm.initRelayUrl(initialRelayUrl) }
    val relayUrl = vm.relayUrl

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        // v4.11: POST_NOTIFICATIONS rides along for the broadcast keep-alive
        // FGS notification (Android 13+), but camera start must only require
        // the camera/mic pair — a denied notification prompt must not block
        // the preview (the FGS still runs, its notification just stays
        // hidden).
        val cameraGranted = results[Manifest.permission.CAMERA] != false &&
            results[Manifest.permission.RECORD_AUDIO] != false
        if (cameraGranted) vm.startCamera(lifecycleOwner)
    }

    LaunchedEffect(Unit) {
        vm.refreshMultiCameraSupport()
    }

    // OBS-V49-001: feed UI visibility into the reconnect loop — a full-screen
    // system interposition (FOTA install screen, 실기기 08-07) must PAUSE the
    // retry budget instead of burning it against timing-out background dials,
    // and returning to the foreground must wake the held run immediately.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> vm.onAppVisibilityChanged(true)
                Lifecycle.Event.ON_STOP -> vm.onAppVisibilityChanged(false)
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Keep the moqkit renderer's display-rotation compensation in sync with the
    // actual display so preview/encoder frames stay world-upright when the
    // device is used in landscape (the activity handles configChanges itself).
    val configuration = LocalConfiguration.current
    LaunchedEffect(configuration.orientation) {
        vm.onDisplayRotationChanged()
    }

    val screenCaptureLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            // Must start the foreground service here, inside the activity result callback.
            // Android 14+ enforces that mediaProjection foreground services are started
            // from the same callback that received the screen capture permission.
            context.startForegroundService(Intent(context, ScreenCaptureService::class.java))
            vm.setScreenProjection(result.resultCode, result.data!!)
        } else {
            vm.screenEnabled = false
        }
    }

    // Start camera preview when screen appears or camera configuration changes.
    LaunchedEffect(vm.cameraEnabled, vm.cameraSourceMode, vm.videoResolution, vm.videoFrameRate) {
        if (vm.cameraEnabled) {
            permissionLauncher.launch(
                if (Build.VERSION.SDK_INT >= 33) {
                    arrayOf(
                        Manifest.permission.CAMERA,
                        Manifest.permission.RECORD_AUDIO,
                        Manifest.permission.POST_NOTIFICATIONS,
                    )
                } else {
                    arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
                }
            )
        } else {
            vm.stopCamera()
        }
    }

    // Request screen capture permission when toggle is enabled
    LaunchedEffect(vm.screenEnabled) {
        if (vm.screenEnabled) {
            val manager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            screenCaptureLauncher.launch(manager.createScreenCaptureIntent())
        } else {
            vm.clearScreenProjection()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            // OBS-V49-002: safeDrawing (= system bars + display cutout + IME)
            // — with systemBars alone, landscape put controls under the camera
            // cutout / status bar and one automation pass could not reach
            // Stop/Publish without scrolling past clipped rows.
            .padding(WindowInsets.safeDrawing.asPaddingValues())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Connection controls
        ConnectionSection(
            vm = vm,
            relayUrl = relayUrl,
            onRelayUrlChange = { vm.relayUrl = it },
            lifecycleOwner = lifecycleOwner,
            permissionLauncher = { permissions ->
                permissionLauncher.launch(permissions)
            },
        )

        // Session state indicator
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(if (vm.isReconnecting) Color(0xFFFFA500) else stateColor(vm.sessionState))
            )
            Spacer(Modifier.width(8.dp))
            Text(vm.stateLabel, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        // Camera preview
        if (vm.cameraEnabled) {
            CameraPreviewCard(vm = vm)
        }

        // Config (when not publishing)
        if (!vm.isPublishing) {
            SourceConfigCard(vm = vm)
            CodecConfigCard(vm = vm)
        }

        // Publishing status (when publishing or reconnecting)
        if (vm.isPublishing || vm.isReconnecting || vm.publisherState == PublisherState.Stopped) {
            PublishingStatusCard(vm = vm)
        }

        // Audio capture death (2026-08-12): persistent by design — the whole
        // defect class is "the streamer does not know the mic is dead", so
        // this must not auto-dismiss while audio is down. Recovering shows
        // the bounded auto re-arm progress; exhausted offers a manual retry.
        if (vm.audioRecovering || vm.audioDead) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (vm.audioDead) {
                        Text(
                            "Microphone input stopped — viewers can't hear you. " +
                                "Automatic recovery failed " +
                                "(${vm.audioRearmAttempts}/${PublisherViewModel.MAX_AUDIO_REARM_ATTEMPTS}).",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                        Button(onClick = { vm.retryAudio(lifecycleOwner) }) {
                            Text("Retry audio")
                        }
                    } else {
                        Text(
                            "Microphone input stopped — recovering audio… " +
                                "(attempt ${vm.audioRearmAttempts}/${PublisherViewModel.MAX_AUDIO_REARM_ATTEMPTS})",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                }
            }
        }

        // Error banner
        vm.lastError?.let { error ->
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                    // AND-V48-001: a detected framing storm is process-scoped
                    // native corruption; the one verified remedy is an app
                    // process restart, so offer it right where the error is.
                    if (vm.stormDetected) {
                        Button(onClick = { restartAppProcess(context) }) {
                            Text("Restart app")
                        }
                    }
                }
            }
        }

        // Debug fault injection — always visible (unlike the config cards) so
        // the death->re-arm->banner chain can be driven MID-broadcast; the
        // toggle applies to the live mic immediately and to every new capture
        // session while ON.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                "Force mic failure (debug)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Switch(
                checked = vm.debugForceMicFailure,
                onCheckedChange = { vm.updateDebugForceMicFailure(it) },
            )
        }

        // Build identity — AND-V47-003: read from the INSTALLED package, not
        // BuildConfig. Gradle caching let BuildConfig constants go stale while
        // the manifest versionCode was fresh (08-06 재테스트: footer 26080408 vs
        // package 26080414), so the footer misidentified the build. PackageInfo
        // cannot disagree with the installed APK by construction, and the build
        // hour is derived from the versionCode itself (yyMMddHH UTC rule).
        val footerContext = LocalContext.current
        val footerIdentity = remember {
            runCatching {
                val code = footerContext.packageManager
                    .getPackageInfo(footerContext.packageName, 0).longVersionCode
                val c = code.toString().padStart(8, '0')
                val builtHour = "20${c.substring(0, 2)}-${c.substring(2, 4)}-${c.substring(4, 6)} ${c.substring(6, 8)}xxZ"
                "MoQDemo ${BuildConfig.APP_VERSION_NAME} ($code) · build hour $builtHour"
            }.getOrDefault(
                "MoQDemo ${BuildConfig.APP_VERSION_NAME} (${BuildConfig.APP_VERSION_CODE}) · built ${BuildConfig.BUILD_TIMESTAMP}",
            )
        }
        Text(
            footerIdentity,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )

        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun ConnectionSection(
    vm: PublisherViewModel,
    relayUrl: String,
    onRelayUrlChange: (String) -> Unit,
    lifecycleOwner: androidx.lifecycle.LifecycleOwner,
    permissionLauncher: (Array<String>) -> Unit,
) {
    Card {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Connection", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)

            OutlinedTextField(
                value = relayUrl,
                onValueChange = onRelayUrlChange,
                label = { Text("Relay URL") },
                singleLine = true,
                enabled = !vm.isPublishing,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = vm.broadcastPath,
                onValueChange = { vm.broadcastPath = it },
                label = { Text("Broadcast path") },
                singleLine = true,
                enabled = !vm.isPublishing,
                modifier = Modifier.fillMaxWidth(),
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        permissionLauncher(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
                        vm.publish(lifecycleOwner, relayUrl)
                    },
                    enabled = vm.canPublish && relayUrl.trim().isNotEmpty(),
                    modifier = Modifier.weight(1f),
                ) { Text("Publish") }

                OutlinedButton(
                    onClick = vm::stop,
                    enabled = vm.canStop,
                    modifier = Modifier.weight(1f),
                ) { Text("Stop") }
            }
        }
    }
}

@Composable
private fun CameraPreviewCard(vm: PublisherViewModel) {
    when (vm.cameraSourceMode) {
        CameraSourceMode.SingleCamera -> SingleCameraPreviewCard(vm)
        CameraSourceMode.MultiCamera -> MultiCameraPreviewCard(vm)
    }
}

/**
 * Preview container that matches the ENCODED frame's aspect (AND-V42-002): in
 * portrait the encode is 9:16, so a fixed 16:9 preview box would center-crop
 * away most of the vertical FOV and mislead the publisher about the framing
 * viewers actually see. While PUBLISHING the aspect is frozen to the active
 * encode (chosen at publish start) so a mid-broadcast rotation cannot make the
 * preview show a different framing than viewers get. Height-capped so a
 * portrait box stays on screen.
 */
@Composable
private fun previewBoxModifier(vm: PublisherViewModel): Modifier {
    val isPortrait = LocalConfiguration.current.orientation != Configuration.ORIENTATION_LANDSCAPE
    val aspect = vm.publishedVideoAspect ?: if (isPortrait) 9f / 16f else 16f / 9f
    return if (aspect < 1f) {
        Modifier
            .fillMaxWidth()
            .heightIn(max = 440.dp)
            .aspectRatio(aspect, matchHeightConstraintsFirst = true)
    } else {
        Modifier
            .fillMaxWidth()
            .aspectRatio(aspect)
    }
}

@Composable
private fun SingleCameraPreviewCard(vm: PublisherViewModel) {
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
    Box(
        modifier = previewBoxModifier(vm)
            .clip(RoundedCornerShape(12.dp))
            .background(Color.Black),
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                SurfaceView(context).apply {
                    holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(h: SurfaceHolder) = vm.setPreviewSurface(h.surface)
                        override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, height: Int) = Unit
                        override fun surfaceDestroyed(h: SurfaceHolder) = vm.setPreviewSurface(null)
                    })
                }
            },
        )
        // Flip camera button
        IconButton(
            onClick = vm::flipCamera,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(8.dp)
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.7f), CircleShape),
        ) {
            Icon(Icons.Default.Cameraswitch, contentDescription = "Flip camera")
        }
    }
    }
}

@Composable
private fun MultiCameraPreviewCard(vm: PublisherViewModel) {
    val mainPosition = vm.multiCameraMainPreviewPosition

    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
    Box(
        modifier = previewBoxModifier(vm)
            .clip(RoundedCornerShape(12.dp))
            .background(Color.Black),
    ) {
        MultiCameraTexture(
            position = CameraPosition.Front,
            vm = vm,
            modifier = multiCameraLayerModifier(
                isMain = mainPosition == CameraPosition.Front,
                onSwap = vm::swapMultiCameraPreview,
                pipAspect = pipAspect(vm),
            ),
        )

        MultiCameraTexture(
            position = CameraPosition.Back,
            vm = vm,
            modifier = multiCameraLayerModifier(
                isMain = mainPosition == CameraPosition.Back,
                onSwap = vm::swapMultiCameraPreview,
                pipAspect = pipAspect(vm),
            ),
        )
    }
    }
}

@Composable
private fun pipAspect(vm: PublisherViewModel): Float =
    vm.publishedVideoAspect
        ?: if (LocalConfiguration.current.orientation != Configuration.ORIENTATION_LANDSCAPE) 9f / 16f else 16f / 9f

private fun BoxScope.multiCameraLayerModifier(
    isMain: Boolean,
    onSwap: () -> Unit,
    pipAspect: Float = 16f / 9f,
): Modifier =
    if (isMain) {
        Modifier
            .fillMaxSize()
            .zIndex(0f)
    } else {
        Modifier
            .align(Alignment.TopEnd)
            .padding(10.dp)
            .width(if (pipAspect < 1f) 84.dp else 128.dp)
            .aspectRatio(pipAspect)
            .zIndex(1f)
            .clip(RoundedCornerShape(8.dp))
            .border(
                width = 1.dp,
                color = Color.White.copy(alpha = 0.75f),
                shape = RoundedCornerShape(8.dp),
            )
            .clickable(onClick = onSwap)
    }

@Composable
private fun MultiCameraTexture(
    position: CameraPosition,
    vm: PublisherViewModel,
    modifier: Modifier = Modifier,
) {
    AndroidView(
        modifier = modifier.background(Color.Black),
        factory = { context ->
            TextureView(context).apply {
                val listener = object : TextureView.SurfaceTextureListener {
                    private var surface: Surface? = null

                    override fun onSurfaceTextureAvailable(
                        texture: SurfaceTexture,
                        width: Int,
                        height: Int,
                    ) {
                        surface = Surface(texture).also {
                            vm.setMultiCameraPreviewSurface(position, it)
                        }
                    }

                    override fun onSurfaceTextureSizeChanged(
                        texture: SurfaceTexture,
                        width: Int,
                        height: Int,
                    ) = Unit

                    override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                        vm.setMultiCameraPreviewSurface(position, null)
                        surface?.release()
                        surface = null
                        return true
                    }

                    override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit
                }

                surfaceTextureListener = listener
                if (isAvailable) {
                    surfaceTexture?.let { listener.onSurfaceTextureAvailable(it, width, height) }
                }
            }
        },
    )
}

@Composable
private fun SourceConfigCard(vm: PublisherViewModel) {
    Card {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Sources", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)

            Text(
                "Video",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Camera", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Switch(checked = vm.cameraEnabled, onCheckedChange = { vm.cameraEnabled = it })
            }
            // v4.11 (OBS-V49-003): mid-broadcast orientation flips republish
            // automatically with the new encode orientation (~1s gap). Off =
            // deliberate fixed-orientation broadcast (기존 정책: Stop/Publish
            // 시 적용).
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Auto-rotate broadcast", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Switch(checked = vm.autoRotateBroadcast, onCheckedChange = { vm.autoRotateBroadcast = it })
            }
            if (vm.cameraEnabled) {
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    CameraSourceMode.entries.forEachIndexed { i, mode ->
                        val supported = mode != CameraSourceMode.MultiCamera || vm.isMultiCameraSupported
                        SegmentedButton(
                            selected = vm.cameraSourceMode == mode,
                            onClick = { if (supported) vm.cameraSourceMode = mode },
                            enabled = !vm.isPublishing && supported,
                            shape = SegmentedButtonDefaults.itemShape(i, CameraSourceMode.entries.size),
                            label = { Text(mode.label) },
                        )
                    }
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Screen Capture", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Switch(checked = vm.screenEnabled, onCheckedChange = { vm.screenEnabled = it })
            }

            Text(
                "Audio",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Microphone", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Switch(checked = vm.micEnabled, onCheckedChange = { vm.micEnabled = it })
            }
        }
    }
}

@Composable
private fun CodecConfigCard(vm: PublisherViewModel) {
    val availableAudioSampleRates = if (vm.audioCodec == AudioCodec.OPUS) {
        listOf(48_000)
    } else {
        listOf(44_100, 48_000)
    }

    Card {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Codec", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)

            // Video codec
            LabeledRow("Video codec") {
                SingleChoiceSegmentedButtonRow {
                    VideoCodec.entries.forEachIndexed { i, codec ->
                        val supported = codec in vm.supportedVideoCodecs
                        SegmentedButton(
                            selected = vm.videoCodec == codec,
                            onClick = { if (supported) vm.videoCodec = codec },
                            enabled = supported,
                            shape = SegmentedButtonDefaults.itemShape(i, VideoCodec.entries.size),
                            label = { Text(codec.name) },
                        )
                    }
                }
            }

            // Resolution
            LabeledRow("Resolution") {
                SingleChoiceSegmentedButtonRow {
                    VideoResolution.entries.forEachIndexed { i, res ->
                        SegmentedButton(
                            selected = vm.videoResolution == res,
                            onClick = { vm.videoResolution = res },
                            shape = SegmentedButtonDefaults.itemShape(i, VideoResolution.entries.size),
                            label = { Text(res.label) },
                        )
                    }
                }
            }

            // Frame rate
            LabeledRow("Frame rate") {
                SingleChoiceSegmentedButtonRow {
                    VideoFrameRate.entries.forEachIndexed { i, fps ->
                        SegmentedButton(
                            selected = vm.videoFrameRate == fps,
                            onClick = { vm.videoFrameRate = fps },
                            shape = SegmentedButtonDefaults.itemShape(i, VideoFrameRate.entries.size),
                            label = { Text("${fps.fps}fps") },
                        )
                    }
                }
            }

            // Audio codec
            LabeledRow("Audio codec") {
                SingleChoiceSegmentedButtonRow {
                    AudioCodec.entries.forEachIndexed { i, codec ->
                        val supported = codec in vm.supportedAudioCodecs
                        SegmentedButton(
                            selected = vm.audioCodec == codec,
                            onClick = { if (supported) vm.selectAudioCodec(codec) },
                            enabled = supported,
                            shape = SegmentedButtonDefaults.itemShape(i, AudioCodec.entries.size),
                            label = { Text(if (codec == AudioCodec.OPUS) "Opus" else "AAC") },
                        )
                    }
                }
            }

            // Audio sample rate
            LabeledRow("Sample rate") {
                SingleChoiceSegmentedButtonRow {
                    availableAudioSampleRates.forEachIndexed { i, rate ->
                        SegmentedButton(
                            selected = vm.audioSampleRate == rate,
                            onClick = { vm.audioSampleRate = rate },
                            shape = SegmentedButtonDefaults.itemShape(i, availableAudioSampleRates.size),
                            label = { Text(if (rate == 44_100) "44.1 kHz" else "48 kHz") },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PublishingStatusCard(vm: PublisherViewModel) {
    Card {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(publisherStateColor(vm))
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "Publisher: ${vm.publisherStateLabel}",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
            }

            vm.publishStatsText?.let { stats ->
                Text(
                    stats,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (vm.publishedTracks.isNotEmpty()) {
                HorizontalDivider()
                vm.publishedTracks.forEach { track ->
                    val trackState = vm.trackStates[track.name] ?: PublishedTrackState.Idle
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(trackStateColor(trackState))
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            track.name,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            trackState.name.lowercase(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LabeledRow(label: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        content()
    }
}

private fun stateColor(state: Session.State): Color = when (state) {
    Session.State.Idle -> Color.Gray
    Session.State.Connecting -> Color(0xFFFFA500)
    Session.State.Connected -> Color(0xFF2196F3)
    is Session.State.Error -> Color.Red
    Session.State.Closed -> Color.Gray
}

private fun publisherStateColor(vm: PublisherViewModel): Color = when {
    vm.isReconnecting -> Color(0xFFFFA500)
    vm.publisherState == PublisherState.Publishing && vm.isPublishStalled -> Color(0xFFFFA500)
    else -> when (vm.publisherState) {
        PublisherState.Idle -> Color.Gray
        PublisherState.Publishing -> Color(0xFF4CAF50)
        PublisherState.Stopped -> Color(0xFFFFA500)
        is PublisherState.Error -> Color.Red
    }
}

private fun trackStateColor(state: PublishedTrackState): Color = when (state) {
    PublishedTrackState.Idle -> Color.Gray
    PublishedTrackState.Starting -> Color(0xFFFFA500)
    PublishedTrackState.Active -> Color(0xFF4CAF50)
    PublishedTrackState.Stopped -> Color.Gray
}

/**
 * AND-V48-001: relaunch the app in a fresh process. The storm state lives in
 * process-global native transport state — it survives Stop/Publish and even a
 * full relay-chain restart (실기기 확정), so recovery needs a new process, not
 * a new session.
 */
private fun restartAppProcess(context: Context) {
    val intent = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
    context.startActivity(intent)
    Runtime.getRuntime().exit(0)
}

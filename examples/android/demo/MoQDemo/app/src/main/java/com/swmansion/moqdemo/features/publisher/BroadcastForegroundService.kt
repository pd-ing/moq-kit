package com.swmansion.moqdemo.features.publisher

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.swmansion.moqdemo.MainActivity

/**
 * v4.11 방송 유지 (OBS-V49-001 R2, 운영자 요구 확정): keeps the process in the
 * camera/microphone foreground-service state while a broadcast is live, so a
 * full-screen interposition (FOTA install screen, another app, lock screen)
 * no longer stops the feed. Android's while-in-use model allows continued
 * camera/mic use in the background when the typed FGS was started while the
 * app was in the foreground (Publish is always a foreground tap), and an FGS
 * process is exempt from Samsung Freecess freezing (실기기 FOTA 사건의 동결
 * 요인 제거).
 *
 * The FGS types are passed per-start (review R1): a fixed camera|microphone
 * request crashed source combinations that lack the matching runtime
 * permission — API 34+ throws SecurityException from startForeground for a
 * type whose permission is missing, and a screen-only broadcast never asks
 * for CAMERA at all. [PublisherViewModel.requiredKeepAliveFgsTypes] only
 * requests types whose permission it verified; screen-only broadcasts skip
 * this service entirely (ScreenCaptureService's mediaProjection FGS already
 * anchors the process).
 *
 * The service is a permission/liveness ANCHOR only — the camera, encoders and
 * the MoQ session stay owned by [PublisherViewModel] (데모 스코프 결정; prod
 * 통합 시 파이프라인 소유를 서비스로 승격하는 것이 로드맵).
 */
class BroadcastForegroundService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val fgsTypes = intent?.getIntExtra(EXTRA_FGS_TYPES, 0) ?: 0
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Broadcast", NotificationManager.IMPORTANCE_LOW),
        )
        // v4.12 (실기기 DEFECT-10): the launcher intent landed on the demo
        // chooser (MainScreen starts at the selection grid) — route explicitly
        // into the live Publisher screen instead. SINGLE_TOP delivers
        // onNewIntent to the existing activity, so the running broadcast's
        // Activity-scoped ViewModel (and its state) is what the user sees.
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_OPEN_PUBLISHER, true)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("Broadcasting")
            .setContentText("MoQDemo is live — tap to open")
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .build()
        try {
            if (Build.VERSION.SDK_INT >= 30 && fgsTypes != 0) {
                startForeground(NOTIFICATION_ID, notification, fgsTypes)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            // Belt-and-braces: the VM only requests types whose runtime
            // permission it verified, but OEM policy can still reject — die
            // quietly (stopping before startForeground satisfies the FGS
            // deadline) instead of crashing the broadcast, and TELL the VM
            // (review R2): this failure is asynchronous, so without the
            // callback the VM would keep believing fgsActive=true and hold
            // the camera bound in the background with no FGS behind it.
            Log.w(TAG, "startForeground rejected (types=$fgsTypes): ${e.message} — stopping keep-alive service")
            onStartFailed?.invoke()
            stopSelf(startId)
            return START_NOT_STICKY
        }
        // The anchor has no work of its own; if the system kills it there is
        // no broadcast left worth restarting without the user.
        return START_NOT_STICKY
    }

    companion object {
        private const val TAG = "BroadcastFgs"
        private const val CHANNEL_ID = "broadcast"
        private const val NOTIFICATION_ID = 0x4D6F51 // "MoQ"
        private const val EXTRA_FGS_TYPES = "fgs_types"

        /**
         * Review R2: invoked (on the main thread, onStartCommand context)
         * when startForeground is rejected after the VM already scheduled the
         * start — the VM registers this before start() and clears it in its
         * stop path so the fallback state flips instead of lying.
         */
        @Volatile
        var onStartFailed: (() -> Unit)? = null

        fun start(context: Context, fgsTypes: Int) {
            context.startForegroundService(
                Intent(context, BroadcastForegroundService::class.java)
                    .putExtra(EXTRA_FGS_TYPES, fgsTypes),
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, BroadcastForegroundService::class.java))
        }
    }
}

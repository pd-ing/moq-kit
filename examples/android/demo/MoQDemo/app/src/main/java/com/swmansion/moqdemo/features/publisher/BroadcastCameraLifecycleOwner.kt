package com.swmansion.moqdemo.features.publisher

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry

/**
 * v4.11 방송 유지 (OBS-V49-001 R2): the lifecycle CameraX binds against.
 *
 * CameraX releases the camera when its bound owner drops below STARTED — with
 * the Activity owner, any full-screen interposition (FOTA install screen,
 * another app) killed the camera feed mid-broadcast even though the
 * foreground service kept the process alive and dialing. This owner is
 * RESUMED exactly while the camera is needed: the app UI is visible (preview)
 * OR a broadcast is active (publish spans backgrounding). It parks at CREATED
 * — never DESTROYED — so CameraX can re-activate the same binding when the
 * owner comes back up.
 *
 * Main-thread confined (LifecycleRegistry requirement); every caller is a
 * lifecycle callback or a viewModelScope(Main) path.
 */
class BroadcastCameraLifecycleOwner : LifecycleOwner {
    private val registry = LifecycleRegistry(this)

    override val lifecycle: Lifecycle
        get() = registry

    init {
        registry.currentState = Lifecycle.State.CREATED
    }

    fun setActive(active: Boolean) {
        val target = if (active) Lifecycle.State.RESUMED else Lifecycle.State.CREATED
        if (registry.currentState != target) {
            registry.currentState = target
        }
    }
}

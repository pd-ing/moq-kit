package com.swmansion.moqdemo

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableLongStateOf
import com.swmansion.moqkit.NativeLogging

data class MoQDemoRelayUrls(
    val sharedRelayUrl: String,
) {
    companion object {
        val defaults = MoQDemoRelayUrls(
            // sharedRelayUrl = "http://192.168.92.134:4443/anon",
            sharedRelayUrl = "https://cdn.moq.dev/demo/bbb.hang",
        )
    }
}

class MainActivity : ComponentActivity() {
    private val relayUrls = MoQDemoRelayUrls.defaults

    // v4.12 (실기기 DEFECT-10): the Broadcasting notification must land on the
    // live Publisher screen, not the demo chooser. A monotonically increasing
    // signal (not a boolean) so EVERY notification tap re-navigates even when
    // an earlier one was already consumed.
    private val openPublisherSignal = mutableLongStateOf(0L)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        NativeLogging.setLogLevel("trace")
        enableEdgeToEdge()
        consumeIntent(intent)
        setContent {
            MaterialTheme {
                MainScreen(
                    relayUrls = relayUrls,
                    openPublisherSignal = openPublisherSignal.longValue,
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        consumeIntent(intent)
    }

    private fun consumeIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_OPEN_PUBLISHER, false) == true) {
            openPublisherSignal.longValue += 1
        }
    }

    companion object {
        const val EXTRA_OPEN_PUBLISHER = "open_publisher"
    }
}

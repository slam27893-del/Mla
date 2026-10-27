package com.myai.assistant.capture

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

/**
 * Invisible activity whose only job is to show Android's own screen-capture
 * consent dialog.
 *
 * Design notes:
 * * `MediaProjectionManager.createScreenCaptureIntent()` must be launched from an
 *   Activity, and the floating assistant runs inside a Service, so this shim exists
 *   instead of duplicating consent logic in the service.
 * * The activity is translucent, excluded from recents and `noHistory`, so it never
 *   becomes a screen of its own — the game stays behind the system dialog.
 * * Refusing the dialog is a normal outcome: the bus is told and the caller shows
 *   "لم تُمنح صلاحية الالتقاط" without retrying in a loop.
 */
class CaptureConsentActivity : AppCompatActivity() {

    private val launcher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            // The token goes straight to the one-shot capture service; it is never
            // persisted or logged.
            ScreenCaptureService.start(this, result.resultCode, data)
        } else {
            CaptureBus.fail("لم تُمنح صلاحية التقاط الشاشة.")
        }
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) {
            // Recreated by the system while the dialog was up: do not ask twice.
            finish()
            return
        }
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
        if (manager == null) {
            CaptureBus.fail("هذا الجهاز لا يوفّر خدمة التقاط الشاشة.")
            finish()
            return
        }
        runCatching { launcher.launch(manager.createScreenCaptureIntent()) }
            .onFailure { CaptureBus.fail("تعذّر فتح نافذة صلاحية الالتقاط.") }
    }
}

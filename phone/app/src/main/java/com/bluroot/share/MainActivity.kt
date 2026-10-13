package com.bluroot.share

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.View
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import java.security.SecureRandom

class MainActivity : AppCompatActivity() {
    private var service: ShareService? = null
    private var bound = false
    private val random = SecureRandom()

    private val notifications = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = (binder as ShareService.LocalBinder).service()
            bound = true
            service?.setListener { render(it) }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            bound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        findViewById<MaterialButton>(R.id.actionButton).setOnClickListener { onAction() }
        ShareService.instance?.let { render(it.current()) }
    }

    override fun onStart() {
        super.onStart()
        bindService(Intent(this, ShareService::class.java), connection, Context.BIND_AUTO_CREATE)
    }

    override fun onStop() {
        if (bound) {
            service?.setListener(null)
            unbindService(connection)
            bound = false
        }
        super.onStop()
    }

    private fun onAction() {
        val current = service?.current()
        if (current?.running == true) {
            service?.requestStop()
            return
        }
        val port = findViewById<TextInputEditText>(R.id.portInput).text?.toString()?.toIntOrNull()
        if (port == null || port !in 1..65535) {
            findViewById<TextView>(R.id.statusText).text = "Port must be from 1 to 65535"
            return
        }
        val token = newCode()
        findViewById<TextView>(R.id.codeText).text = token
        val intent = Intent(this, ShareService::class.java).setAction(ShareService.ACTION_START)
        intent.putExtra(ShareService.EXTRA_PORT, port)
        intent.putExtra(ShareService.EXTRA_TOKEN, token)
        intent.putExtra(ShareService.EXTRA_PAUSE, findViewById<MaterialSwitch>(R.id.pauseSwitch).isChecked)
        intent.putExtra(
            ShareService.EXTRA_PERMISSIVE,
            findViewById<MaterialSwitch>(R.id.selinuxSwitch).isChecked
        )
        ContextCompat.startForegroundService(this, intent)
    }

    private fun render(state: ShareUiState) {
        findViewById<TextView>(R.id.codeText).text = if (state.token.isEmpty()) "------" else state.token
        findViewById<TextView>(R.id.statusText).text = state.status
        findViewById<TextView>(R.id.detailText).text = state.detail
        findViewById<TextView>(R.id.addressText).text = state.addresses.ifEmpty {
            "Start sharing to see this phone's address."
        }
        findViewById<TextView>(R.id.logText).text = state.log
        findViewById<TextView>(R.id.rootText).text = when (state.rooted) {
            true -> "Root granted"
            false -> "Root was denied. Grant it in Magisk and try again."
            null -> "Root is requested when you tap Start."
        }
        val button = findViewById<MaterialButton>(R.id.actionButton)
        button.text = if (state.running) "Stop sharing" else "Start sharing"
        button.isEnabled = !state.busy
        findViewById<View>(R.id.portLayout).visibility = if (state.running) View.GONE else View.VISIBLE
        findViewById<MaterialSwitch>(R.id.pauseSwitch).isEnabled = !state.running
        findViewById<MaterialSwitch>(R.id.selinuxSwitch).isEnabled = !state.running
    }

    private fun newCode(): String {
        val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        return buildString(6) {
            repeat(6) { append(alphabet[random.nextInt(alphabet.length)]) }
        }
    }
}

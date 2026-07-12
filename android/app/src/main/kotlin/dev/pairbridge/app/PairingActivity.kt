package dev.pairbridge.app

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One-time setup: enter the laptop's address + token, verify it's reachable, store it. */
class PairingActivity : AppCompatActivity() {

    private lateinit var credentialStore: CredentialStore
    private lateinit var shareButton: MaterialButton
    private lateinit var shareStatusText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pairing)
        credentialStore = CredentialStore(this)

        val logo = findViewById<View>(R.id.logo)
        val title = findViewById<View>(R.id.title)
        val subtitle = findViewById<View>(R.id.subtitle)
        val formCard = findViewById<View>(R.id.formCard)
        val statusRow = findViewById<View>(R.id.statusRow)
        val hostInput = findViewById<TextInputEditText>(R.id.hostInput)
        val portInput = findViewById<TextInputEditText>(R.id.portInput)
        val tokenInput = findViewById<TextInputEditText>(R.id.tokenInput)
        val statusProgress = findViewById<CircularProgressIndicator>(R.id.statusProgress)
        val statusIcon = findViewById<View>(R.id.statusIcon)
        val statusText = findViewById<TextView>(R.id.statusText)
        val pairButton = findViewById<MaterialButton>(R.id.pairButton)
        shareButton = findViewById(R.id.shareButton)
        shareStatusText = findViewById(R.id.shareStatusText)

        animateEntrance(logo, title, subtitle, formCard, statusRow, findViewById(R.id.shareCard))

        shareButton.setOnClickListener { view ->
            bounce(view)
            onShareButtonClicked()
        }

        if (credentialStore.isPaired) {
            hostInput.setText(credentialStore.host)
            portInput.setText(credentialStore.port.toString())
            setStatus(statusProgress, statusIcon, statusText, State.SUCCESS, getString(R.string.status_paired, credentialStore.host))
        }

        pairButton.setOnClickListener { view ->
            bounce(view)
            val host = hostInput.text.toString().trim()
            val port = portInput.text.toString().trim().toIntOrNull() ?: CredentialStore.DEFAULT_PORT
            val token = tokenInput.text.toString().trim()

            if (host.isEmpty() || token.isEmpty()) {
                setStatus(statusProgress, statusIcon, statusText, State.ERROR, "Host and token are required")
                return@setOnClickListener
            }

            setStatus(statusProgress, statusIcon, statusText, State.LOADING, getString(R.string.status_pairing))
            lifecycleScope.launch {
                val reachable = withContext(Dispatchers.IO) {
                    runCatching { LaptopClient(host, port, token).checkHealth() }.getOrDefault(false)
                }
                if (reachable) {
                    credentialStore.host = host
                    credentialStore.port = port
                    credentialStore.token = token
                    setStatus(statusProgress, statusIcon, statusText, State.SUCCESS, getString(R.string.status_paired, host))
                } else {
                    setStatus(statusProgress, statusIcon, statusText, State.ERROR, getString(R.string.status_error, "Could not reach $host:$port"))
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshShareStatus()
    }

    private fun onShareButtonClicked() {
        if (!Environment.isExternalStorageManager()) {
            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
            return
        }
        credentialStore.sharingEnabled = !credentialStore.sharingEnabled
        if (credentialStore.sharingEnabled) {
            startPairbridgeService()
        } else {
            stopService(Intent(this, PairbridgeService::class.java))
        }
        refreshShareStatus()
    }

    private fun startPairbridgeService() {
        val intent = Intent(this, PairbridgeService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun refreshShareStatus() {
        if (!Environment.isExternalStorageManager()) {
            shareButton.text = getString(R.string.action_grant_access)
            shareStatusText.text = getString(R.string.share_status_needs_permission)
            shareStatusText.setTextColor(ContextCompat.getColor(this, R.color.status_neutral))
            return
        }
        if (credentialStore.sharingEnabled) {
            shareButton.text = getString(R.string.action_stop_sharing)
            shareStatusText.text = getString(R.string.share_status_on, TabletFileServer.PORT)
            shareStatusText.setTextColor(ContextCompat.getColor(this, R.color.status_success))
            if (credentialStore.isPaired) startPairbridgeService()
        } else {
            shareButton.text = getString(R.string.action_start_sharing)
            shareStatusText.text = getString(R.string.share_status_off)
            shareStatusText.setTextColor(ContextCompat.getColor(this, R.color.status_neutral))
        }
    }

    private enum class State { LOADING, SUCCESS, ERROR }

    private fun setStatus(
        progress: CircularProgressIndicator,
        icon: View,
        text: TextView,
        state: State,
        message: String,
    ) {
        progress.visibility = if (state == State.LOADING) View.VISIBLE else View.GONE
        icon.visibility = if (state == State.SUCCESS || state == State.ERROR) View.VISIBLE else View.GONE
        if (icon is ImageView) {
            val res = if (state == State.SUCCESS) R.drawable.ic_status_success else R.drawable.ic_status_error
            icon.setImageResource(res)
        }
        val color = when (state) {
            State.SUCCESS -> R.color.status_success
            State.ERROR -> R.color.status_error
            else -> R.color.status_neutral
        }
        text.setTextColor(ContextCompat.getColor(this, color))
        text.text = message
        icon.alpha = 0f
        icon.animate().alpha(1f).setDuration(200).start()
    }

    private fun bounce(view: View) {
        view.animate()
            .scaleX(0.96f).scaleY(0.96f)
            .setDuration(80)
            .withEndAction {
                view.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
            }
            .start()
    }

    private fun animateEntrance(vararg views: View) {
        views.forEachIndexed { index, view ->
            view.translationY = 24f
            view.animate()
                .alpha(1f)
                .translationY(0f)
                .setStartDelay(index * 70L)
                .setDuration(360)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }
    }
}

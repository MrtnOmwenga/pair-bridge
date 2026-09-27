package dev.pairbridge.app

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.Settings
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.google.android.material.textfield.TextInputEditText
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Pairs with the laptop (by scanning the code from `server.py pair`, or by hand) and toggles tablet sharing. */
class PairingActivity : AppCompatActivity() {

    private lateinit var laptop: LaptopConnection
    private lateinit var shareButton: MaterialButton
    private lateinit var shareStatusText: TextView
    private lateinit var hostInput: TextInputEditText
    private lateinit var portInput: TextInputEditText
    private lateinit var tokenInput: TextInputEditText
    private lateinit var statusProgress: CircularProgressIndicator
    private lateinit var statusIcon: View
    private lateinit var statusText: TextView
    private lateinit var browseButton: MaterialButton

    private val nearbyWifiPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { refreshShareStatus() }

    private val scanLauncher = registerForActivityResult(ScanContract()) { result ->
        result.contents?.let(::onPairingCode)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pairing)
        laptop = LaptopConnection.get(this)

        hostInput = findViewById(R.id.hostInput)
        portInput = findViewById(R.id.portInput)
        tokenInput = findViewById(R.id.tokenInput)
        statusProgress = findViewById(R.id.statusProgress)
        statusIcon = findViewById(R.id.statusIcon)
        statusText = findViewById(R.id.statusText)
        shareButton = findViewById(R.id.shareButton)
        shareStatusText = findViewById(R.id.shareStatusText)
        browseButton = findViewById(R.id.browseButton)
        browseButton.setOnClickListener { openLaptopFiles() }

        animateEntrance(
            findViewById(R.id.logo),
            findViewById(R.id.title),
            findViewById(R.id.subtitle),
            findViewById(R.id.formCard),
            findViewById(R.id.statusRow),
            findViewById(R.id.shareCard),
        )

        if (!supportsTabletSharing()) findViewById<View>(R.id.shareCard).visibility = View.GONE
        shareButton.setOnClickListener { view ->
            bounce(view)
            onShareButtonClicked()
        }

        val credentials = laptop.credentials
        if (credentials.isPaired) {
            hostInput.setText(credentials.host)
            portInput.setText(credentials.port.toString())
            setStatus(State.SUCCESS, getString(R.string.status_paired, credentials.host))
            browseButton.visibility = View.VISIBLE
        }

        findViewById<MaterialButton>(R.id.scanButton).setOnClickListener { view ->
            bounce(view)
            scanLauncher.launch(
                ScanOptions()
                    .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                    .setPrompt(getString(R.string.scan_prompt))
                    .setBeepEnabled(false)
                    .setOrientationLocked(false),
            )
        }

        findViewById<MaterialButton>(R.id.pairButton).setOnClickListener { view ->
            bounce(view)
            val host = hostInput.text.toString().trim()
            val port = portInput.text.toString().trim().toIntOrNull() ?: CredentialStore.DEFAULT_PORT
            val token = tokenInput.text.toString().trim()
            if (host.isEmpty() || token.isEmpty()) {
                setStatus(State.ERROR, "Host and token are required")
                return@setOnClickListener
            }
            pair(host, port, token)
        }
    }

    /** Pairing codes look like pairbridge://pair?host=…&port=…&token=…&id=… */
    private fun onPairingCode(contents: String) {
        val uri = Uri.parse(contents)
        val host = uri.getQueryParameter("host")
        val port = uri.getQueryParameter("port")?.toIntOrNull()
        val token = uri.getQueryParameter("token")
        if (uri.scheme != "pairbridge" || uri.host != "pair" || host == null || port == null || token == null) {
            setStatus(State.ERROR, getString(R.string.error_bad_code))
            return
        }
        hostInput.setText(host)
        portInput.setText(port.toString())
        tokenInput.setText(token)
        pair(host, port, token)
    }

    /** Saves the pairing only after an authenticated request succeeds, so a wrong token is caught here. */
    private fun pair(host: String, port: Int, token: String) {
        setStatus(State.LOADING, getString(R.string.status_pairing))
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { LaptopClient(host, port, token).listRoots() } }
            result.onSuccess { roots ->
                laptop.credentials.savePairing(host, port, token, roots.serverId, roots.serverName)
                contentResolver.notifyChange(DocumentsContract.buildRootsUri(LaptopDocumentsProvider.AUTHORITY), null)
                setStatus(State.SUCCESS, getString(R.string.status_paired, host))
                browseButton.visibility = View.VISIBLE
            }.onFailure { e ->
                val message = if (e is LaptopClientException && e.code == 401) {
                    getString(R.string.error_token_rejected)
                } else {
                    getString(R.string.error_unreachable, "$host:$port")
                }
                setStatus(State.ERROR, message)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshShareStatus()
    }

    private fun hasNearbyWifiPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(this, Manifest.permission.NEARBY_WIFI_DEVICES) ==
            PackageManager.PERMISSION_GRANTED
    }

    /**
     * Opens Android's own Files app at the Laptop root. Vendor file managers (Xiaomi's among
     * them) also claim this intent but don't list other apps' storage providers, and HyperOS
     * hides the system Files app's icon, so the system app is targeted by package first.
     */
    private fun openLaptopFiles() {
        val rootUri = DocumentsContract.buildRootUri(LaptopDocumentsProvider.AUTHORITY, LaptopDocumentsProvider.ROOT_ID)
        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(rootUri, DocumentsContract.Root.MIME_TYPE_ITEM)
        for (pkg in listOf("com.google.android.documentsui", "com.android.documentsui", null)) {
            try {
                startActivity(Intent(intent).setPackage(pkg))
                return
            } catch (e: ActivityNotFoundException) {
                continue
            }
        }
        Toast.makeText(this, R.string.error_no_files_app, Toast.LENGTH_LONG).show()
    }

    private fun onShareButtonClicked() {
        if (!hasNearbyWifiPermission()) {
            nearbyWifiPermissionLauncher.launch(Manifest.permission.NEARBY_WIFI_DEVICES)
            return
        }
        if (!hasAllFilesAccess()) {
            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
            return
        }
        laptop.credentials.sharingEnabled = !laptop.credentials.sharingEnabled
        if (laptop.credentials.sharingEnabled) {
            startPairbridgeService()
        } else {
            stopService(Intent(this, PairbridgeService::class.java))
        }
        refreshShareStatus()
    }

    private fun startPairbridgeService() {
        startForegroundService(Intent(this, PairbridgeService::class.java))
    }

    // Serving the tablet's folders relies on "All files access", which exists from Android 11.
    private fun supportsTabletSharing() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    private fun refreshShareStatus() {
        if (!supportsTabletSharing()) return
        if (!hasNearbyWifiPermission() || !hasAllFilesAccess()) {
            shareButton.text = getString(R.string.action_grant_access)
            shareStatusText.text = getString(R.string.share_status_needs_permission)
            shareStatusText.setTextColor(ContextCompat.getColor(this, R.color.status_neutral))
            return
        }
        if (laptop.credentials.sharingEnabled) {
            shareButton.text = getString(R.string.action_stop_sharing)
            shareStatusText.text = getString(R.string.share_status_on, TabletFileServer.PORT)
            shareStatusText.setTextColor(ContextCompat.getColor(this, R.color.status_success))
            if (laptop.credentials.isPaired) startPairbridgeService()
        } else {
            shareButton.text = getString(R.string.action_start_sharing)
            shareStatusText.text = getString(R.string.share_status_off)
            shareStatusText.setTextColor(ContextCompat.getColor(this, R.color.status_neutral))
        }
    }

    private enum class State { LOADING, SUCCESS, ERROR }

    private fun setStatus(state: State, message: String) {
        statusProgress.visibility = if (state == State.LOADING) View.VISIBLE else View.GONE
        statusIcon.visibility = if (state == State.LOADING) View.GONE else View.VISIBLE
        (statusIcon as? ImageView)?.setImageResource(
            if (state == State.SUCCESS) R.drawable.ic_status_success else R.drawable.ic_status_error,
        )
        val color = when (state) {
            State.SUCCESS -> R.color.status_success
            State.ERROR -> R.color.status_error
            State.LOADING -> R.color.status_neutral
        }
        statusText.setTextColor(ContextCompat.getColor(this, color))
        statusText.text = message
        statusIcon.alpha = 0f
        statusIcon.animate().alpha(1f).setDuration(200).start()
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

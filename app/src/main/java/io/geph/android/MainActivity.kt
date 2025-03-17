package io.geph.android

import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.content.*
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.util.Base64
import android.util.Log
import android.view.View
import android.webkit.*
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.browser.customtabs.CustomTabsIntent
import androidx.fragment.app.Fragment
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.webkit.WebViewAssetLoader
import io.geph.android.proxbinder.Proxbinder
import io.geph.android.tun2socks.TunnelManager
import io.geph.android.tun2socks.TunnelState
import io.geph.android.tun2socks.TunnelVpnService
import kotlinx.serialization.json.*
import org.apache.commons.text.StringEscapeUtils
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.Socket
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity(), MainActivityInterface {

    // -------------------------------------------------------------------
    // Fields for the main (VPN-based) daemon / service.
    // -------------------------------------------------------------------
    var isShow = false
    var scrollRange = -1
    private val mInternetDown = false
    private val mInternetDownSince: Long = 0
    private var mUiHandler: Handler? = null
    private val mProgress: View? = null
    private var mWebView: WebView? = null
    private var vpnReceiver: Receiver? = null
    
    // Current daemon configuration 
    private var daemonArgs: DaemonArgs? = null

    // -------------------------------------------------------------------
    // The fallback daemon, used ONLY for "daemon_rpc" if 127.0.0.1:10000 fails
    // -------------------------------------------------------------------
    /**
     * This daemon is used **only** for fallback when "daemon_rpc" fails to connect
     * to 127.0.0.1:10000. It's a read-only lazy property, so it's only created
     * once you actually access it.
     */
    private val fallbackDaemon: GephDaemon by lazy {
        Log.d(TAG, "START FALLBACK DAEMON")
        // Create with a default configuration
        GephDaemon(this.applicationContext, configTemplate(), true)
    }

    // -------------------------------------------------------------------
    // Standard activity methods
    // -------------------------------------------------------------------
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        bindActivity()

        val filter = IntentFilter().apply {
            addAction(TunnelVpnService.TUNNEL_VPN_DISCONNECT_BROADCAST)
            addAction(TunnelVpnService.TUNNEL_VPN_START_BROADCAST)
            addAction(TunnelVpnService.TUNNEL_VPN_START_SUCCESS_EXTRA)
        }
        vpnReceiver = Receiver()
        LocalBroadcastManager.getInstance(this).registerReceiver(vpnReceiver!!, filter)
    }

    override fun onResume() {
        super.onResume()
        mUiHandler = Handler()
        if (isServiceRunning) {
            val intent = intent
            if (intent != null && intent.action == ACTION_STOP_VPN_SERVICE) {
                intent.action = null
                stopVpn()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        LocalBroadcastManager.getInstance(this).unregisterReceiver(vpnReceiver!!)

        fallbackDaemon.stopDaemon()

        Log.d(TAG, "destroying MainActivity")

        // The original code does this; keep it if you want the same behavior
        System.exit(0)
    }

    // -------------------------------------------------------------------
    // WebView initialization
    // -------------------------------------------------------------------
    @SuppressLint("SetJavaScriptEnabled")
    private fun bindActivity() {
        mWebView = findViewById(R.id.main_webview)
        val wview = mWebView!!
        wview.settings.javaScriptEnabled = true
        wview.settings.domStorageEnabled = true
        wview.settings.javaScriptCanOpenWindowsAutomatically = true
        wview.settings.setSupportMultipleWindows(false)
        WebView.setWebContentsDebuggingEnabled(true)
        wview.webChromeClient = WebChromeClient()

        // asset loader
        val assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        wview.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? {
                if (request == null) {
                    throw RuntimeException("request is null")
                }
                val resp = assetLoader.shouldInterceptRequest(request.url)
                if (request.url.toString().endsWith("js")) {
                    resp?.setMimeType("text/javascript")
                }
                return resp
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                val initJsString =
                    application.assets.open("init.js").bufferedReader().use { it.readText() }
                wview.evaluateJavascript(initJsString, null)
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url.toString()
                Log.e(TAG, url)
                return if (url.contains("appassets.androidplatform.net")) {
                    false
                } else {
                    val i = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    startActivity(i)
                    true
                }
            }
        }

        wview.addJavascriptInterface(this, "Android")
        wview.loadUrl("https://appassets.androidplatform.net/htmlbuild/index.html")
    }

    // -------------------------------------------------------------------
    // JavaScript interface: dispatch calls from WebView to Kotlin
    // -------------------------------------------------------------------
    @JavascriptInterface
    fun jsVersion(): String {
        return BuildConfig.VERSION_NAME
    }

    @JavascriptInterface
    fun jsHasPlay(): String {
        return try {
            application.packageManager.getPackageInfo("com.android.vending", 0)
            "true"
        } catch (e: Exception) {
            "false"
        }
    }

    @JavascriptInterface
    fun callRpc(verb: String, jsonArgs: String, cback: String) {
        val wbview = mWebView!!
        thread {
            try {
                val result = callRpcInner(verb, jsonArgs)
                runOnUiThread {
                    wbview.evaluateJavascript(
                        "$cback[0](\"${StringEscapeUtils.escapeEcmaScript(result)}\")",
                        null
                    )
                }
            } catch (e: Exception) {
                runOnUiThread {
                    wbview.evaluateJavascript(
                        "$cback[1](\"${StringEscapeUtils.escapeEcmaScript(e.message)}\")",
                        null
                    )
                }
            }
        }
    }

    // -------------------------------------------------------------------
    // The actual handling of RPC calls
    // -------------------------------------------------------------------
    fun callRpcInner(verb: String?, jsonArgs: String?): String {
        val args = JSONArray(jsonArgs)
        Log.e(TAG, verb!!)
        when (verb) {
            "start_daemon" -> {
                // Create daemon args and start VPN service
                rpcStartDaemon(args.getJSONObject(0))
                Thread.sleep(1000);
                return "null"
            }
            "restart_daemon" -> {
                throw Exception("restarting not supported")
                return "null"
            }
            "stop_daemon" -> {
                // Stop the VPN service
                stopVpn()
                return "null"
            }
            "daemon_rpc" -> {
                val command = args.getString(0)
                return try {
                    // Attempt a TCP connection to 127.0.0.1:10000
                    Socket("127.0.0.1", 10000).use { socket ->
                        val out = PrintWriter(socket.getOutputStream(), true)
                        val input = BufferedReader(InputStreamReader(socket.getInputStream()))
                        out.println(command)
                        input.readLine() // read one-line response
                    }
                } catch (e: Exception) {
                    // If we fail to connect, fallback to the lazy fallbackDaemon
                    fallbackDaemon.rawStdioRpc(command) ?: ""
                }
            }
            "get_app_list" -> {
                return rpcGetAppList()
            }
            "get_app_icon" -> {
                return rpcGetAppIcon(args.getString(0))
            }
            "export_logs" -> {
                rpcExportLogs()
                return "null"
            }
            "get_debug_logs" -> {
                try {
                    val process = Runtime.getRuntime().exec("logcat -d")
                    val bufferedReader = process.inputStream.bufferedReader()
                    val log = bufferedReader.use { it.readText() }
                    return JSONObject.quote(log)
                } catch (e: Exception) {
                    e.printStackTrace()
                    "Error: ${e.message}"
                }
            }
            "open_browser" -> {
                Log.d(TAG, "open browser")
                val url = args.getString(0)
                val builder = CustomTabsIntent.Builder()

// (Optional) customize toolbar color
//                builder.setToolbarColor(ContextCompat.getColor(this, R.color.your_color))

                val customTabsIntent = builder.build()
                customTabsIntent.launchUrl(this, Uri.parse(url))
                return "null"
            }
        }
        throw Exception("Unknown RPC verb: $verb")
    }

    // -------------------------------------------------------------------
    // "start_daemon" calls here: sets up the main VPN-based service 
    // -------------------------------------------------------------------
    private fun rpcStartDaemon(args: JSONObject) {
        try {
            val jsonString = args.toString()
            val json = Json { ignoreUnknownKeys = true }
            daemonArgs = json.decodeFromString(DaemonArgs.serializer(), jsonString)

            // Store the DaemonArgs in shared preferences as JSON for the VPN service
            val prefs = applicationContext.getSharedPreferences("daemon", Context.MODE_PRIVATE)
            prefs.edit().apply {
                putString(TunnelManager.DAEMON_ARGS, Json.encodeToString(DaemonArgs.serializer(), daemonArgs!!))
                apply()
            }
            
            // Start the VPN service
            startVpn()
            
        } catch (e: Exception) {
            Log.e(TAG, "Error starting daemon: ${e.message}", e)
            throw e
        }
    }

    // -------------------------------------------------------------------
    // "export_logs" calls here
    // -------------------------------------------------------------------
    @JavascriptInterface
    fun rpcExportLogs() {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/vnd.sqlite3"
            putExtra(Intent.EXTRA_TITLE, "geph-debugpack.db")
        }
        startActivityForResult(intent, CREATE_FILE)
    }

    // -------------------------------------------------------------------
    // Example: get list of installed apps
    // -------------------------------------------------------------------
    fun rpcGetAppList(): String {
        val pm = packageManager
        val packages = pm.getInstalledApplications(PackageManager.GET_META_DATA)
        val bigArray = JSONArray()
        for (packageInfo in packages) {
            if (packageInfo.packageName == applicationContext.packageName) continue
            val jsonObject = JSONObject().apply {
                put("id", packageInfo.packageName)
                put("friendly_name", packageInfo.loadLabel(pm))
            }
            bigArray.put(jsonObject)
        }
        return bigArray.toString()
    }

    // -------------------------------------------------------------------
    // Example: get app icon
    // -------------------------------------------------------------------
    fun rpcGetAppIcon(packageName: String?): String {
        val pm = packageManager
        val icon = drawableToBitmap(pm.getApplicationIcon(packageName!!))
        val b64 = encodeToBase64(icon).replace("\n", "")
        return "\"data:image/png;base64,$b64\""
    }

    // -------------------------------------------------------------------
    // The usual Proxbinder map from original code (if you need it)
    // -------------------------------------------------------------------
    private val pbMap: MutableMap<Int, Proxbinder> = HashMap()

    // -------------------------------------------------------------------
    // Activity result logic for VPN + file creation
    // -------------------------------------------------------------------
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CODE_PREPARE_VPN && resultCode == RESULT_OK) {
            startTunnelService(applicationContext)
        } else if (requestCode == CREATE_FILE && resultCode == RESULT_OK) {
            val ctx = applicationContext
            val daemonBinaryPath: String = applicationInfo.nativeLibraryDir + "/libgeph.so"
            val debugPackPath = applicationInfo.dataDir + "/geph4-debugpack.db"
            val debugPackExportedPath = applicationInfo.dataDir + "/geph4-debugpack-exported.db"
            val pb = ProcessBuilder(
                daemonBinaryPath, "debugpack",
                "--export-to", debugPackExportedPath,
                "--debugpack-path", debugPackPath
            )
            thread {
                try {
                    val process = pb.start()
                    val retval = process.waitFor()
                    Log.d(TAG, "Export process returned $retval")
                } catch (e: IOException) {
                    Log.e(TAG, "Export debugpack failed: ${e.message}")
                }
                try {
                    FileInputStream(debugPackExportedPath).use { `is` ->
                        contentResolver.openOutputStream(data!!.data!!).use { os ->
                            val buffer = ByteArray(1024)
                            var length: Int
                            while (`is`.read(buffer).also { length = it } > 0) {
                                os!!.write(buffer, 0, length)
                            }
                        }
                    }
                } catch (e: IOException) {
                    e.printStackTrace()
                }
            }
        }
    }

    // -------------------------------------------------------------------
    // Methods for the main (VPN-based) daemon
    // -------------------------------------------------------------------
    protected fun prepareAndStartTunnelService() {
        Log.d(TAG, "Starting VpnService")
        if (hasVpnService()) {
            if (prepareVpnService()) {
                startTunnelService(applicationContext)
            }
        } else {
            Log.e(TAG, "Device does not support whole device VPN mode.")
        }
    }

    @TargetApi(Build.VERSION_CODES.ICE_CREAM_SANDWICH)
    @Throws(ActivityNotFoundException::class)
    protected fun prepareVpnService(): Boolean {
        val prepareVpnIntent = VpnService.prepare(baseContext)
        if (prepareVpnIntent != null) {
            startActivityForResult(prepareVpnIntent, REQUEST_CODE_PREPARE_VPN)
            return false
        }
        return true
    }

    protected fun startTunnelService(context: Context?) {
        Log.i(TAG, "Starting tunnel service")
        val startTunnelVpn = Intent(context, TunnelVpnService::class.java)
        
        if (startService(startTunnelVpn) == null) {
            Log.d(TAG, "Failed to start tunnel vpn service")
            return
        }
        TunnelState.getTunnelState().setStartingTunnelManager()
    }

    // -------------------------------------------------------------------
    // Implement MainActivityInterface
    // -------------------------------------------------------------------
    override fun startVpn() {
        prepareAndStartTunnelService()
    }

    override fun stopVpn() {
        Log.e(TAG, "Attempting to stop VPN")
        val currentTunnelManager = TunnelState.getTunnelState().tunnelManager
        currentTunnelManager?.signalStopService() ?: Log.e(TAG, "Cannot stop because tunnel manager is null!")
    }

    // -------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------
    /**
     * Simple helper to retrieve the service status
     * @return true iff the service is alive and running; false otherwise
     */
    protected val isServiceRunning: Boolean
        get() {
            val tunnelState = TunnelState.getTunnelState()
            return tunnelState.startingTunnelManager || tunnelState.tunnelManager != null
        }

    /**
     * Whether the device supports the VPN-based service.
     */
    private fun hasVpnService(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP
    }

    // This is if you need to see whether a fragment is present
    private val isContentFragmentAdded: Boolean
        get() = supportFragmentManager.findFragmentByTag(FRONT)?.isAdded == true

    private inner class Receiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                TunnelVpnService.TUNNEL_VPN_DISCONNECT_BROADCAST -> {
                    // handle if you want
                }
                TunnelVpnService.TUNNEL_VPN_START_BROADCAST -> {
                    // handle if you want
                }
                TunnelVpnService.TUNNEL_VPN_START_SUCCESS_EXTRA -> {
                    Log.d(TAG, "broadcast: TUNNEL_VPN_START_SUCCESS_EXTRA")
                }
            }
        }
    }

    // -------------------------------------------------------------------
    // Companion object and other constants
    // -------------------------------------------------------------------
    companion object {
        const val ACTION_STOP_VPN_SERVICE = "stop_vpn_immediately"
        private val TAG = MainActivity::class.java.simpleName
        private const val REQUEST_CODE_PREPARE_VPN = 100
        private const val CREATE_FILE = 1
        private const val FRONT = "front"

        fun encodeToBase64(image: Bitmap): String {
            val baos = ByteArrayOutputStream()
            image.compress(Bitmap.CompressFormat.PNG, 100, baos)
            val b = baos.toByteArray()
            return Base64.encodeToString(b, Base64.DEFAULT)
        }

        fun drawableToBitmap(drawable: Drawable): Bitmap {
            if (drawable is BitmapDrawable) {
                return drawable.bitmap
            }
            val width = 32
            val height = 32
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            drawable.setBounds(0, 0, canvas.width, canvas.height)
            drawable.draw(canvas)
            return bitmap
        }
    }
}
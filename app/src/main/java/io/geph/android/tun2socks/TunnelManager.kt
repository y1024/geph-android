package io.geph.android.tun2socks

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.PendingIntent.FLAG_IMMUTABLE
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.system.OsConstants.F_SETFD
import androidx.core.app.NotificationCompat
import android.util.Log
import com.sun.jna.Library
import com.sun.jna.Native
import io.geph.android.DaemonArgs
import io.geph.android.GephDaemon
import io.geph.android.MainActivity
import io.geph.android.R
import kotlinx.serialization.json.*
import java.io.FileInputStream
import java.io.FileOutputStream

import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.system.exitProcess


interface LibC : Library { // A representation of libC in Java
    fun fcntl(fd: Int, cmd: Int, args: Int): Int; // mapping of the puts function, in C `int puts(const char *s);`
    fun dup2(oldFd: Int, newFd: Int): Int;
}

class TunnelManager(parentService: TunnelVpnService?) {
    private var m_parentService: TunnelVpnService? = null
    private var m_tunnelThreadStopSignal: CountDownLatch? = null
    private var m_tunnelThread: Thread? = null
    private val m_isStopping: AtomicBoolean
    private val m_isReconnecting: AtomicBoolean
    private var tunFd: ParcelFileDescriptor? = null
    
    // Store GephDaemon instance
    private var gephDaemon: GephDaemon? = null

    // Implementation of android.app.Service.onStartCommand
    fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            Log.i(LOG_TAG, "Intent is null")
            return 0
        }
        Log.i(LOG_TAG, "onStartCommand")

        // Setup and run VPN service with daemon
        setupAndRunVpnService()
        
        // Set up notification
        val ctx = context
        val notificationIntent = Intent(ctx, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(ctx, 0, notificationIntent, FLAG_IMMUTABLE)
        val largeIcon = BitmapFactory.decodeResource(ctx!!.resources, R.mipmap.ic_launcher)
        val channelId = createNotificationChannel()
        val builder = NotificationCompat.Builder(ctx, channelId)
                .setSmallIcon(R.drawable.ic_stat_notification_icon)
                .setLargeIcon(largeIcon)
                .setWhen(System.currentTimeMillis())
                .setContentTitle(ctx.getText(R.string.notification_label))
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
        val notification = builder.build()
        
        // starting this service on foreground to avoid accidental GC by Android system
        vpnService!!.startForeground(NOTIFICATION_ID, notification)
        return Service.START_STICKY
    }

    private fun createNotificationChannel(): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channelId = "geph_service"
            val channelName = "Geph background service"
            val chan = NotificationChannel(channelId,
                    channelName, NotificationManager.IMPORTANCE_NONE)
            chan.description = "Geph background service"
            val notificationManager = context!!.getSystemService(NotificationManager::class.java)
            assert(notificationManager != null)
            notificationManager!!.createNotificationChannel(chan)
            return channelId
        }
        return ""
    }

    private fun setupAndRunVpnService() {
        Log.e("SETUP", "Setting up VPN service and daemon")
        
        // Get DaemonArgs from shared preferences
        val prefs = context!!.getSharedPreferences("daemon", Context.MODE_PRIVATE)
        val daemonArgsJson = prefs.getString(DAEMON_ARGS, null)
        
        if (daemonArgsJson == null) {
            Log.e(LOG_TAG, "No daemon arguments found in preferences")
            m_parentService!!.broadcastVpnStart(false)
            return
        }
        
        // Parse DaemonArgs from JSON
        val daemonArgs = try {
            val json = Json { ignoreUnknownKeys = true }
            json.decodeFromString(DaemonArgs.serializer(), daemonArgsJson)
        } catch (e: Exception) {
            Log.e(LOG_TAG, "Failed to parse daemon arguments: ${e.message}")
            m_parentService!!.broadcastVpnStart(false)
            return
        }
        
        // Create VPN interface
        var vpnInterface: ParcelFileDescriptor? = null
        while (vpnInterface == null) {
            Log.d("SETUP", "Attempting to create VPN interface")
            val builder = m_parentService!!.newBuilder()
                .addAddress("100.64.89.64", 10)
                .addRoute("0.0.0.0", 0)
                .addDnsServer("1.1.1.1")
                .addDisallowedApplication(context!!.packageName)
            
            // Add excluded apps from the app whitelist
            try {
                // Only process app whitelist if it's not empty
                for (packageName in daemonArgs.appWhitelist) {
                    try {
                        builder.addDisallowedApplication(packageName)
                    } catch (e: Exception) {
                        Log.w(LOG_TAG, "Failed to add app to exclusion list: $packageName")
                    }
                }
            } catch (e: Exception) {
                Log.e(LOG_TAG, "Error setting up app exclusions: ${e.message}")
                e.printStackTrace()
            }
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                builder.setMetered(false)
            }
            
            vpnInterface = builder.setBlocking(true)
                .setMtu(65535)
                .establish()
        }
        
        tunFd = vpnInterface
        startGephDaemon(vpnInterface, daemonArgs)
    }

    private fun startGephDaemon(vpnInterface: ParcelFileDescriptor, daemonArgs: DaemonArgs) {
        val fd = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val fd = vpnInterface.detachFd()
            var hoho = Native.load(LibC::class.java);
            val rv = hoho.fcntl(fd, F_SETFD, 0);
            hoho.dup2(fd, 0);
            0
        } else {
            -1 // Will handle stdio-based approach for older versions
        }
        // Create a config from the DaemonArgs
        val config = daemonArgs.toConfig(context!!).jsonObject
        
        // Add VPN-specific configurations to the config
        val vpnEnabledConfig = buildJsonObject {
            // Copy all elements from the original config
            for ((key, value) in config) {
                put(key, value)
            }

            // Add VPN fd configuration if available
            if (fd >= 0) {
                put("vpn_fd", fd)
            }
            
            // Ensure control port is set for daemon_rpc
            put("control_listen", "127.0.0.1:10000")
        }
        
        // Create and start the daemon
        gephDaemon = GephDaemon(context!!, vpnEnabledConfig, false)
        
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            // For older Android versions, manually handle tunnel I/O
            startLegacyIo(vpnInterface)
        }
        
        // Broadcast successful VPN start
        m_parentService!!.broadcastVpnStart(true)
        
        // Monitor daemon for crashes
        thread {
            // This thread will exit when the daemon process exits
            try {
                // Wait for daemon process to exit (via reflection)
                val process = gephDaemon!!.javaClass.getDeclaredField("daemonProcess")
                process.isAccessible = true
                val daemonProcess = process.get(gephDaemon) as Process
                
                val exitCode = daemonProcess.waitFor()
                Log.e(LOG_TAG, "Daemon process exited with code: $exitCode")
                
                // When daemon exits, stop the VPN service
                vpnService?.stopForeground(true)
                vpnService?.stopSelf()
                Log.e(LOG_TAG, "Daemon process finished: $exitCode")
                // destroy this process to fully fully clean up
                exitProcess(0)

            } catch (e: Exception) {
                Log.e(LOG_TAG, "Error monitoring daemon process: ${e.message}")
            }
        }
    }
    
    private fun startLegacyIo(vpnInterface: ParcelFileDescriptor) {
        // download
        Log.e(LOG_TAG, "VPN I/O SET UP")
        Thread {
            val body = ByteArray(40000);
            val writer = FileOutputStream(vpnInterface.fileDescriptor)
            while(true) {
                val n = gephDaemon!!.downloadVpn(body);
                writer.write(body, 0, n)
            }
        }.start()
        // upload
        Thread {
            Log.e(LOG_TAG, "VPN I/O UP STARTED")
            val body = ByteArray(40000);
            val reader = FileInputStream(vpnInterface.fileDescriptor)
            while(true) {
                val n = reader.read(body);
                gephDaemon!!.uploadVpn(body, n);
            }
        }.start()
    }

    fun terminateDaemon() {
        gephDaemon?.stopDaemon()
        gephDaemon = null
        
        // Close the VPN interface
        tunFd?.close()
        tunFd = null
    }

    // Implementation of android.app.Service.onDestroy
    fun onDestroy() {
        terminateDaemon()
        
        if (m_tunnelThread == null) {
            return
        }
        
        // signalStopService should have been called, but in case it was not, call here.
        signalStopService()
        
        try {
            m_tunnelThread!!.join()
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        
        m_tunnelThreadStopSignal = null
        m_tunnelThread = null
        
        // stop the foreground service
        vpnService!!.stopForeground(true)
    }

    // Signals the runTunnel thread to stop. The thread will self-stop the service.
    fun signalStopService() {
        if (m_tunnelThreadStopSignal != null) {
            m_tunnelThreadStopSignal!!.countDown()
        }
        
        // Also terminate the daemon
        terminateDaemon()
    }

    // Context accessors
    val context: Context?
        get() = m_parentService

    val vpnService: VpnService?
        get() = m_parentService

    fun newVpnServiceBuilder(): VpnService.Builder {
        return m_parentService!!.newBuilder()
    }

    companion object {
        const val NOTIFICATION_ID = 7839214
        
        // The key for storing DaemonArgs in SharedPreferences
        const val DAEMON_ARGS = "daemonArgs"
        
        private const val LOG_TAG = "TunnelManager"
    }

    init {
        m_parentService = parentService
        m_isStopping = AtomicBoolean(false)
        m_isReconnecting = AtomicBoolean(false)
    }
}
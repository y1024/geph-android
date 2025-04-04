package io.geph.android

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File

/**
 * A daemon class that uses a [JsonObject] as its configuration.
 * The constructor immediately writes the config to disk and starts the daemon process.
 */

class GephDaemon(
    private val context: Context,
    private val config: JsonObject,
    private val rpc: Boolean,
) {
    private val daemonProcess: Process
    private var inputReader: BufferedReader? = null
    private var outputWriter: BufferedWriter? = null

    // Thread to read from stderr
    private var errorReaderThread: Thread? = null

    init {
        // 1) Convert the dynamic JSON object to a string
        val configString = Json.encodeToString(JsonObject.serializer(), config)
        Log.d("GephDaemon", "starting with $config")
        // 2) Write the config to the app's private files directory
        val configFile = File.createTempFile("geph_config_", ".json", context.cacheDir)
        configFile.writeText(configString)

        // 3) Prepare the process command
        val command = if (rpc) {
            listOf(
                context.applicationInfo.nativeLibraryDir + "/libgeph.so",
                "--config",
                configFile.absolutePath,
                "--stdio-rpc"
            )
        } else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            listOf(
                context.applicationInfo.nativeLibraryDir + "/libgeph.so",
                "--config",
                configFile.absolutePath,
                "--stdio-vpn"
            )
        } else {
            listOf(
                context.applicationInfo.nativeLibraryDir + "/libgeph.so",
                "--config",
                configFile.absolutePath,
            )
        }

        // 4) Spawn the daemon process
        daemonProcess = try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                ProcessBuilder(command)
                    .start()
            } else {
                ProcessBuilder(command)
                    .redirectInput(
                        if (rpc) {
                            ProcessBuilder.Redirect.PIPE
                        } else {
                            ProcessBuilder.Redirect.INHERIT
                        }
                    )
                    .start()
            }
        } catch (e: Exception) {
            throw RuntimeException("Failed to start Geph daemon process", e)
        }

        // 5) Set up readers/writers for stdin/stdout

        if (rpc) {
            inputReader = daemonProcess.inputStream.bufferedReader()
            outputWriter = daemonProcess.outputStream.bufferedWriter()
        }

        // 6) Set up a separate thread to continuously read stderr and log it
        errorReaderThread = Thread {
            daemonProcess.errorStream.bufferedReader().use { errorReader ->
                try {
                    var line: String?
                    while (errorReader.readLine().also { line = it } != null) {
                        Log.d("GephDaemon", line!!)
                    }
                } catch(e: Exception) {
                    Log.d("GephDaemon", "exited with $e")
                }
            }
        }.apply { start() }

    }

    /**
     * Send a single line to the daemon's stdin and read exactly one line from its stdout.
     *
     * @param line The line/string to send to the daemon.
     * @return The line read back from stdout, or `null` if the stream is closed.
     */
    @Synchronized
    fun rawStdioRpc(line: String): String? {
        outputWriter!!.write(line)
        outputWriter!!.newLine()
        outputWriter!!.flush()
        Log.d("stdio", line)

        // Read a single line from the daemon's stdout
        return inputReader!!.readLine()
    }

    fun uploadVpn(arr: ByteArray, len: Int) {
        daemonProcess.outputStream.write(len / 256)
        daemonProcess.outputStream.write(len % 256)
        daemonProcess.outputStream.write(arr, 0, len)
        daemonProcess.outputStream.flush()
    }

    fun downloadVpn(bts: ByteArray): Int {
        val a = daemonProcess.inputStream.read();
        val b = daemonProcess.inputStream.read();
        val len = a*256 + b;
        var n = 0;
        while (n < len) {
            n += daemonProcess.inputStream.read(bts, n, len - n);
        }
        return n
    }

    /**
     * Stops the daemon by destroying the underlying process.
     * Optionally interrupt/cleanup the stderr-reading thread if desired.
     */
    fun stopDaemon() {
        daemonProcess.destroy()
        errorReaderThread?.interrupt()
    }
}

package io.geph.android

import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.Closeable
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
    companion object {
        // Matches common ANSI CSI/OSC escape sequences so relayed logs stay readable.
        private val ANSI_ESCAPE_REGEX =
            Regex("""\u001B(?:\[[0-?]*[ -/]*[@-~]|\][^\u0007]*(?:\u0007|\u001B\\))""")

        private fun configureNoColor(processBuilder: ProcessBuilder): ProcessBuilder {
            processBuilder.environment().apply {
                put("NO_COLOR", "1")
                put("CLICOLOR", "0")
                put("CLICOLOR_FORCE", "0")
                put("TERM", "dumb")
            }
            return processBuilder
        }

        private fun stripAnsi(text: String): String = text.replace(ANSI_ESCAPE_REGEX, "")
    }

    private val configFile: File
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
        configFile = File.createTempFile("geph_config_", ".json", context.cacheDir).apply {
            deleteOnExit()
        }
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
                configureNoColor(ProcessBuilder(command))
                    .start()
            } else {
                configureNoColor(ProcessBuilder(command))
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
                        Log.d("GephDaemon", stripAnsi(line!!))
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
        return try {
            val writer = outputWriter ?: return null
            writer.write(line)
            writer.newLine()
            writer.flush()
            Log.d("stdio", line)

            // Read a single line from the daemon's stdout
            inputReader?.readLine()
        } catch (e: Exception) {
            Log.w("GephDaemon", "stdio rpc failed", e)
            null
        }
    }

    fun uploadVpn(arr: ByteArray, len: Int) {
        if (len <= 0) {
            return
        }
        daemonProcess.outputStream.write(len / 256)
        daemonProcess.outputStream.write(len % 256)
        daemonProcess.outputStream.write(arr, 0, len)
        daemonProcess.outputStream.flush()
    }

    fun downloadVpn(bts: ByteArray): Int {
        val a = daemonProcess.inputStream.read()
        val b = daemonProcess.inputStream.read()
        if (a < 0 || b < 0) {
            return -1
        }
        val len = a * 256 + b
        var n = 0
        while (n < len) {
            val read = daemonProcess.inputStream.read(bts, n, len - n)
            if (read < 0) {
                return -1
            }
            n += read
        }
        return n
    }

    val isAlive: Boolean
        get() = try {
            daemonProcess.exitValue()
            false
        } catch (_: IllegalThreadStateException) {
            true
        }

    fun waitForExit(): Int = daemonProcess.waitFor()

    private fun closeQuietly(closeable: Closeable?) {
        try {
            closeable?.close()
        } catch (_: Exception) {
        }
    }

    /**
     * Stops the daemon by destroying the underlying process.
     * Optionally interrupt/cleanup the stderr-reading thread if desired.
     */
    fun stopDaemon() {
        closeQuietly(inputReader)
        closeQuietly(outputWriter)
        daemonProcess.destroyForcibly()
        errorReaderThread?.interrupt()
        configFile.delete()
    }
}

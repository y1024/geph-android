package io.geph.android.tun2socks

class TunnelState private constructor() {
    @Volatile
    private var _tunnelManager: TunnelManager? = null
    @Volatile
    private var _startingTunnelManager = false

    var tunnelManager: TunnelManager?
        @Synchronized get() = _tunnelManager
        @Synchronized set(value) {
            _tunnelManager = value
            _startingTunnelManager = false
        }

    val startingTunnelManager: Boolean
        @Synchronized get() = _startingTunnelManager

    @Synchronized
    fun setStartingTunnelManager() {
        _startingTunnelManager = true
    }

    fun clone(): Any = throw CloneNotSupportedException()

    companion object {
        @Volatile
        private var instance: TunnelState? = null

        @JvmStatic
        fun getTunnelState(): TunnelState {
            return instance ?: synchronized(this) {
                instance ?: TunnelState().also { instance = it }
            }
        }
    }
}

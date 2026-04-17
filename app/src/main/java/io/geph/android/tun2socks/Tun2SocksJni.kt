package io.geph.android.tun2socks

import android.util.Log

object Tun2SocksJni {
    init {
        System.loadLibrary("tun2socks")
    }

    @JvmStatic
    external fun runTun2Socks(
        vpnInterfaceFileDescriptor: Int,
        vpnInterfaceMTU: Int,
        vpnIpAddress: String,
        vpnNetMask: String,
        socksServerAddress: String,
        dnsServerAddress: String,
        transparentDNS: Int
    ): Int

    @JvmStatic
    external fun terminateTun2Socks(): Int

    @JvmStatic
    fun logTun2Socks(level: String, channel: String, msg: String) {
        Log.i("Bridge", "$level ($channel): $msg")
    }
}

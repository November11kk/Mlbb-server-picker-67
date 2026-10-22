package com.mlbb.serverpicker

import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor

class MlbbVpnService : VpnService() {

    private var vpnInterface: ParcelFileDescriptor? = null

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {

        startVpn()

        return START_STICKY
    }

    private fun startVpn() {
        if (vpnInterface != null) {
            return
        }

        val builder = Builder()

        builder.setSession("MLBB Server Picker")
            .addAddress("10.10.0.2", 32)
            .addRoute("0.0.0.0", 0)
            .setBlocking(false)

        vpnInterface = builder.establish()
    }

    override fun onDestroy() {
        stopVpn()
        super.onDestroy()
    }

    private fun stopVpn() {
        vpnInterface?.close()
        vpnInterface = null
    }

    override fun onBind(intent: Intent?) = super.onBind(intent)
}

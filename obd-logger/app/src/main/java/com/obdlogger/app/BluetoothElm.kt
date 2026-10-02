package com.obdlogger.app

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import java.io.IOException
import java.util.UUID

/** Opens an RFCOMM (SPP) socket to a paired ELM327 adapter. */
object BluetoothElm {
    private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    /**
     * Cheap clones often fail the regular SDP-based connect; the insecure and
     * channel-1 reflection variants are the usual workarounds.
     */
    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice, onSocket: (BluetoothSocket) -> Unit): BluetoothSocket {
        val attempts = listOf<() -> BluetoothSocket>(
            { device.createRfcommSocketToServiceRecord(SPP_UUID) },
            { device.createInsecureRfcommSocketToServiceRecord(SPP_UUID) },
            {
                device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
                    .invoke(device, 1) as BluetoothSocket
            },
        )
        var last: Exception? = null
        for (create in attempts) {
            var socket: BluetoothSocket? = null
            try {
                socket = create()
                onSocket(socket)
                socket.connect()
                return socket
            } catch (e: Exception) {
                last = e
                try {
                    socket?.close()
                } catch (_: IOException) {
                }
            }
        }
        throw IOException("Не удалось подключиться к ${device.address}: ${last?.message}", last)
    }
}

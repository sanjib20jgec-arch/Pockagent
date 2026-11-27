package com.example.pocketagent

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets
import java.util.UUID

object OfflinePeerShareManager {

    private const val SERVICE_NAME = "PocketAgentOfflineShare"
    private val SERVICE_UUID: UUID = UUID.fromString("8f7d9e30-4c2b-4a8e-9f11-7d5b3e2a1c04")
    const val MAX_PAYLOAD_BYTES: Int = 512 * 1024

    fun hasBluetoothConnectPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED
    }

    @SuppressLint("MissingPermission")
    fun listPairedDeviceNames(context: Context): List<Pair<String, String>> {
        if (!hasBluetoothConnectPermission(context)) return emptyList()
        val adapter = getEnabledAdapter(context) ?: return emptyList()
        return adapter.bondedDevices
            ?.map { device -> (device.name ?: device.address) to device.address }
            ?.sortedBy { it.first.lowercase() }
            ?: emptyList()
    }

    @SuppressLint("MissingPermission")
    fun sendPayloadToDevice(
        context: Context,
        deviceAddress: String,
        jsonPayload: String,
    ): Result<Int> {
        val bytes = jsonPayload.toByteArray(StandardCharsets.UTF_8)
        if (bytes.isEmpty()) {
            return Result.failure(IllegalArgumentException("Payload must not be empty"))
        }
        if (bytes.size > MAX_PAYLOAD_BYTES) {
            return Result.failure(
                IllegalArgumentException("Payload exceeds $MAX_PAYLOAD_BYTES byte limit (${bytes.size} bytes)"),
            )
        }
        if (!hasBluetoothConnectPermission(context)) {
            return Result.failure(SecurityException("Missing BLUETOOTH_CONNECT permission"))
        }
        val adapter = getEnabledAdapter(context)
            ?: return Result.failure(IllegalStateException("Bluetooth adapter unavailable or disabled"))

        var socket: BluetoothSocket? = null
        return try {
            val device = adapter.getRemoteDevice(deviceAddress)
            socket = device.createRfcommSocketToServiceRecord(SERVICE_UUID)
            socket.connect()
            val out = DataOutputStream(BufferedOutputStream(socket.outputStream))
            out.writeInt(bytes.size)
            out.write(bytes)
            out.flush()
            Result.success(bytes.size)
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            runCatching { socket?.close() }
        }
    }

    @SuppressLint("MissingPermission")
    fun receiveSinglePayload(
        context: Context,
        timeoutMs: Int = 30_000,
    ): Result<String> {
        if (!hasBluetoothConnectPermission(context)) {
            return Result.failure(SecurityException("Missing BLUETOOTH_CONNECT permission"))
        }
        val adapter = getEnabledAdapter(context)
            ?: return Result.failure(IllegalStateException("Bluetooth adapter unavailable or disabled"))

        var serverSocket: BluetoothServerSocket? = null
        var clientSocket: BluetoothSocket? = null
        return try {
            serverSocket = adapter.listenUsingRfcommWithServiceRecord(SERVICE_NAME, SERVICE_UUID)
            clientSocket = serverSocket.accept(timeoutMs)
            val input = DataInputStream(BufferedInputStream(clientSocket.inputStream))
            val length = input.readInt()
            if (length <= 0 || length > MAX_PAYLOAD_BYTES) {
                return Result.failure(
                    IllegalArgumentException("Invalid incoming payload length: $length"),
                )
            }
            val buffer = ByteArray(length)
            input.readFully(buffer)
            Result.success(String(buffer, StandardCharsets.UTF_8))
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            runCatching { clientSocket?.close() }
            runCatching { serverSocket?.close() }
        }
    }

    private fun getEnabledAdapter(context: Context): BluetoothAdapter? {
        val manager = context.getSystemService(BluetoothManager::class.java) ?: return null
        val adapter = manager.adapter ?: return null
        return if (adapter.isEnabled) adapter else null
    }
}

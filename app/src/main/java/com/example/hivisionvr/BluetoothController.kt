package com.example.hivisionvr

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.util.Log
import java.io.IOException
import java.util.UUID
import java.util.concurrent.Executors

/**
 * Conecta via Bluetooth Clássico (SPP - Serial Port Profile) a um ESP32 que já foi
 * PAREADO PREVIAMENTE nas configurações do Android (o app não faz o pareamento, só abre
 * o socket serial com um dispositivo que já está na lista de "bonded devices").
 *
 * O ESP32 já está programado para ler 1 byte: 0x00 = desligar, 0x01 = ligar.
 */
class BluetoothController(private val listener: ConnectionListener) {

    interface ConnectionListener {
        fun onConnected(deviceName: String)
        fun onConnectionFailed(message: String)
        fun onDisconnected()
        fun onCommandSent(turnOn: Boolean)
    }

    private var socket: BluetoothSocket? = null
    private val executor = Executors.newSingleThreadExecutor()

    /**
     * Procura, entre os dispositivos já pareados, um cujo nome contenha [deviceNameContains]
     * (case-insensitive) e conecta nele. Ajuste esse nome para o nome que o seu ESP32
     * anuncia (o padrão de muitos firmwares é algo como "ESP32" ou o nome que você definiu
     * em BluetoothSerial.begin("NomeAqui") no código do ESP32).
     */
    @SuppressLint("MissingPermission") // permissão já checada antes de chamar este método
    fun connectToBondedDevice(adapter: BluetoothAdapter, deviceNameContains: String) {
        executor.execute {
            val device = adapter.bondedDevices.firstOrNull {
                it.name?.contains(deviceNameContains, ignoreCase = true) == true
            }

            if (device == null) {
                listener.onConnectionFailed(
                    "Nenhum dispositivo pareado com nome contendo '$deviceNameContains'. " +
                        "Pareie o ESP32 nas configurações de Bluetooth do Android antes de abrir o app."
                )
                return@execute
            }

            connect(device)
        }
    }

    @SuppressLint("MissingPermission")
    private fun connect(device: BluetoothDevice) {
        try {
            val newSocket = device.createRfcommSocketToServiceRecord(SPP_UUID)
            newSocket.connect()
            socket = newSocket
            listener.onConnected(device.name ?: device.address)
        } catch (e: IOException) {
            Log.e(TAG, "Falha ao conectar no ESP32", e)
            listener.onConnectionFailed("Falha ao conectar: ${e.message}")
            socket = null
        }
    }

    /**
     * true = envia o caractere ASCII '1' (liga), false = envia '0' (desliga).
     *
     * IMPORTANTE: manda o CARACTERE DE TEXTO '1'/'0' (0x31/0x30), não o byte binário
     * 1/0 (0x01/0x00). É isso que apps como o Serial Bluetooth Terminal mandam quando
     * você digita "1" e aperta enviar - e é isso que firmwares que fazem
     * `if (SerialBT.read() == '1')` esperam receber. Enviar o byte binário 0x01 nunca
     * bate com a comparação contra o caractere '1', então o ESP32 simplesmente ignora.
     */
    fun send(turnOn: Boolean) {
        val currentSocket = socket
        if (currentSocket == null) {
            listener.onConnectionFailed("Bluetooth não conectado - comando não enviado.")
            return
        }
        executor.execute {
            try {
                currentSocket.outputStream.write(if (turnOn) '1'.code else '0'.code)
                currentSocket.outputStream.flush()
                listener.onCommandSent(turnOn)
            } catch (e: IOException) {
                Log.e(TAG, "Falha ao enviar comando", e)
                listener.onConnectionFailed("Falha ao enviar comando: ${e.message}")
                listener.onDisconnected()
                socket = null
            }
        }
    }

    fun close() {
        executor.execute {
            try {
                socket?.close()
            } catch (e: IOException) {
                Log.e(TAG, "Erro ao fechar socket Bluetooth", e)
            }
            socket = null
        }
    }

    companion object {
        private const val TAG = "BluetoothController"
        // UUID padrão do perfil SPP (Serial Port Profile) - o mesmo usado pela imensa
        // maioria dos exemplos de Bluetooth Clássico para ESP32 (BluetoothSerial.h)
        private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }
}

package app.iandis.fluetooth

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.util.Log
import java.io.OutputStream
import java.lang.Exception
import java.util.UUID

class FluetoothManager(private val _adapter: BluetoothAdapter?) {

    // Standard SerialPortService ID
    private var _uuid: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    private var _connectedDevice: MutableList<BluetoothDevice> = mutableListOf()
    private var _socket: MutableList<BluetoothSocket> = mutableListOf()
    private val _executor: SerialExecutor = SerialExecutor()

    /**
     * @return **true** when enabled, **false** when disabled, **null** when not supported
     * */
    val isAvailable: Boolean get() = _adapter?.isEnabled ?: false

    /**
     * @return **true** when device connected, **false** when not connected
     * */

    fun isConnected(deviceAddress: String): Boolean {
        for (socket: BluetoothSocket in _socket) {
            if (socket.remoteDevice.address.equals(deviceAddress)) {
                return socket.isConnected
            }
        }
        return false
    }

    /**
     * @return **MutableList<Map<String, String>>** of connected device
     * */

    val connectedDevice: MutableList<Map<String, String>>
        get() = _connectedDevice.map {
            it.toMap()
        }.toMutableList()

    fun getAvailableDevices(): List<Map<String, String>> {
        val devicesMap: MutableList<Map<String, String>> = mutableListOf()
        val bondedDevices: Set<BluetoothDevice> = _adapter!!.bondedDevices
        if (bondedDevices.isNotEmpty()) {
            for (device: BluetoothDevice in bondedDevices) {
                devicesMap.add(device.toMap())
            }
        }
        return devicesMap
    }

    fun send(bytes: ByteArray, deviceAddress: String, onComplete: () -> Unit, onError: (Throwable) -> Unit) {
        // Find the connected device with the specified address
        val device = _connectedDevice.find { it.address == deviceAddress }

        if (device == null) {
            onError(Exception("No device connected!"))
            return
        }

        _executor.execute {
            try {
                if (!isConnected(deviceAddress)) {
                    disconnectDevice(deviceAddress)
                    onError(Exception("No device connected!"))
                    return@execute
                }

                // Find the socket associated with the device
                val socket = _socket.find { it.remoteDevice.address == deviceAddress }

                if (socket != null) {
                    val os: OutputStream = socket.outputStream
                    os.write(bytes)
                    os.flush()
                    onComplete()
                } else {
                    onError(Exception("Socket not found for device $deviceAddress"))
                }
            } catch (t: Throwable) {
                disconnectDevice(deviceAddress)
                onError(t)
            }
        }

    }

    @Synchronized
    fun connect(
        deviceAddress: String,
        onResult: (BluetoothDevice) -> Unit,
        onError: (Throwable) -> Unit
    ) {

        var currentDevice: BluetoothDevice? = null

        if (_socket.isNotEmpty()) {
            for (socket: BluetoothSocket in _socket) {
                if (socket.remoteDevice.address.equals(deviceAddress)) {
                    socket.connect()
                    if (socket.isConnected) {
                        onResult(socket.remoteDevice)
                        return
                    } else {
                        disconnectDevice(deviceAddress)
                        break
                    }
                }
            }
        }

        val bondedDevices: Set<BluetoothDevice> = _adapter!!.bondedDevices
        if (bondedDevices.isNotEmpty()) {
            for (device: BluetoothDevice in bondedDevices) {
                if (device.address.equals(deviceAddress)) {
                    currentDevice = device
                    break
                }
            }
        }

        if (currentDevice == null) {
            onError(Exception("Device not found"))
            return
        }

        _executor.execute {
            try {
                val mSocket = connect(currentDevice)
                _connectedDevice.add(currentDevice)
                _socket.add(mSocket)
                onResult(currentDevice)
            } catch (t: Throwable) {
                disconnectDevice(deviceAddress)
                onError(t)
            }
        }
    }

    private fun closeSocket() {
        for (socket: BluetoothSocket in _socket) {
            val os: OutputStream = socket.outputStream
            os.close()
            socket.close()
        }
    }

    private fun connect(device: BluetoothDevice): BluetoothSocket {
        val mSocket = device.createRfcommSocketToServiceRecord(_uuid)
        mSocket.connect()
        return mSocket
    }

    fun disconnect() {
        closeSocket()
        _socket.clear()
        _connectedDevice.clear()
    }

    fun disconnectDevice(deviceAddress: String) {
        for (socket: BluetoothSocket in _socket) {
            if (socket.remoteDevice.address.equals(deviceAddress)) {
                val os: OutputStream = socket.outputStream
                os.close()
                socket.close()
                _socket.remove(socket)
                _connectedDevice.remove(socket.remoteDevice)
                break
            }
        }
    }

    fun dispose() {
        disconnect()
        _executor.shutdown()
    }
}
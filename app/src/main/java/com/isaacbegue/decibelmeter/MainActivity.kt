package com.isaacbegue.decibelmeter

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.util.Log
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlin.concurrent.thread // Para el hilo de grabación
import kotlin.math.log10
import kotlin.math.sqrt
import android.widget.ProgressBar

data class DbReading(val timestamp: Long, val dbValue: Double)

class MainActivity : AppCompatActivity() {

    private lateinit var dbTextView: TextView
    private var audioRecord: AudioRecord? = null
    private var bufferSize: Int = 0
    private var isRecording = false
    private val sampleRate = 44100 // Tasa de muestreo común
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private val RECORD_AUDIO_PERMISSION_CODE = 1
    private var lastUiUpdateTime: Long = 0
    private val uiUpdateInterval: Long = 500 // Milisegundos (ajusta esto a tu gusto, 300-500ms suele ir bien)
    private val dbValuesList = mutableListOf<Double>() // Para guardar valores entre actualizaciones
    private lateinit var dbProgressBar: ProgressBar
    private lateinit var minDbTextView: TextView
    private lateinit var maxDbTextView: TextView
    private val recentReadings = ArrayDeque<DbReading>()
    private val timeWindowMillis: Long = 40000 // 40 segundos
    private val splOffset = 90.0 // Mismo offset que antes


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        dbTextView = findViewById(R.id.textViewDbValue)
        minDbTextView = findViewById(R.id.textViewMinDb)
        maxDbTextView = findViewById(R.id.textViewMaxDb)

        dbProgressBar = findViewById(R.id.progressBarDbLevel)

        requestAudioPermission()
    }

    private fun requestAudioPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            // Permiso no concedido, solicitarlo
            ActivityCompat.requestPermissions(this,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                RECORD_AUDIO_PERMISSION_CODE)
        } else {
            // Permiso ya concedido, iniciar grabación
            startAudioRecording()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int,
                                            permissions: Array<out String>,
                                            grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            RECORD_AUDIO_PERMISSION_CODE -> {
                if ((grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED)) {
                    // Permiso concedido por el usuario
                    startAudioRecording()
                } else {
                    // Permiso denegado
                    dbTextView.text = "Permiso Requerido"
                }
                return
            }
            // Añadir otros 'when' para otros requestCodes si los hubiera
        }
    }

    private fun startAudioRecording() {
        // Verificar permiso de nuevo por seguridad
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            // No deberíamos llegar aquí si la lógica de permisos es correcta, pero es bueno verificar
            dbTextView.text = "Error: Permiso no concedido"
            return
        }

        bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        // Asegurarse de que bufferSize es válido
        if (bufferSize == AudioRecord.ERROR_BAD_VALUE || bufferSize == AudioRecord.ERROR) {
            dbTextView.text = "Error: Tamaño de buffer inválido"
            return
        }

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC, // Fuente de audio: Micrófono
            sampleRate,
            channelConfig,
            audioFormat,
            bufferSize
        )

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            dbTextView.text = "Error: Falló inicialización de AudioRecord"
            return
        }

        isRecording = true
        audioRecord?.startRecording()

        // Iniciar hilo para leer y procesar audio
        thread(start = true) { // Importa kotlin.concurrent.thread
            processAudioStream()
        }
        dbTextView.text = "Escuchando..." // Mensaje inicial
    }

    private fun processAudioStream() {
        val audioBuffer = ShortArray(bufferSize / 2)
        val currentAudioRecord = audioRecord

        if (currentAudioRecord == null || currentAudioRecord.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            Log.e("DecibelMeter", "AudioRecord no está listo o ya se detuvo al iniciar el bucle.")
            isRecording = false
        }

        lastUiUpdateTime = System.currentTimeMillis()

        while (isRecording && currentAudioRecord != null && currentAudioRecord.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
            if (!isRecording) break

            val readResult = currentAudioRecord.read(audioBuffer, 0, audioBuffer.size)

            if (readResult > 0) {
                if (!isRecording) break

                val dbValue = calculateDb(audioBuffer, readResult)

                // --- Lógica de promediado/actualización ---
                synchronized(dbValuesList) { // Sincronizar acceso a la lista
                    dbValuesList.add(dbValue)
                }

                val currentTime = System.currentTimeMillis()
                if (currentTime - lastUiUpdateTime >= uiUpdateInterval) {
                    val valuesToProcess: List<Double>
                    synchronized(dbValuesList) { // Sincronizar acceso
                        valuesToProcess = ArrayList(dbValuesList) // Copiar valores
                        dbValuesList.clear() // Limpiar para el próximo intervalo
                    }

                    if (valuesToProcess.isNotEmpty()) {
                        // Calcular promedio o máximo. Usemos el máximo para picos de ruido.
                        // val avgDb = valuesToProcess.average()
                        val maxDb = valuesToProcess.maxOrNull() ?: -90.0 // Tomar el máximo del intervalo
                        val currentReading = DbReading(currentTime, maxDb) // Guardamos el max del intervalo
                        synchronized(recentReadings) { // Sincronizar acceso a la deque
                            recentReadings.addLast(currentReading) // Añadir al final
                        }
                        updateMinMaxDisplay(currentTime) // Llamar a la función que actualiza min/max

                        // Actualizar UI en el hilo principal
                        runOnUiThread {
                            if (isRecording && audioRecord != null) {
                                // *** MODIFICACIÓN PARA MOSTRAR dB ESTIMADO *** (Ver punto 2)
                                val estimatedSpl = maxDb + 90.0 // Añade un offset (ej. 90)
                                dbTextView.text = String.format("%.1f ~dB", estimatedSpl) // Mostrar 1 decimal y etiqueta diferente

                                // Actualizar ProgressBar (opcional, basado en el valor estimado)
                                val progress = (estimatedSpl).toInt().coerceIn(0, 120) // Asumiendo rango 0-120 dB SPL
                                dbProgressBar.progress = progress
                            }
                        }
                    }
                    lastUiUpdateTime = currentTime // Actualizar tiempo de la última actualización
                }
                // --- Fin lógica de promediado ---

            } else if (readResult < 0) {
                Log.e("DecibelMeter", "Error al leer AudioRecord: $readResult")
                isRecording = false
                runOnUiThread{ dbTextView.text = "Error lectura" }
                break
            }
        }

        Log.d("DecibelMeter", "Saliendo del bucle processAudioStream. isRecording=$isRecording")
        isRecording = false
    }

    private fun calculateDb(audioData: ShortArray, readSize: Int): Double {
        var sum: Double = 0.0
        for (i in 0 until readSize) {
            // Convertir a Double y sumar cuadrados
            sum += audioData[i].toDouble() * audioData[i].toDouble()
        }
        // Calcular RMS (Root Mean Square)
        val rms = sqrt(sum / readSize)

        // Evitar log10(0)
        if (rms == 0.0) {
            return -90.0 // O un valor muy bajo representativo de silencio
        }

        // Calcular dBFS (Decibels relative to Full Scale)
        // Usamos 32767.0 como referencia (valor máximo para un Short de 16 bits)
        val referenceAmplitude = 32767.0
        val dbValue = 20 * log10(rms / referenceAmplitude)

        // Si el valor es muy bajo, trúncalo para evitar -Infinity
        return if(dbValue < -90.0) -90.0 else dbValue
    }

    override fun onPause() {
        super.onPause()
        stopAudioRecording()
    }

    override fun onResume() {
        super.onResume()
        // Si el permiso ya está concedido, intentar iniciar de nuevo
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED) {
            startAudioRecording()
        } else if (!ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.RECORD_AUDIO)) {
            // El usuario denegó permanentemente, o es la primera vez. onCreate ya lo maneja.
            // Podrías mostrar aquí un mensaje si el usuario denegó permanentemente.
        } else {
            // El usuario denegó la última vez, pero no permanentemente. No hacemos nada aquí,
            // onCreate ya intentó pedirlo. Si quieres volver a pedirlo al volver a la app,
            // podrías llamar a requestAudioPermission() aquí, pero puede ser molesto.
        }
    }


    private fun stopAudioRecording() {
        // Asegurarse de que isRecording se ponga a false PRIMERO
        // para que el bucle en el hilo se detenga lo antes posible.
        isRecording = false

        // Usar una copia local y verificar nulidad antes de usar
        val recordToStop = audioRecord
        if (recordToStop != null) {
            try {
                // Verificar estado antes de intentar detener
                if (recordToStop.state == AudioRecord.STATE_INITIALIZED) {
                    recordToStop.stop()
                }
                recordToStop.release()
                Log.d("DecibelMeter", "AudioRecord detenido y liberado.")
            } catch (e: IllegalStateException) {
                Log.e("DecibelMeter", "Error al detener/liberar AudioRecord: ${e.message}")
                // Puede ocurrir si ya estaba detenido/liberado
            }
        }
        audioRecord = null // Poner a null después de liberar

        // Puedes resetear la UI aquí si quieres, en el hilo principal
        runOnUiThread {
            if (::dbTextView.isInitialized) { // Evitar crash si la vista no está lista
                dbTextView.text = "Detenido"
            }
            if (::dbProgressBar.isInitialized) {
                dbProgressBar.progress = 0
            }
        }
    }

    // Es buena práctica liberar en onDestroy también, aunque onPause debería cubrirlo
    override fun onDestroy() {
        super.onDestroy()
        stopAudioRecording() // Asegura la liberación si la app se destruye directamente
    }

    private fun updateMinMaxDisplay(currentTime: Long) {
        val cutoffTime = currentTime - timeWindowMillis // Límite de tiempo (hace 40s)
        var minDbValue: Double? = null
        var maxDbValue: Double? = null

        synchronized(recentReadings) { // Necesario sincronizar
            // 1. Eliminar lecturas viejas del principio de la deque
            while (recentReadings.isNotEmpty() && recentReadings.first().timestamp < cutoffTime) {
                recentReadings.removeFirst()
            }

            // 2. Encontrar min y max en las lecturas restantes (si hay alguna)
            if (recentReadings.isNotEmpty()) {
                // minDbValue = recentReadings.minByOrNull { it.dbValue }?.dbValue // Más eficiente
                // maxDbValue = recentReadings.maxByOrNull { it.dbValue }?.dbValue // Más eficiente
                // O usando funciones más simples si prefieres:
                minDbValue = recentReadings.minOfOrNull { it.dbValue }
                maxDbValue = recentReadings.maxOfOrNull { it.dbValue }
            }
        } // Fin del bloque synchronized

        // 3. Actualizar los TextViews en el hilo principal
        runOnUiThread {
            // Usar 'let' para ejecutar código solo si no es nulo
            // y el operador Elvis '?:' para el caso nulo.
            val minText = minDbValue?.let { value ->
                String.format("%.1f", value + splOffset) // 'value' aquí es un Double no nulo
            } ?: "--" // Si minDbValue era null, se usa "--"

            val maxText = maxDbValue?.let { value ->
                String.format("%.1f", value + splOffset) // 'value' aquí es un Double no nulo
            } ?: "--" // Si maxDbValue era null, se usa "--"

            minDbTextView.text = "Min (40s): $minText"
            maxDbTextView.text = "Max (40s): $maxText"
        }
    }
}
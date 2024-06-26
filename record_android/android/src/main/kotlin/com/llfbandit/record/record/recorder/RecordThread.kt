package com.llfbandit.record.record.recorder

import com.llfbandit.record.Utils
import com.llfbandit.record.record.AudioEncoder
import com.llfbandit.record.record.PCMReader
import com.llfbandit.record.record.RecordConfig
import com.llfbandit.record.record.RecordState
import com.llfbandit.record.record.encoder.EncoderListener
import com.llfbandit.record.record.encoder.IEncoder
import com.llfbandit.record.record.format.AacFormat
import com.llfbandit.record.record.format.AmrNbFormat
import com.llfbandit.record.record.format.AmrWbFormat
import com.llfbandit.record.record.format.FlacFormat
import com.llfbandit.record.record.format.Format
import com.llfbandit.record.record.format.OpusFormat
import com.llfbandit.record.record.format.PcmFormat
import com.llfbandit.record.record.format.WaveFormat
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class RecordThread(
    private val config: RecordConfig,
    private val recorderListener: OnAudioRecordListener
) : EncoderListener {
    private var reader: PCMReader? = null
    private var audioEncoder: IEncoder? = null

    // Signals whether a recording is in progress (true) or not (false).
    private val isRecording = AtomicBoolean(false)

    // Signals whether a recording is paused (true) or not (false).
    private val isPaused = AtomicBoolean(false)
    private var hasBeenCanceled = false

    private val executorService = Executors.newSingleThreadExecutor()
    private val completion = CountDownLatch(1)

    override fun onEncoderDataSize(): Int = reader?.bufferSize ?: 0

    override fun onEncoderDataNeeded(byteBuffer: ByteBuffer): Int {
        return reader?.read(byteBuffer) ?: 0
    }

    override fun onEncoderFailure(ex: Exception) {
        recorderListener.onFailure(ex)
    }

    override fun onEncoderStream(bytes: ByteArray) {
        recorderListener.onAudioChunk(bytes)
    }

    override fun onEncoderStop() {
        audioEncoder?.release()

        reader?.stop()
        reader?.release()
        reader = null

        if (hasBeenCanceled) {
            Utils.deleteFile(config.path)
        }

        updateState(RecordState.STOP)

        completion.countDown()
        executorService.shutdown()
    }

    fun isRecording(): Boolean {
        return audioEncoder != null && isRecording.get()
    }

    fun isPaused(): Boolean {
        return audioEncoder != null && isPaused.get()
    }

    fun pauseRecording() {
        if (isRecording()) {
            audioEncoder?.pause()
            updateState(RecordState.PAUSE)
        }
    }

    fun resumeRecording() {
        if (isPaused()) {
            audioEncoder?.resume()
            updateState(RecordState.RECORD)
        }
    }

    fun stopRecording() {
        if (isRecording()) {
            audioEncoder?.stop()
        }
    }

    fun cancelRecording() {
        if (isRecording()) {
            hasBeenCanceled = true
            audioEncoder?.stop()
        } else {
            Utils.deleteFile(config.path)
        }
    }

    fun getAmplitude(): Double = reader?.getAmplitude() ?: -160.0

    fun startRecording() {
        executorService.execute {
            try {
                val format = selectFormat()
                val (encoder, adjustedFormat) = format.getEncoder(config, this)

                reader = PCMReader(config, adjustedFormat)
                reader!!.start()

                audioEncoder = encoder
                audioEncoder!!.start()

                updateState(RecordState.RECORD)

                completion.await()
            } catch (ex: Exception) {
                recorderListener.onFailure(ex)
                onEncoderStop()
            }
        }
    }

    private fun selectFormat(): Format {
        when (config.encoder) {
            AudioEncoder.aacLc, AudioEncoder.aacEld, AudioEncoder.aacHe -> return AacFormat()
            AudioEncoder.amrNb -> return AmrNbFormat()
            AudioEncoder.amrWb -> return AmrWbFormat()
            AudioEncoder.flac -> return FlacFormat()
            AudioEncoder.pcm16bits -> return PcmFormat()
            AudioEncoder.opus -> return OpusFormat()
            AudioEncoder.wav -> return WaveFormat()
        }
        throw Exception("Unknown format: " + config.encoder)
    }

    private fun updateState(state: RecordState) {
        when (state) {
            RecordState.PAUSE -> {
                isRecording.set(true)
                isPaused.set(true)
                recorderListener.onPause()
            }

            RecordState.RECORD -> {
                isRecording.set(true)
                isPaused.set(false)
                recorderListener.onRecord()
            }

            RecordState.STOP -> {
                isRecording.set(false)
                isPaused.set(false)
                recorderListener.onStop()
            }
        }
    }
}
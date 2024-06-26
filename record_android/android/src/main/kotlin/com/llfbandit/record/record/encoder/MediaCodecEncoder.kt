package com.llfbandit.record.record.encoder

import android.media.MediaCodec
import android.media.MediaCodec.CodecException
import android.media.MediaFormat
import com.llfbandit.record.record.container.IContainerWriter

class MediaCodecEncoder(
    encoder: String,
    mediaFormat: MediaFormat,
    private val listener: EncoderListener,
    private val container: IContainerWriter,
) : IEncoder, MediaCodec.Callback() {

    private val codec = createCodec(encoder, mediaFormat)
    private var trackIndex = -1
    private var recordStopped = false
    private var recordPaused = false

    override fun start() {
        codec.setCallback(this)
        codec.start()
    }

    override fun pause() {
        recordPaused = true
    }

    override fun resume() {
        recordPaused = false
    }

    override fun stop() {
        recordPaused = false
        recordStopped = true
    }

    override fun release() {}

    private fun createCodec(encoder: String, mediaFormat: MediaFormat): MediaCodec {
        var mediaCodec: MediaCodec? = null
        try {
            mediaCodec = MediaCodec.createByCodecName(encoder)
            mediaCodec.configure(mediaFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            return mediaCodec
        } catch (e: Exception) {
            mediaCodec?.release()
            throw e
        }
    }

    private fun internalStop() {
        codec.stop()
        codec.release()
        container.stop()
        container.release()

        listener.onEncoderStop()
    }

    //////////////////////////////////////////////////////////
    // MediaCodec.Callback
    //////////////////////////////////////////////////////////
    override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
        try {
            trackIndex = container.addTrack(format)
            container.start()
        } catch (e: Exception) {
            listener.onEncoderFailure(e)
            internalStop()
        }
    }

    override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
        if (recordPaused) {
            codec.queueInputBuffer(index, 0, 0, 0, 0)
            return
        }

        try {
            val byteBuffer = codec.getInputBuffer(index) ?: return
            val resultBytes = listener.onEncoderDataNeeded(byteBuffer)
            val flags = if (recordStopped) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0

            codec.queueInputBuffer(index, 0, resultBytes, 0, flags)
        } catch (e: Exception) {
            listener.onEncoderFailure(e)
            internalStop()
        }
    }

    override fun onOutputBufferAvailable(
        codec: MediaCodec,
        index: Int,
        info: MediaCodec.BufferInfo
    ) {
        try {
            val byteBuffer = codec.getOutputBuffer(index)
            if (byteBuffer != null) {
                if (!container.isStream()) {
                    container.writeSampleData(trackIndex, byteBuffer, info)
                } else {
                    listener.onEncoderStream(container.writeStream(trackIndex, byteBuffer, info))
                }
            }
            codec.releaseOutputBuffer(index, false)
            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                internalStop()
            }
        } catch (e: Exception) {
            listener.onEncoderFailure(e)
            internalStop()
        }
    }

    override fun onError(codec: MediaCodec, e: CodecException) {
        listener.onEncoderFailure(e)
        internalStop()
    }
}
package develop.tomo1139.mediacodecmoviecutter.media

import android.media.*
import android.media.MediaExtractor.SEEK_TO_CLOSEST_SYNC
import develop.tomo1139.mediacodecmoviecutter.util.Logger
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer


class ResampledRawAudioExtractor(inputFilePath: String, outputFilePath: String, private val startMs: Long, private val endMs: Long) {
    private val audioExtractor = MediaExtractor()
    private val audioTrackIdx: Int
    private val inputAudioFormat: MediaFormat
    private val audioChannelCount: Int

    private val audioDecoder: MediaCodec

    private var isAudioExtractEnd = false
    private var isAudioDecodeEnd = false

    private val isAllEnd: Boolean
        get() = isAudioExtractEnd && isAudioDecodeEnd

    private var rawAudioFileOutputStream: FileOutputStream? = null

    init {
        val outputFile = File(outputFilePath)
        if (outputFile.exists()) {
            outputFile.delete()
        }
        try {
            rawAudioFileOutputStream = FileOutputStream(outputFile,false)
            //rawAudioFileOutputStream = openFileOutput("output.wav", Context.MODE_PRIVATE) // TODO
        } catch (e: Exception) {
            throw RuntimeException("file open error")
        }
        audioExtractor.setDataSource(inputFilePath)
        audioTrackIdx = getAudioTrackIdx(audioExtractor)
        if (audioTrackIdx == -1) {
            Logger.e("audio not found")
            throw RuntimeException("audio not found")
        }
        audioExtractor.selectTrack(audioTrackIdx)
        inputAudioFormat = audioExtractor.getTrackFormat(audioTrackIdx)
        Logger.e("inputAudioFormat: $inputAudioFormat")

        val inputAudioMime = inputAudioFormat.getString(MediaFormat.KEY_MIME)
        audioChannelCount = inputAudioFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        if (audioChannelCount != 1 && audioChannelCount != 2) {
            throw RuntimeException("invalid channel count $audioChannelCount")
        }

        audioDecoder = MediaCodec.createDecoderByType(inputAudioMime)
        audioDecoder.configure(inputAudioFormat, null, null, 0)
    }

    fun extractResampledRawAudio(onProgressUpdated: (progress: String) -> Unit) {
        audioDecoder.start()

        audioExtractor.seekTo(startMs*1000, SEEK_TO_CLOSEST_SYNC)

        onProgressUpdated("0 %")

        while (!isAllEnd) {
            if (!isAudioExtractEnd) { isAudioExtractEnd = extract(audioExtractor, audioDecoder, onProgressUpdated) }
            if (!isAudioDecodeEnd) { isAudioDecodeEnd = decode(audioDecoder) }
        }

        audioExtractor.release()
        audioDecoder.stop()
        audioDecoder.release()
        try {
            rawAudioFileOutputStream?.close()
        } catch (e: Exception) {
            throw RuntimeException("rawAudioFileOutputStream close error")
        }
    }

    private fun extract(extractor: MediaExtractor, decoder: MediaCodec, onProgressUpdated: ((progress: String) -> Unit)? = null): Boolean {
        var isExtractEnd = false
        val inputBufferIdx = decoder.dequeueInputBuffer(CODEC_TIMEOUT_IN_US)
        if (inputBufferIdx >= 0) {
            val inputBuffer = decoder.getInputBuffer(inputBufferIdx) as ByteBuffer
            val sampleSize = extractor.readSampleData(inputBuffer, 0)
            if (sampleSize > 0) {
                if (extractor.sampleTime < endMs*1000) {
                    val progress = (extractor.sampleTime-startMs*1000)*100 / (endMs*1000 - startMs*1000)
                    onProgressUpdated?.invoke("$progress %")

                    decoder.queueInputBuffer(inputBufferIdx, 0, sampleSize, extractor.sampleTime, extractor.sampleFlags)
                } else {
                    Logger.e("isExtractEnd = true")
                    isExtractEnd = true
                    decoder.queueInputBuffer(inputBufferIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                }
            } else {
                Logger.e("isExtractEnd = true")
                isExtractEnd = true
                decoder.queueInputBuffer(inputBufferIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            }

            if (!isExtractEnd) {
                extractor.advance()
            }
        }
        return isExtractEnd
    }

    private fun decode(decoder: MediaCodec): Boolean {
        var isDecodeEnd = false
        val decoderOutputBufferInfo = MediaCodec.BufferInfo()
        val decoderOutputBufferIdx = decoder.dequeueOutputBuffer(decoderOutputBufferInfo, CODEC_TIMEOUT_IN_US)

        if (decoderOutputBufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
            Logger.e("isDecodeEnd = true")
            isDecodeEnd = true
        }
        if (decoderOutputBufferIdx >= 0) {
            val decoderOutputBuffer = (decoder.getOutputBuffer(decoderOutputBufferIdx) as ByteBuffer).duplicate()
            decoderOutputBuffer.position(decoderOutputBufferInfo.offset)
            decoderOutputBuffer.limit(decoderOutputBufferInfo.offset + decoderOutputBufferInfo.size)

            val decoderOutputData = ByteArray(decoderOutputBufferInfo.size)
            val oldPosition = decoderOutputBuffer?.position() ?: 0
            decoderOutputBuffer?.get(decoderOutputData)
            decoderOutputBuffer?.position(oldPosition)

            val monoData = if (audioChannelCount == 2) {
                val shortArray = ShortArray(decoderOutputData.size / 4) {
                    val left = decoderOutputData[it * 4] + (decoderOutputData[(it * 4) + 1].toInt() shl 8)
                    val right = decoderOutputData[it * 4 + 2] + (decoderOutputData[(it * 4) + 3].toInt() shl 8)
                    ((left + right) / 2).toShort()
                }
                ByteArray(shortArray.size * 2) {
                    if (it % 2 == 0) {
                        (shortArray[it / 2].toInt() and 0xff).toByte()
                    } else {
                        ((shortArray[it / 2].toInt() shr 8) and 0xff).toByte()
                    }
                }
            } else {
                decoderOutputData
            }

            try {
                rawAudioFileOutputStream?.write(monoData)
            } catch (e: Exception) {
                throw RuntimeException("raw audio write error")
            }

            decoder.releaseOutputBuffer(decoderOutputBufferIdx, false)
        }
        return isDecodeEnd
    }

    private fun getAudioTrackIdx(extractor: MediaExtractor): Int {
        for (idx in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(idx)
            val mime = format.getString(MediaFormat.KEY_MIME)
            if (mime?.startsWith("audio") == true) {
                return idx
            }
        }
        return -1
    }

    companion object {
        private const val CODEC_TIMEOUT_IN_US = 10000L
    }
}
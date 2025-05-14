package com.sherpaonnxofflinetts

import com.facebook.react.bridge.*
import com.facebook.react.modules.core.DeviceEventManagerModule
import com.k2fsa.sherpa.onnx.*
import android.content.res.AssetManager
import kotlin.concurrent.thread
import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

class ModelLoader(private val context: Context) {

    /**
     * Copies a file from the assets directory to the internal storage.
     *
     * @param assetPath The path to the asset in the assets directory.
     * @param outputFileName The name of the file in internal storage.
     * @return The absolute path to the copied file.
     * @throws IOException If an error occurs during file operations.
     */
    @Throws(IOException::class)
    fun loadModelFromAssets(assetPath: String, outputFileName: String): String {
        // Open the asset as an InputStream
        val assetManager = context.assets
        val inputStream = assetManager.open(assetPath)

        // Create a file in the app's internal storage
        val outFile = File(context.filesDir, outputFileName)
        FileOutputStream(outFile).use { output ->
            inputStream.copyTo(output)
        }

        // Close the InputStream
        inputStream.close()

        // Return the absolute path to the copied file
        return outFile.absolutePath
    }

    /**
     * Copies an entire directory from the assets to internal storage.
     *
     * @param assetDir The directory path in the assets.
     * @param outputDir The directory path in internal storage.
     * @throws IOException If an error occurs during file operations.
     */
    @Throws(IOException::class)
    fun copyAssetDirectory(assetDir: String, outputDir: File) {
        val assetManager = context.assets
        val files = assetManager.list(assetDir) ?: return

        if (!outputDir.exists()) {
            outputDir.mkdirs()
        }

        for (file in files) {
            val assetPath = if (assetDir.isEmpty()) file else "$assetDir/$file"
            val outFile = File(outputDir, file)

            if (assetManager.list(assetPath)?.isNotEmpty() == true) {
                // It's a directory
                copyAssetDirectory(assetPath, outFile)
            } else {
                // It's a file
                assetManager.open(assetPath).use { inputStream ->
                    FileOutputStream(outFile).use { outputStream ->
                        inputStream.copyTo(outputStream)
                    }
                }
            }
        }
    }
}


class TTSManagerModule(private val reactContext: ReactApplicationContext) : ReactContextBaseJavaModule(reactContext) {

    private var tts: OfflineTts? = null
    private var realTimeAudioPlayer: AudioPlayer? = null
    private val modelLoader = ModelLoader(reactContext)

    override fun getName(): String {
        return "TTSManager"
    }

    // Initialize TTS and Audio Player
    @ReactMethod
    fun initializeTTS(sampleRate: Double, channels: Int, modelId: String) {
        // Setup Audio Player
        realTimeAudioPlayer = AudioPlayer(sampleRate.toInt(), channels, object : AudioPlayerDelegate {
            override fun didUpdateVolume(volume: Float) {
                sendVolumeUpdate(volume)
            }
        })

        // Determine model paths based on modelId
        
        val modelDirAssetPath = "models"
        val modelDirInternal = File(reactContext.filesDir, "models")
        modelLoader.copyAssetDirectory(modelDirAssetPath, modelDirInternal)
        val modelPath = File(modelDirInternal, modelId).absolutePath
        val tokensPath = File(modelDirInternal, "tokens.txt").absolutePath
        val dataDirPath = File(modelDirInternal, "espeak-ng-data").absolutePath // Directory copy handled above

        // Build OfflineTtsConfig using the helper function
        val config = OfflineTtsConfig(
            model=OfflineTtsModelConfig(
              vits=OfflineTtsVitsModelConfig(
                model=modelPath,
                tokens=tokensPath,
                dataDir=dataDirPath,
              ),
              numThreads=1,
              debug=true,
            )
          )

        // Initialize sherpa-onnx offline TTS
        tts = OfflineTts(config=config)

        // Start the audio player
        realTimeAudioPlayer?.start()
    }

    // Generate and Play method exposed to React Native
    @ReactMethod
    fun generateAndPlay(text: String, sid: Int, speed: Double, promise: Promise) {
        val trimmedText = text.trim()
        if (trimmedText.isEmpty()) {
            promise.reject("EMPTY_TEXT", "Input text is empty")
            return
        }

        val sentences = splitText(trimmedText, 15)
        // thread {
            try {
                for (sentence in sentences) {
                    val processedSentence = if (sentence.endsWith(".")) sentence else "$sentence."
                    generateAudio(processedSentence, sid, speed.toFloat())
                }
                // Once done generating and enqueueing all audio, resolve the promise
                promise.resolve("Audio generated and played successfully")
            } catch (e: Exception) {
                promise.reject("GENERATION_ERROR", "Error during audio generation: ${e.message}")
            }
        // }
    }

    // Deinitialize method exposed to React Native
    @ReactMethod
    fun deinitialize() {
        realTimeAudioPlayer?.stopPlayer()
        realTimeAudioPlayer = null
        tts?.release()
        tts = null
    }

    // Helper: split text into manageable chunks similar to iOS logic
    private fun splitText(text: String, maxWords: Int): List<String> {
        val sentences = mutableListOf<String>()
        val words = text.split("\\s+".toRegex()).filter { it.isNotEmpty() }
        var currentIndex = 0
        val totalWords = words.size

        while (currentIndex < totalWords) {
            val endIndex = (currentIndex + maxWords).coerceAtMost(totalWords)
            var chunk = words.subList(currentIndex, endIndex).joinToString(" ")

            val lastPeriod = chunk.lastIndexOf('.')
            val lastComma = chunk.lastIndexOf(',')

            when {
                lastPeriod != -1 -> {
                    val sentence = chunk.substring(0, lastPeriod + 1).trim()
                    sentences.add(sentence)
                    currentIndex += sentence.split("\\s+".toRegex()).size
                }
                lastComma != -1 -> {
                    val sentence = chunk.substring(0, lastComma + 1).trim()
                    sentences.add(sentence)
                    currentIndex += sentence.split("\\s+".toRegex()).size
                }
                else -> {
                    sentences.add(chunk.trim())
                    currentIndex += maxWords
                }
            }
        }

        return sentences
    }

    private fun generateAudio(text: String, sid: Int, speed: Float) {
        val startTime = System.currentTimeMillis()
        val audio = tts?.generate(text, sid, speed)
        val endTime = System.currentTimeMillis()
        val generationTime = (endTime - startTime) / 1000.0
        println("Time taken for TTS generation: $generationTime seconds")

        if (audio == null) {
            println("Error: TTS was never initialized or audio generation failed")
            return
        }
        realTimeAudioPlayer?.enqueueAudioData(audio.samples, audio.sampleRate)
    }

    private fun sendVolumeUpdate(volume: Float) {
        // Emit the volume to JavaScript
        if (reactContext.hasActiveCatalystInstance()) {
            val params = Arguments.createMap()
            params.putDouble("volume", volume.toDouble())
            reactContext
                .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
                .emit("VolumeUpdate", params)
        }
    }
}
    // private fun splitText(text: String, maxWords: Int): List<String> {
    //     val sentences = mutableListOf<String>()
    //     // Split text into paragraphs using newline characters
    //     val paragraphs = text.split("\n").map { it.trim() }.filter { it.isNotEmpty() }

    //     for (paragraph in paragraphs) {
    //         val words = paragraph.split("\\s+".toRegex()).filter { it.isNotEmpty() }
    //         var currentIndex = 0
    //         val totalWords = words.size

    //         while (currentIndex < totalWords) {
    //             val endIndex = (currentIndex + maxWords).coerceAtMost(totalWords)
    //             val chunkWords = words.subList(currentIndex, endIndex)
    //             var chunk = chunkWords.joinToString(" ")

    //             // Check for the last punctuation mark in order: . ; ,
    //             val lastPeriod = chunk.lastIndexOf('.')
    //             val lastSemicolon = chunk.lastIndexOf(';')
    //             val lastComma = chunk.lastIndexOf(',')

    //             val splitIndex = when {
    //                 lastPeriod != -1 -> lastPeriod + 1
    //                 lastSemicolon != -1 -> lastSemicolon + 1
    //                 lastComma != -1 -> lastComma + 1
    //                 else -> -1
    //             }

    //             if (splitIndex != -1) {
    //                 // Split at the punctuation mark
    //                 val sentence = chunk.substring(0, splitIndex).trim()
    //                 sentences.add(sentence)
    //                 // Move currentIndex by the number of words in the extracted sentence
    //                 currentIndex += sentence.split("\\s+".toRegex()).size
    //             } else {
    //                 // No punctuation found; add the entire chunk
    //                 sentences.add(chunk.trim())
    //                 currentIndex += maxWords
    //             }
    //         }
    //     }

    //     return sentences
    // }
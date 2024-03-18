package jp.espresso3389.content_resolver

import android.net.Uri
import android.os.Handler
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer

/** ContentResolverPlugin */
class ContentResolverPlugin: FlutterPlugin, MethodCallHandler {
  /// The MethodChannel that will the communication between Flutter and native Android
  ///
  /// This local reference serves to register the plugin with the Flutter Engine and unregister it
  /// when the Flutter Engine is detached from the Activity
  private lateinit var channel : MethodChannel
  private lateinit var flutterPluginBinding: FlutterPlugin.FlutterPluginBinding

  override fun onAttachedToEngine(flutterPluginBinding: FlutterPlugin.FlutterPluginBinding) {
    this.flutterPluginBinding = flutterPluginBinding
    channel = MethodChannel(flutterPluginBinding.binaryMessenger, "content_resolver")
    channel.setMethodCallHandler(this)
  }

  override fun onMethodCall(call: MethodCall, result: Result) {
    var address = 0L
    try {
      when (call.method) {
          "getContent" -> {
            val uri = Uri.parse(call.arguments as String)
            openInputStream(uri).use {
              val buffer = ByteArrayOutputStream()
              it.copyTo(buffer)
              val (bufAddress, byteBuffer) = allocBuffer(buffer.size())
              address = bufAddress
              byteBuffer.put(buffer.toByteArray())
              result.success(hashMapOf("address" to address, "length" to buffer.size(), "mimeType" to getMimeType(uri), "fileName" to getFileName(uri)))
            }
          }
          "writeContent" -> {
            openOutputStream(Uri.parse(call.argument<String>("uri") as String), call.argument<String>("mode") as String).use {
              it.write(call.argument<ByteArray>("bytes") as ByteArray)
            }
            result.success(0)
          }
          "releaseBuffer" -> {
            releaseBuffer(call.arguments as Long)
            result.success(0)
          }
          "saveContentToFile" -> {
            val uri = Uri.parse(call.argument<String>("uri") as String)
            val file = File(call.argument<String>("filePath") as String)
            openInputStream(uri).use { input ->
              FileOutputStream(file).use { output ->
                input.copyTo(output)
              }
            }
            result.success(hashMapOf("mimeType" to getMimeType(uri), "fileName" to getFileName(uri)))
          }
          "getContentMetadata" -> {
            val uri = Uri.parse(call.arguments as String)
            result.success(hashMapOf("mimeType" to getMimeType(uri), "fileName" to getFileName(uri)))
          }
          "streamContent" -> {
            streamContent(call)
          }
          else -> {
            result.notImplemented()
          }
      }
    } catch (e: Exception) {
      releaseBuffer(address)
      result.error("exception", "Internal error.", e)
    }
  }

  private fun openInputStream(uri: Uri): InputStream {
    val cr = flutterPluginBinding.applicationContext.contentResolver
    return BufferedInputStream(ParcelFileDescriptor.AutoCloseInputStream(cr.openFileDescriptor(uri, "r")))
  }

  private fun getMimeType(uri: Uri): String? {
    val cr = flutterPluginBinding.applicationContext.contentResolver
    return cr.getType(uri)
  }

  private fun getFileName(uri: Uri): String? {
    val cr = flutterPluginBinding.applicationContext.contentResolver
    return cr.query(uri, null, null, null, null)?.use { cursor ->
      val nameColumnIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
      cursor.moveToFirst()
      return cursor.getString(nameColumnIndex)
    }
  }

  private fun openOutputStream(uri: Uri, mode: String): OutputStream {
    val cr = flutterPluginBinding.applicationContext.contentResolver
    return BufferedOutputStream(ParcelFileDescriptor.AutoCloseOutputStream(cr.openFileDescriptor(uri, mode)))
  }

  override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
    channel.setMethodCallHandler(null)
  }

  private fun allocBuffer(size: Int): Pair<Long, ByteBuffer> {
    val address = ByteBufferHelper.malloc(size.toLong())
    val bb = ByteBufferHelper.newDirectBuffer(address, size.toLong())
    return address to bb
  }

  private fun releaseBuffer(address: Long) {
    ByteBufferHelper.free(address)
  }

  private fun streamContent(call: MethodCall) = runBlocking {
    val id = call.argument<Int>("id") as Int
    val uri = Uri.parse(call.argument<String>("uri") as String)
    val bufferSize = call.argument<Int>("bufferSize") as Int
    readBytes(openInputStream(uri), 0, id, ByteArray(bufferSize))
  }

  private fun readBytes(input: InputStream, bytesReadSoFar: Int, id: Int, buffer: ByteArray) {
    val length = input.read(buffer)
    if (length < 0) {
      input.close()
      channel.invokeMethod("close",
        hashMapOf("id" to id, "totalSize" to bytesReadSoFar))
    } else {
      channel.invokeMethod("data",
        hashMapOf("id" to id, "offset" to bytesReadSoFar, "data" to buffer.sliceArray(0 until length)),
        object: Result {
          override fun success(result: Any?) {
            this@ContentResolverPlugin.run {
              readBytes(input, bytesReadSoFar + length, id, buffer)
            }
          }

          override fun error(errorCode: String, errorMessage: String?, errorDetails: Any?) {
          }

          override fun notImplemented() {
          }
        })
    }
  }
}

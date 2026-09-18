package com.example

import android.app.DownloadManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.viewinterop.AndroidView
import com.example.ui.theme.MyApplicationTheme
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

class MainActivity : ComponentActivity() {

  private var fileUploadCallback: ValueCallback<Array<Uri>>? = null

  private val fileChooserLauncher: ActivityResultLauncher<Intent> =
    registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
      if (fileUploadCallback == null) return@registerForActivityResult

      val results: Array<Uri>? = when {
        result.resultCode == RESULT_OK && result.data != null -> {
          val data = result.data
          val clipData = data?.clipData
          if (clipData != null && clipData.itemCount > 0) {
            val uriList = ArrayList<Uri>()
            for (i in 0 until clipData.itemCount) {
              val uri = clipData.getItemAt(i).uri
              if (uri != null) uriList.add(uri)
            }
            uriList.toTypedArray()
          } else {
            val singleUri = data?.data
            if (singleUri != null) arrayOf(singleUri) else null
          }
        }
        else -> null
      }

      fileUploadCallback?.onReceiveValue(results)
      fileUploadCallback = null
    }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()

    setContent {
      MyApplicationTheme {
        var webViewInstance by remember { mutableStateOf<WebView?>(null) }

        BackHandler(enabled = webViewInstance != null) {
          val wv = webViewInstance
          if (wv != null && wv.canGoBack()) {
            wv.goBack()
          } else {
            finish()
          }
        }

        Box(
          modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0F172A))
            .statusBarsPadding()
        ) {
          PdfWebViewContainer(
            onWebViewCreated = { webViewInstance = it },
            onOpenFileChooser = { callback, fileChooserParams ->
              fileUploadCallback?.onReceiveValue(null)
              fileUploadCallback = callback

              val intent = try {
                fileChooserParams?.createIntent()?.apply {
                  putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                } ?: Intent(Intent.ACTION_GET_CONTENT).apply {
                  addCategory(Intent.CATEGORY_OPENABLE)
                  type = "*/*"
                  putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/pdf", "*/*"))
                  putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                }
              } catch (e: Exception) {
                Intent(Intent.ACTION_GET_CONTENT).apply {
                  addCategory(Intent.CATEGORY_OPENABLE)
                  type = "*/*"
                  putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/pdf", "*/*"))
                  putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                }
              }

              try {
                fileChooserLauncher.launch(
                  Intent.createChooser(intent, "Select PDF Files")
                )
              } catch (e: Exception) {
                Log.e("MainActivity", "Error launching file chooser", e)
                fileUploadCallback?.onReceiveValue(null)
                fileUploadCallback = null
                Toast.makeText(this@MainActivity, "Could not open file picker", Toast.LENGTH_SHORT).show()
              }
            },
            onSaveBlob = { base64Data, fileName, mimeType ->
              saveFileToDownloads(base64Data, fileName, mimeType)
            }
          )
        }
      }
    }
  }

  private fun saveFileToDownloads(base64Data: String, fileName: String, mimeType: String) {
    try {
      val pureBase64 = if (base64Data.contains(",")) {
        base64Data.substringAfter(",")
      } else {
        base64Data
      }
      val decodedBytes = Base64.decode(pureBase64, Base64.DEFAULT)
      val actualName = if (fileName.isNotBlank()) fileName else "downloaded_file.pdf"
      val actualMime = if (mimeType.isNotBlank()) mimeType else "application/pdf"

      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val contentValues = ContentValues().apply {
          put(MediaStore.MediaColumns.DISPLAY_NAME, actualName)
          put(MediaStore.MediaColumns.MIME_TYPE, actualMime)
          put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        }
        val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
        if (uri != null) {
          contentResolver.openOutputStream(uri)?.use { os ->
            os.write(decodedBytes)
            os.flush()
          }
          runOnUiThread {
            Toast.makeText(this, "Saved to Downloads: $actualName", Toast.LENGTH_LONG).show()
          }
        } else {
          throw Exception("Unable to create MediaStore entry")
        }
      } else {
        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (!downloadsDir.exists()) downloadsDir.mkdirs()
        val targetFile = File(downloadsDir, actualName)
        FileOutputStream(targetFile).use { fos ->
          fos.write(decodedBytes)
          fos.flush()
        }
        runOnUiThread {
          Toast.makeText(this, "Saved to Downloads: ${targetFile.name}", Toast.LENGTH_LONG).show()
        }
      }
    } catch (e: Exception) {
      Log.e("MainActivity", "Failed to save file", e)
      runOnUiThread {
        Toast.makeText(this, "Failed to save file: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
      }
    }
  }
}

@Composable
fun PdfWebViewContainer(
  onWebViewCreated: (WebView) -> Unit,
  onOpenFileChooser: (ValueCallback<Array<Uri>>, WebChromeClient.FileChooserParams?) -> Unit,
  onSaveBlob: (String, String, String) -> Unit,
  modifier: Modifier = Modifier
) {
  AndroidView(
    modifier = modifier.fillMaxSize(),
    factory = { context ->
      WebView(context).apply {
        settings.apply {
          javaScriptEnabled = true
          domStorageEnabled = true
          databaseEnabled = true
          allowFileAccess = true
          allowContentAccess = true
          allowFileAccessFromFileURLs = true
          allowUniversalAccessFromFileURLs = true
          mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
          useWideViewPort = true
          loadWithOverviewMode = true
          builtInZoomControls = false
          displayZoomControls = false
          cacheMode = WebSettings.LOAD_DEFAULT
          userAgentString = settings.userAgentString + " WowPdfAndroid/1.0"
        }

        addJavascriptInterface(
          WebAppInterface(context, onSaveBlob),
          "AndroidNative"
        )

        webChromeClient = object : WebChromeClient() {
          override fun onShowFileChooser(
            webView: WebView?,
            filePathCallback: ValueCallback<Array<Uri>>?,
            fileChooserParams: FileChooserParams?
          ): Boolean {
            if (filePathCallback != null) {
              onOpenFileChooser(filePathCallback, fileChooserParams)
              return true
            }
            return false
          }

          override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
            Log.d("WebViewConsole", "${consoleMessage?.message()} -- From line ${consoleMessage?.lineNumber()} of ${consoleMessage?.sourceId()}")
            return true
          }
        }

        webViewClient = object : WebViewClient() {
          override fun shouldOverrideUrlLoading(
            view: WebView?,
            request: WebResourceRequest?
          ): Boolean {
            val url = request?.url?.toString() ?: return false
            if (url.startsWith("file:///android_asset/")) {
              return false
            }
            if (url.startsWith("http://") || url.startsWith("https://")) {
              // External navigation can stay inside or open browser as appropriate
              return false
            }
            return try {
              val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
              context.startActivity(intent)
              true
            } catch (e: Exception) {
              false
            }
          }

          override fun onPageFinished(view: WebView?, url: String?) {
            super.onPageFinished(view, url)
            // Hook download links (including blob: and data: URLs) so downloads route through AndroidNative interface
            val jsHookDownloads = """
              (function() {
                if (window.__androidDownloadHooked) return;
                window.__androidDownloadHooked = true;
                
                document.addEventListener('click', function(e) {
                  var target = e.target;
                  while (target && target.tagName !== 'A') {
                    target = target.parentElement;
                  }
                  if (!target) return;
                  var href = target.getAttribute('href') || '';
                  var downloadAttr = target.getAttribute('download');
                  
                  if (downloadAttr !== null || href.startsWith('blob:') || href.startsWith('data:')) {
                    if (href.startsWith('blob:')) {
                      e.preventDefault();
                      fetch(href)
                        .then(function(r) { return r.blob(); })
                        .then(function(blob) {
                          var reader = new FileReader();
                          reader.onloadend = function() {
                            if (window.AndroidNative && window.AndroidNative.downloadFile) {
                              window.AndroidNative.downloadFile(reader.result, downloadAttr || 'download.pdf', blob.type || 'application/pdf');
                            }
                          };
                          reader.readAsDataURL(blob);
                        })
                        .catch(function(err) {
                          console.error('Blob download conversion failed', err);
                        });
                    } else if (href.startsWith('data:')) {
                      e.preventDefault();
                      if (window.AndroidNative && window.AndroidNative.downloadFile) {
                        window.AndroidNative.downloadFile(href, downloadAttr || 'download.pdf', 'application/pdf');
                      }
                    }
                  }
                }, true);
              })();
            """.trimIndent()
            view?.evaluateJavascript(jsHookDownloads, null)
          }
        }

        setDownloadListener { url, userAgent, contentDisposition, mimetype, contentLength ->
          try {
            if (url.startsWith("blob:") || url.startsWith("data:")) {
              val script = """
                (function() {
                  fetch('$url')
                    .then(r => r.blob())
                    .then(b => {
                      var reader = new FileReader();
                      reader.onloadend = function() {
                        if (window.AndroidNative) {
                          window.AndroidNative.downloadFile(reader.result, 'document.pdf', b.type);
                        }
                      };
                      reader.readAsDataURL(b);
                    });
                })();
              """.trimIndent()
              evaluateJavascript(script, null)
              return@setDownloadListener
            }

            val request = DownloadManager.Request(Uri.parse(url)).apply {
              setMimeType(mimetype)
              addRequestHeader("User-Agent", userAgent)
              setDescription("Downloading file…")
              setTitle(URLUtil.guessFileName(url, contentDisposition, mimetype))
              setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
              setDestinationInExternalPublicDir(
                Environment.DIRECTORY_DOWNLOADS,
                URLUtil.guessFileName(url, contentDisposition, mimetype)
              )
            }
            val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager
            dm?.enqueue(request)
            Toast.makeText(context, "Downloading file…", Toast.LENGTH_SHORT).show()
          } catch (e: Exception) {
            Log.e("MainActivity", "DownloadListener failed", e)
            Toast.makeText(context, "Download error: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
          }
        }

        loadUrl("file:///android_asset/index.html")
        onWebViewCreated(this)
      }
    }
  )
}

class WebAppInterface(
  private val context: Context,
  private val onSaveBlob: (String, String, String) -> Unit
) {
  @JavascriptInterface
  fun downloadFile(base64Data: String, fileName: String, mimeType: String) {
    onSaveBlob(base64Data, fileName, mimeType)
  }

  @JavascriptInterface
  fun showToast(message: String) {
    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
  }
}

package com.example.screensafe

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class MainActivity : AppCompatActivity() {
    companion object {
        private const val TAG = "ScreenSafe"
        private const val BRIDGE_NAME = "ScreenSafeBridge"
        private const val MAX_LOG_VALUE = 500
    }

    private lateinit var urlInput: EditText
    private lateinit var statusText: TextView
    private lateinit var resultText: TextView
    private lateinit var webView: WebView

    @SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        urlInput = findViewById(R.id.urlInput)
        statusText = findViewById(R.id.statusText)
        resultText = findViewById(R.id.resultText)
        webView = findViewById(R.id.webView)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = true
            loadsImagesAutomatically = true
        }
        // Every loaded page can call this object, so expose reporting only.
        webView.addJavascriptInterface(DomBridge(), BRIDGE_NAME)
        webView.webChromeClient = WebChromeClient()
        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                statusText.text = getString(R.string.loading, url.orEmpty())
            }

            override fun onPageFinished(view: WebView, url: String) {
                statusText.text = getString(R.string.inspecting, url)
                inspectDom()
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) {
                    val message = "LOAD ERROR: ${error.errorCode} ${error.description}"
                    Log.e(TAG, message)
                    statusText.text = message
                }
            }
        }

        findViewById<Button>(R.id.openButton).setOnClickListener { openTypedUrl() }
        findViewById<Button>(R.id.inspectButton).setOnClickListener { inspectDom() }
        findViewById<Button>(R.id.audioProbeButton).setOnClickListener { probeAudio() }
        loadUrlFromIntent(intent)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) webView.goBack() else finish()
            }
        })
    }

    private fun openTypedUrl() {
        var url = urlInput.text.toString().trim()
        if (url.isBlank()) return
        if (!url.startsWith("http://", true) && !url.startsWith("https://", true)) {
            url = "https://$url"
            urlInput.setText(url)
        }
        webView.loadUrl(url)
    }

    private fun loadUrlFromIntent(sourceIntent: Intent?) {
        val url = sourceIntent?.getStringExtra("url")?.trim().orEmpty()
        if (url.startsWith("https://") || url.startsWith("http://")) {
            urlInput.setText(url)
            webView.loadUrl(url)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        loadUrlFromIntent(intent)
    }

    private fun inspectDom() {
        webView.evaluateJavascript(DOM_INSPECTOR_SCRIPT) { encodedResult ->
            val json = decodeJavascriptString(encodedResult)
            if (json == null) {
                Log.e(TAG, "INSPECT: JavaScript returned null or invalid data")
                statusText.text = getString(R.string.inspect_failed)
            } else {
                logReport(json, "INITIAL")
            }
        }
    }

    private fun probeAudio() {
        resultText.append("\n\nĐang đo audio trong 750 ms… Hãy phát video trước nếu video đang pause.")
        webView.evaluateJavascript(AUDIO_PROBE_SCRIPT, null)
    }

    // evaluateJavascript JSON-encodes a returned JavaScript string once.
    private fun decodeJavascriptString(value: String): String? = try {
        if (value == "null") null else JSONArray("[$value]").getString(0)
    } catch (error: Exception) {
        Log.e(TAG, "Cannot decode JavaScript result", error)
        null
    }

    private fun logReport(json: String, source: String) {
        try {
            val report = JSONObject(json)
            val text = report.getJSONObject("text")
            val images = report.getJSONArray("images")
            val videos = report.getJSONArray("videos")
            val audios = report.getJSONArray("audios")
            val limits = report.getJSONArray("limits")

            Log.i(TAG, "[$source][TEXT] chars=${text.getInt("length")}, sample=${text.optString("sample").take(MAX_LOG_VALUE)}")
            Log.i(TAG, "[$source][IMAGE] count=${images.length()}")
            logSamples("IMAGE", images)
            Log.i(TAG, "[$source][VIDEO] count=${videos.length()}")
            logSamples("VIDEO", videos)
            Log.i(TAG, "[$source][AUDIO] standalone=${audios.length()}")
            logSamples("AUDIO", audios)
            for (index in 0 until limits.length()) Log.w(TAG, "[$source][LIMIT] ${limits.getString(index)}")

            val reportFile = saveReport(report, source)
            val readableImages = countTrue(images, "pixelReadable")
            val readableFrames = countTrue(videos, "frameReadable")
            statusText.text = getString(
                R.string.inspect_result,
                text.getInt("length"), images.length(), videos.length(), audios.length(), limits.length()
            )
            resultText.text = buildString {
                appendLine("Nguồn: $source")
                appendLine("Trang: ${report.optString("pageUrl")}")
                appendLine("TEXT: ${text.getInt("length")} ký tự (đã trả về ${text.optString("content").length})")
                appendLine("IMAGE: ${images.length()} | đọc pixel: $readableImages/${images.length()}")
                appendLine("VIDEO: ${videos.length()} | đọc frame: $readableFrames/${videos.length()}")
                appendLine("AUDIO riêng: ${audios.length()} | audio trong video: xem report")
                appendLine("LIMIT: ${limits.length()}")
                appendLine("File JSON: ${reportFile.absolutePath}")
                appendLine()
                appendLine("Text mẫu:")
                append(text.optString("sample"))
            }
        } catch (error: Exception) {
            Log.e(TAG, "Invalid DOM report: ${json.take(MAX_LOG_VALUE)}", error)
            statusText.text = getString(R.string.inspect_failed)
        }
    }

    private fun countTrue(array: JSONArray, key: String): Int =
        (0 until array.length()).count { array.optJSONObject(it)?.optBoolean(key) == true }

    private fun saveReport(report: JSONObject, source: String): File {
        report.put("reportSource", source)
        report.put("receivedAtEpochMs", System.currentTimeMillis())
        val directory = File(getExternalFilesDir(null), "reports").apply { mkdirs() }
        val file = File(directory, "latest_report.json")
        file.writeText(report.toString(2), Charsets.UTF_8)
        Log.i(TAG, "[$source][REPORT] saved=${file.absolutePath}")
        return file
    }

    private fun logSamples(group: String, values: JSONArray) {
        for (index in 0 until minOf(values.length(), 3)) {
            Log.i(TAG, "[$group][$index] ${values.getJSONObject(index).toString().take(MAX_LOG_VALUE)}")
        }
    }

    private inner class DomBridge {
        @JavascriptInterface
        fun onMutation(reportJson: String) {
            runOnUiThread {
                Log.i(TAG, "MutationObserver detected new DOM content")
                logReport(reportJson, "MUTATION")
            }
        }

        @JavascriptInterface
        fun onAudioProbe(reportJson: String) {
            runOnUiThread {
                try {
                    val report = JSONObject(reportJson)
                    val directory = File(getExternalFilesDir(null), "reports").apply { mkdirs() }
                    val file = File(directory, "latest_audio_probe.json")
                    file.writeText(report.toString(2), Charsets.UTF_8)
                    Log.i(TAG, "[AUDIO_PROBE] ${report.toString().take(2000)}")
                    Log.i(TAG, "[AUDIO_PROBE][REPORT] saved=${file.absolutePath}")
                    resultText.append("\n\nAUDIO PROBE: ${report.optJSONArray("media")?.length() ?: 0} media\nFile: ${file.absolutePath}")
                } catch (error: Exception) {
                    Log.e(TAG, "Invalid audio probe report", error)
                }
            }
        }
    }

    override fun onDestroy() {
        webView.removeJavascriptInterface(BRIDGE_NAME)
        webView.stopLoading()
        webView.destroy()
        super.onDestroy()
    }
}

private const val AUDIO_PROBE_SCRIPT = """
(function () {
  var media = Array.from(document.querySelectorAll('video, audio'));
  var AudioContextClass = window.AudioContext || window.webkitAudioContext;
  if (!AudioContextClass) {
    window.ScreenSafeBridge.onAudioProbe(JSON.stringify({supported:false, reason:'Web Audio API is unavailable.', media:[]}));
    return;
  }
  window.__screenSafeAudioContext = window.__screenSafeAudioContext || new AudioContextClass();
  window.__screenSafeAudioNodes = window.__screenSafeAudioNodes || new WeakMap();
  var context = window.__screenSafeAudioContext;
  Promise.resolve(context.resume()).catch(function () {}).then(function () {
    var probes = media.map(function (element, index) {
      var probe = {index:index, tag:element.tagName, src:element.currentSrc || element.src || '',
        paused:!!element.paused, muted:!!element.muted, contextState:context.state,
        measured:false, rms:0, peak:0, nonSilent:false, reason:''};
      try {
        var nodes = window.__screenSafeAudioNodes.get(element);
        if (!nodes) {
          var source = context.createMediaElementSource(element);
          var analyser = context.createAnalyser();
          analyser.fftSize = 2048;
          source.connect(analyser);
          analyser.connect(context.destination);
          nodes = {source:source, analyser:analyser};
          window.__screenSafeAudioNodes.set(element, nodes);
        }
        probe._analyser = nodes.analyser;
      } catch (error) {
        probe.reason = 'Cannot attach Web Audio source: ' + error.name + ': ' + error.message;
      }
      return probe;
    });
    setTimeout(function () {
      probes.forEach(function (probe) {
        var analyser = probe._analyser;
        delete probe._analyser;
        if (!analyser) return;
        try {
          var samples = new Float32Array(analyser.fftSize);
          analyser.getFloatTimeDomainData(samples);
          var sum = 0, peak = 0;
          samples.forEach(function (value) { sum += value * value; peak = Math.max(peak, Math.abs(value)); });
          probe.rms = Math.sqrt(sum / samples.length);
          probe.peak = peak;
          probe.nonSilent = probe.rms > 0.0001 || probe.peak > 0.001;
          probe.measured = true;
          if (probe.paused) probe.reason = 'Media is paused; play it and probe again.';
          else if (!probe.nonSilent) probe.reason = 'No non-silent samples. Media may be silent, cross-origin blocked, DRM-protected, muted, or not decoded yet.';
          else probe.reason = 'Non-silent audio samples were measured successfully.';
        } catch (error) { probe.reason = 'Audio sampling failed: ' + error.name; }
      });
      window.ScreenSafeBridge.onAudioProbe(JSON.stringify({supported:true, collectedAt:new Date().toISOString(),
        contextState:context.state, media:probes,
        note:'RMS/peak prove sample access; this prototype does not persist raw PCM or bypass protected media.'}));
    }, 750);
  });
})();
"""

private const val DOM_INSPECTOR_SCRIPT = """
(function () {
  var MAX_TEXT_CHARS = 50000;
  function absoluteUrl(value) {
    if (!value) return '';
    try { return new URL(value, document.baseURI).href; } catch (_) { return value; }
  }
  function inspect(root) {
    var scope = root && root.querySelectorAll ? root : document;
    var textValue = root === document
      ? ((document.body && (document.body.innerText || document.body.textContent)) || '')
      : ((root.innerText || root.textContent) || '');
    function imageInfo(img) {
      var info = {src: absoluteUrl(img.currentSrc || img.src), alt: img.alt || '',
        width: img.naturalWidth || 0, height: img.naturalHeight || 0, complete: !!img.complete,
        pixelReadable: false, pixelSampleRgba: null, pixelReason: '',
        fallback: 'Download URL with required cookies/headers; if blocked, request user-approved Screen Capture (not implemented).'};
      if (!img.complete || !img.naturalWidth) {
        info.pixelReason = 'Image is not fully loaded.';
      } else {
        try {
          var imageCanvas = document.createElement('canvas');
          imageCanvas.width = 1; imageCanvas.height = 1;
          var imageContext = imageCanvas.getContext('2d');
          imageContext.drawImage(img, 0, 0, 1, 1);
          info.pixelSampleRgba = Array.from(imageContext.getImageData(0, 0, 1, 1).data);
          info.pixelReadable = true;
          info.pixelReason = 'Canvas pixel read succeeded; image pixels are available for analysis.';
        } catch (error) {
          info.pixelReason = 'Canvas pixel read blocked, commonly by cross-origin taint: ' + error.name;
        }
      }
      return info;
    }
    var images = Array.from(scope.querySelectorAll('img')).map(imageInfo);
    if (root && root.matches && root.matches('img')) images.unshift(imageInfo(root));

    var videoElements = Array.from(scope.querySelectorAll('video'));
    if (root && root.matches && root.matches('video')) videoElements.unshift(root);
    var videos = videoElements.map(function (video) {
      var src = video.currentSrc || video.src || '';
      var info = {
        currentSrc: absoluteUrl(src), currentTime: Number(video.currentTime) || 0,
        duration: Number.isFinite(video.duration) ? video.duration : null,
        videoWidth: video.videoWidth || 0, videoHeight: video.videoHeight || 0,
        paused: !!video.paused, readyState: video.readyState,
        sourceType: src.indexOf('blob:') === 0 ? 'blob' : (src ? 'url' : 'none'),
        drmMediaKeysPresent: !!video.mediaKeys, frameReadable: false, frameSampleRgba: null, frameReason: '',
        audio: {
          muted: !!video.muted, volume: Number(video.volume),
          audioTracks: video.audioTracks ? video.audioTracks.length : null,
          webkitAudioDecodedByteCount: typeof video.webkitAudioDecodedByteCount === 'number' ? video.webkitAudioDecodedByteCount : null,
          webAudioSupported: !!(window.AudioContext || window.webkitAudioContext),
          captureStreamSupported: !!(video.captureStream || video.mozCaptureStream),
          extractionImplemented: false,
          assessment: 'Metadata only. PCM extraction needs a user-gesture Web Audio pipeline; Playback Capture needs Android user consent.'
        },
        fallback: 'Use user-approved Screen Capture for frames or Playback Capture for audio when allowed (not implemented); DRM is never bypassed.'
      };
      if (!video.videoWidth || !video.videoHeight || video.readyState < 2) {
        info.frameReason = 'No decoded frame is currently available (metadata/data not ready).';
      } else {
        try {
          var canvas = document.createElement('canvas');
          canvas.width = Math.min(video.videoWidth, 2); canvas.height = Math.min(video.videoHeight, 2);
          var context = canvas.getContext('2d');
          context.drawImage(video, 0, 0, canvas.width, canvas.height);
          info.frameSampleRgba = Array.from(context.getImageData(0, 0, 1, 1).data);
          info.frameReadable = true;
          info.frameReason = 'Canvas drawImage/getImageData succeeded; a frame can be sampled later.';
        } catch (error) {
          info.frameReason = 'Frame blocked/tainted (commonly cross-origin, protected, or DRM media): ' + error.name;
        }
      }
      return info;
    });

    var audios = Array.from(scope.querySelectorAll('audio')).map(function (audio) {
      var audioSrc = audio.currentSrc || audio.src || '';
      return {
        currentSrc: absoluteUrl(audioSrc), currentTime: Number(audio.currentTime) || 0,
        duration: Number.isFinite(audio.duration) ? audio.duration : null,
        paused: !!audio.paused, muted: !!audio.muted, volume: Number(audio.volume),
        readyState: audio.readyState,
        sourceType: audioSrc.indexOf('blob:') === 0 ? 'blob' : (audioSrc ? 'url' : 'none'),
        webAudioSupported: !!(window.AudioContext || window.webkitAudioContext),
        extractionImplemented: false,
        reason: 'Audio metadata is readable; raw PCM extraction is not part of this DOM prototype.'
      };
    });

    var limits = [];
    if (scope.querySelectorAll('canvas').length) limits.push('Canvas pixels are not DOM content; reading may fail when tainted by cross-origin data.');
    if (images.some(function (x) { return x.src.indexOf('blob:') === 0; }) || videos.some(function (x) { return x.sourceType === 'blob'; })) limits.push('Blob URL found: it is temporary and is not a reusable network URL.');
    if (videos.some(function (x) { return x.drmMediaKeysPresent; })) limits.push('Encrypted Media/DRM MediaKeys detected; protected media is not extracted or bypassed.');
    if (scope.querySelectorAll('iframe').length) limits.push('Iframe found: cross-origin iframe DOM cannot be read due to the same-origin policy.');
    if (videos.some(function (x) { return !x.currentSrc; })) limits.push('A video has no visible currentSrc; it may use MediaSource/streaming or has not loaded yet.');
    if (audios.some(function (x) { return x.sourceType === 'blob'; })) limits.push('Blob audio found: raw audio URL is not reusable outside this page session.');
    return {
      pageUrl: location.href, pageTitle: document.title, collectedAt: new Date().toISOString(),
      text: {length: textValue.length, content: textValue.slice(0, MAX_TEXT_CHARS),
        sample: textValue.slice(0, 500), truncated: textValue.length > MAX_TEXT_CHARS},
      images: images, videos: videos, audios: audios, limits: limits,
      fallbacks: {
        dom: 'MutationObserver plus manual Re-scan button.',
        image: 'URL download first, canvas pixels when same-origin/CORS allows, then user-approved Screen Capture.',
        video: 'Canvas frame sampling first, then user-approved Screen Capture.',
        audio: 'Web Audio after user gesture or Android Playback Capture with user consent.',
        screenCaptureImplemented: false,
        audioExtractionImplemented: false,
        drmPolicy: 'Never bypass DRM or protected content.'
      }
    };
  }

  var initial = inspect(document);
  if (window.__screenSafeObserver) window.__screenSafeObserver.disconnect();
  var timer = 0;
  window.__screenSafeObserver = new MutationObserver(function (mutations) {
    var added = [];
    mutations.forEach(function (mutation) { Array.from(mutation.addedNodes).forEach(function (node) { if (node.nodeType === Node.ELEMENT_NODE) added.push(node); }); });
    if (!added.length) return;
    clearTimeout(timer);
    timer = setTimeout(function () {
      // Re-scan the full document so several sibling nodes added in one burst are not missed.
      try { window.ScreenSafeBridge.onMutation(JSON.stringify(inspect(document))); } catch (_) {}
    }, 400);
  });
  if (document.documentElement) window.__screenSafeObserver.observe(document.documentElement, {childList: true, subtree: true});
  return JSON.stringify(initial);
})();
"""

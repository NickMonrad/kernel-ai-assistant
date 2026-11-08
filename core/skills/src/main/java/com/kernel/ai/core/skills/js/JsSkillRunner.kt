package com.kernel.ai.core.skills.js

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "KernelAI"
private const val TIMEOUT_MS = 15_000L
private const val BRIDGE_NAME = "Android"

/**
 * Executes a JavaScript skill packaged in `assets/skills/<skillName>/index.html`.
 *
 * Mirrors Google AI Edge Gallery's JS skill execution model:
 *  1. The skill's HTML file is loaded into a hidden WebView.
 *  2. `ai_edge_gallery_get_result(args)` is called asynchronously via JS injection.
 *  3. The skill calls `Android.onResult(json)` back to the native bridge.
 *  4. The result is returned as a String to the calling coroutine.
 *
 * WebView must be created on the main thread; the coroutine suspends on the main
 * dispatcher so the message loop can process WebView callbacks without blocking.
 *
 * Security note: `allowUniversalAccessFromFileURLs` is enabled so that skills
 * loaded from `file://android_asset` can make HTTPS network calls (e.g., Wikipedia).
 * This is acceptable because the skill assets are bundled with the app and are trusted.
 */
@Singleton
class JsSkillRunner @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    @SuppressLint("SetJavaScriptEnabled")
    suspend fun execute(skillName: String, args: Map<String, String>): String =
        withContext(Dispatchers.Main) {
            val deferred = CompletableDeferred<String>()
            val webView = WebView(context)
            try {
                webView.settings.apply {
                    javaScriptEnabled = true
                    allowFileAccessFromFileURLs = true
                    @Suppress("DEPRECATION")
                    allowUniversalAccessFromFileURLs = true
                    mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    cacheMode = WebSettings.LOAD_NO_CACHE
                }
                webView.addJavascriptInterface(ResultBridge(deferred), BRIDGE_NAME)

                val argsJson = JSONObject(args as Map<*, *>).toString()

                webView.webViewClient = object : WebViewClient() {
                    private var callInjected = false

                    override fun onPageFinished(view: WebView, url: String) {
                        if (callInjected || deferred.isCompleted || url == "about:blank") return
                        callInjected = true
                        val js = """
                            (async () => {
                                try {
                                    const result = await ai_edge_gallery_get_result($argsJson);
                                    $BRIDGE_NAME.onResult(JSON.stringify({success:true,result:String(result)}));
                                } catch(e) {
                                    $BRIDGE_NAME.onResult(JSON.stringify({success:false,error:String(e)}));
                                }
                            })();
                        """.trimIndent()
                        view.evaluateJavascript(js, null)
                    }

                    override fun onReceivedError(
                        view: WebView,
                        request: WebResourceRequest?,
                        error: WebResourceError?,
                    ) {
                        if (!deferred.isCompleted) {
                            deferred.completeExceptionally(
                                Exception("WebView load error: ${error?.description}")
                            )
                        }
                    }
                }

                webView.loadUrl("file:///android_asset/skills/$skillName/index.html")
                Log.d(TAG, "JsSkillRunner: loading skill=$skillName args=$args")

                withTimeoutOrNull(TIMEOUT_MS) { deferred.await() }
                    ?: throw Exception("JS skill '$skillName' timed out after ${TIMEOUT_MS / 1000}s")
            } finally {
                webView.destroy()
            }
        }

    private class ResultBridge(private val deferred: CompletableDeferred<String>) {
        @JavascriptInterface
        fun onResult(jsonResult: String) {
            if (deferred.isCompleted) return
            try {
                val obj = JSONObject(jsonResult)
                if (obj.optBoolean("success", false)) {
                    deferred.complete(obj.optString("result", ""))
                } else {
                    deferred.completeExceptionally(Exception(obj.optString("error", "Unknown JS error")))
                }
            } catch (e: Exception) {
                deferred.completeExceptionally(e)
            }
        }
    }
}

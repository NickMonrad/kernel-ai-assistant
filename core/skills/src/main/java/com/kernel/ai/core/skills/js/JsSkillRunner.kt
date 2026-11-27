package com.kernel.ai.core.skills.js

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
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
            val call = JsSkillCall(skillName)
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
                webView.addJavascriptInterface(ResultBridge(call), BRIDGE_NAME)

                val argsJson = JSONObject(args as Map<*, *>).toString()

                webView.webViewClient = object : WebViewClient() {
                    private var callInjected = false

                    override fun onPageFinished(view: WebView, url: String) {
                        if (callInjected || call.isCompleted || url == "about:blank") return
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
                        call.fail(Exception("WebView load error: ${error?.description}"))
                    }

                    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean =
                        call.onRendererGone(
                            didCrash = detail.didCrash(),
                            rendererPriority = detail.rendererPriorityAtExit(),
                            destroy = { view.destroy() },
                        )
                }

                webView.loadUrl("file:///android_asset/skills/$skillName/index.html")
                Log.d(TAG, "JsSkillRunner: loading skill=$skillName args=$args")

                withTimeoutOrNull(TIMEOUT_MS) { call.await() }
                    ?: throw Exception("JS skill '$skillName' timed out after ${TIMEOUT_MS / 1000}s")
            } finally {
                call.destroyWebView { webView.destroy() }
            }
        }

    private class ResultBridge(private val call: JsSkillCall) {
        @JavascriptInterface
        fun onResult(jsonResult: String) {
            if (call.isCompleted) return
            try {
                val obj = JSONObject(jsonResult)
                if (obj.optBoolean("success", false)) {
                    call.succeed(obj.optString("result", ""))
                } else {
                    call.fail(Exception(obj.optString("error", "Unknown JS error")))
                }
            } catch (e: Exception) {
                call.fail(e)
            }
        }
    }
}

/**
 * One `JsSkillRunner.execute` call. Owns the pending result and guarantees it completes once:
 * later bridge results, load errors, or renderer-gone callbacks are ignored after the first.
 * Kept free of WebView types so the completion rules are unit-testable on the JVM.
 */
internal class JsSkillCall(private val skillName: String) {
    private val deferred = CompletableDeferred<String>()
    private var webViewDestroyed = false

    val isCompleted: Boolean get() = deferred.isCompleted

    suspend fun await(): String = deferred.await()

    /** Returns false if the call already completed. */
    fun succeed(result: String): Boolean = deferred.complete(result)

    /** Returns false if the call already completed. */
    fun fail(error: Throwable): Boolean = deferred.completeExceptionally(error)

    /**
     * The WebView renderer was killed (for example by lmkd). Fails the call once with
     * [JsSkillRendererGoneException], destroys the WebView, and returns true so the
     * default crash-the-app behaviour (returning false) does not apply.
     */
    fun onRendererGone(
        didCrash: Boolean,
        rendererPriority: Int,
        destroy: () -> Unit,
    ): Boolean {
        Log.w(
            TAG,
            "JsSkillRunner: renderer gone skill=$skillName didCrash=$didCrash " +
                "rendererPriorityAtExit=$rendererPriority alreadyCompleted=${deferred.isCompleted}",
        )
        fail(JsSkillRendererGoneException())
        destroyWebView(destroy)
        return true
    }

    /**
     * Destroys the WebView at most once; called from both the renderer-gone path and `finally`.
     * Must be called on the main thread: `WebView.destroy()` is main-thread only.
     */
    fun destroyWebView(destroy: () -> Unit) {
        if (webViewDestroyed) return
        webViewDestroyed = true
        destroy()
    }
}

/** Surfaced to the model/user as a failed skill result; the caller may retry. */
internal class JsSkillRendererGoneException :
    Exception("The JS renderer was interrupted; please try again.")

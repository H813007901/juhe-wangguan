package dev.aigw.app.marvis

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.ProgressBar
import androidx.appcompat.app.AppCompatActivity
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest

/**
 * Marvis QQ 网页登录：复刻 QQ OpenSDK AuthDialog 的 m_authorize H5 流
 * （URL 拼装与回跳拦截见 marvis_recon/recon-qqh5.md §1.2/§2）。
 * 登录成功回跳 auth://tauth.qq.com/ 时取 access_token/expires_in/openid 经 setResult 回传调用方。
 */
class MarvisQqLoginActivity : AppCompatActivity() {

    private lateinit var progressBar: ProgressBar
    private lateinit var webView: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
        webView = WebView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(progressBar, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            addView(webView, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        setContentView(root)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            cacheMode = WebSettings.LOAD_NO_CACHE
        }
        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                progressBar.progress = newProgress
                progressBar.visibility = if (newProgress >= 100) View.GONE else View.VISIBLE
            }
        }
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                handleRedirect(request.url.toString())

            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                // 对齐 AuthDialog.java:186-194：证书错误一律取消，不 proceed
                handler.cancel()
            }
        }
        webView.loadUrl(buildAuthorizeUrl())
    }

    private fun handleRedirect(url: String): Boolean {
        // 快捷登录会尝试 wtloginmqq://（把会话递给手机 QQ App）——无 QQ 环境时引导账密登录
        if (url.startsWith("wtloginmqq://") || url.startsWith("mqq") && url.contains("ptlogin")) {
            runOnUiThread {
                android.widget.Toast.makeText(
                    this,
                    "快捷登录需要手机 QQ：改用账密登录（已强制切换）",
                    android.widget.Toast.LENGTH_LONG,
                ).show()
            }
            return true
        }
        // ptlogin 快捷页注入实测可用的参数组（网页抓号实战样本）：pt_enable_pwd=1 出账密表单，
        // force_qr=1 出扫码，autorefresh/ucheck 保活；h5sig/loginty 等会话参数保留不动
        if (url.startsWith("https://xui.ptlogin2.qq.com/cgi-bin/xlogin") && !url.contains("pt_enable_pwd")) {
            webView.loadUrl("$url&pt_enable_pwd=1&force_qr=1&autorefresh=1&ucheck=1")
            return true
        }
        // graph 路线：登录成功后回跳 redirect_url，token 在 fragment/query 里——先于页面加载拦截
        if (url.contains("access_token=") && (url.contains("openid=") || url.contains("expires_in="))) {
            val params = parseAuthRedirect(url)
            setResult(
                RESULT_OK,
                Intent()
                    .putExtra(EXTRA_OPENID, params[PARAM_OPENID].orEmpty())
                    .putExtra(EXTRA_ACCESS_TOKEN, params[PARAM_ACCESS_TOKEN].orEmpty())
                    .putExtra(EXTRA_EXPIRES_IN, params[PARAM_EXPIRES_IN].orEmpty()),
            )
            finish()
            return true
        }
        if (url.startsWith(REDIRECT_PREFIX)) {
            val params = parseAuthRedirect(url)
            setResult(
                RESULT_OK,
                Intent()
                    .putExtra(EXTRA_OPENID, params[PARAM_OPENID].orEmpty())
                    .putExtra(EXTRA_ACCESS_TOKEN, params[PARAM_ACCESS_TOKEN].orEmpty())
                    .putExtra(EXTRA_EXPIRES_IN, params[PARAM_EXPIRES_IN].orEmpty()),
            )
            finish()
            return true
        }
        if (url.startsWith("auth://cancel") || url.startsWith("auth://close")) {
            setResult(RESULT_CANCELED)
            finish()
            return true
        }
        // 下载手 Q 客户端的外链，网关场景直接忽略
        if (url.startsWith("download://") || url.endsWith(".apk")) return true
        return false
    }

    /** auth:// 换 http:// 后 query 与 fragment 双路解析（对齐 Util.parseUrlToJson）；graph 路线的 https URL 也走这里。 */
    private fun parseAuthRedirect(url: String): Map<String, String> {
        val parsed = LinkedHashMap<String, String>()
        val normalized = if (url.startsWith("auth://")) url.replaceFirst("auth://", "http://") else url
        val uri = Uri.parse(normalized)
        for (part in listOf(uri.encodedQuery, uri.encodedFragment)) {
            if (part.isNullOrEmpty()) continue
            for (pair in part.split('&')) {
                val sep = pair.indexOf('=')
                if (sep <= 0) continue
                val key = URLDecoder.decode(pair.substring(0, sep), "UTF-8")
                val value = URLDecoder.decode(pair.substring(sep + 1), "UTF-8")
                if (value.isNotEmpty() && key !in parsed) parsed[key] = value
            }
        }
        return parsed
    }

    /** graph.qq.com 经典 OAuth（实测对 appid 1101070898 免 sign 直接下发 ptlogin 登录页），
     *  redirect_uri 用 https 域，登录后回跳 fragment 带 access_token/openid，WebView 拦截即可。 */
    private fun buildAuthorizeUrl(): String {
        val params = linkedMapOf(
            "client_id" to MARVIS_APP_ID,
            "redirect_uri" to REDIRECT_URL,
            "response_type" to "token",
            "display" to "mobile",
        )
        return AUTHORIZE_BASE + params.entries.joinToString("&") { (key, value) ->
            "$key=${URLEncoder.encode(value, "UTF-8")}"
        }
    }

    companion object {
        const val AUTHORIZE_BASE = "https://graph.qq.com/oauth2.0/authorize?"
        const val REDIRECT_URL = "https://marvis.qq.com/"
        const val REDIRECT_PREFIX = "auth://tauth.qq.com/"
        const val PARAM_OPENID = "openid"
        const val PARAM_ACCESS_TOKEN = "access_token"
        const val PARAM_EXPIRES_IN = "expires_in"
        const val EXTRA_OPENID = "openid"
        const val EXTRA_ACCESS_TOKEN = "access_token"
        const val EXTRA_EXPIRES_IN = "expires_in"
        const val MARVIS_APP_ID = "1101070898"

        fun intent(activity: Activity): Intent = Intent(activity, MarvisQqLoginActivity::class.java)
    }
}

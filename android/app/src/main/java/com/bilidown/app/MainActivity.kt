package com.bilidown.app

import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.KeyEvent
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {

    private lateinit var webView: WebView
    private lateinit var localServer: LocalServer

    /** 运行时申请 WRITE_EXTERNAL_STORAGE 权限（Android 6-9 需要，用于写入公共 Download 目录） */
    private val requestStoragePermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        // 无论是否授权都启动应用：getDownloadDir 内部会测试可写性，不可写时回退到私有目录
        startApp()
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Android 6-9 需要运行时申请 WRITE_EXTERNAL_STORAGE 才能写公共 Download 目录
        // Android 10+ 该权限已废弃/弱化，但仍需声明以兼容旧机型
        if (needsStoragePermission()) {
            requestStoragePermission.launch(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            startApp()
        }
    }

    /** Android 9 及以下需要运行时申请存储权限 */
    private fun needsStoragePermission(): Boolean {
        return android.os.Build.VERSION.SDK_INT <= android.os.Build.VERSION_CODES.P &&
            ContextCompat.checkSelfPermission(
                this, android.Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) != PackageManager.PERMISSION_GRANTED
    }

    /** 启动本地服务 + 加载 WebView */
    private fun startApp() {
        // 启动本地 HTTP 服务
        localServer = LocalServer(this)
        val port = localServer.start()

        // 配置 WebView
        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.cacheMode = WebSettings.LOAD_NO_CACHE
            settings.builtInZoomControls = false
            settings.displayZoomControls = false
            settings.setSupportZoom(false)
            settings.mediaPlaybackRequiresUserGesture = false
            settings.allowFileAccess = false
            settings.allowContentAccess = false

            webViewClient = WebViewClient()
            webChromeClient = WebChromeClient()

            loadUrl("http://127.0.0.1:$port/")
        }

        setContentView(webView)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        // 返回键：WebView 可后退则后退，否则退出 APP
        if (keyCode == KeyEvent.KEYCODE_BACK && this::webView.isInitialized && webView.canGoBack()) {
            webView.goBack()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() {
        if (this::webView.isInitialized) webView.destroy()
        if (this::localServer.isInitialized) localServer.stop()
        super.onDestroy()
    }
}

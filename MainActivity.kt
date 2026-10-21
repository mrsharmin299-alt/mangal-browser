package com.mangal.browser

import android.app.Activity
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.text.InputType
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import java.io.File

class MainActivity : Activity() {
    private lateinit var webView: WebView
    private lateinit var toolbar: LinearLayout
    private lateinit var urlField: EditText
    private val homeUrl = "file:///android_asset/web/index.html"

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    private fun toolButton(label: String): TextView {
        val t = TextView(this)
        t.text = label
        t.textSize = 20f
        t.setTextColor(Color.WHITE)
        t.gravity = Gravity.CENTER
        t.layoutParams = LinearLayout.LayoutParams(dp(42), dp(42))
        return t
    }

    private fun go(input: String) {
        val q = input.trim()
        if (q.isEmpty()) return
        val url = if (q.startsWith("http://") || q.startsWith("https://")) q
        else if (!q.contains(" ") && q.contains(".")) "https://$q"
        else "https://www.google.com/search?q=" + Uri.encode(q)
        webView.loadUrl(url)
        webView.requestFocus()
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(urlField.windowToken, 0)
    }

    private fun updateBar(url: String?) {
        val u = url ?: return
        val isHome = u.startsWith("file:///android_asset")
        toolbar.visibility = if (isHome) View.GONE else View.VISIBLE
        if (!isHome && !urlField.hasFocus()) urlField.setText(u)
    }

    private fun showMenu(anchor: View) {
        val menu = PopupMenu(this, anchor)
        menu.menu.add(0, 1, 0, "সামনে যান")
        menu.menu.add(0, 2, 1, "অন্য ব্রাউজারে খুলুন")
        menu.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> if (webView.canGoForward()) webView.goForward()
                2 -> {
                    val u = webView.url
                    if (u != null && u.startsWith("http")) {
                        try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u))) } catch (e: Exception) { }
                    }
                }
            }
            true
        }
        menu.show()
    }

    // ---------- Downloads ----------

    private fun enqueueDownload(url: String, name: String) {
        try {
            val req = DownloadManager.Request(Uri.parse(url))
            req.addRequestHeader("User-Agent", webView.settings.userAgentString)
            val cookie = CookieManager.getInstance().getCookie(url)
            if (cookie != null) req.addRequestHeader("Cookie", cookie)
            req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
            val dm = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
            dm.enqueue(req)
            toast("ডাউনলোড শুরু হয়েছে")
        } catch (e: Exception) {
            toast("ডাউনলোড করা যায়নি")
        }
    }

    private fun imageExt(s: String): String {
        val l = s.lowercase()
        return when {
            l.contains("png") -> "png"
            l.contains("webp") -> "webp"
            l.contains("gif") -> "gif"
            else -> "jpg"
        }
    }

    private fun saveDataUri(src: String) {
        try {
            val comma = src.indexOf(',')
            val meta = src.substring(5, comma)
            if (!meta.contains("base64")) { toast("ছবি সংরক্ষণ করা যায়নি"); return }
            val mime = meta.substringBefore(';')
            val bytes = Base64.decode(src.substring(comma + 1), Base64.DEFAULT)
            val ext = imageExt(mime)
            val name = "MangalBrowser_" + System.currentTimeMillis() + "." + ext
            if (Build.VERSION.SDK_INT >= 29) {
                val values = ContentValues()
                values.put(MediaStore.Images.Media.DISPLAY_NAME, name)
                values.put(MediaStore.Images.Media.MIME_TYPE, "image/" + (if (ext == "jpg") "jpeg" else ext))
                values.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/MangalBrowser")
                val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                if (uri == null) { toast("ছবি সংরক্ষণ করা যায়নি"); return }
                val out = contentResolver.openOutputStream(uri)
                if (out == null) { toast("ছবি সংরক্ষণ করা যায়নি"); return }
                out.use { it.write(bytes) }
            } else {
                val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
                dir.mkdirs()
                File(dir, name).writeBytes(bytes)
            }
            toast("ছবি সংরক্ষণ হয়েছে")
        } catch (e: Exception) {
            toast("ছবি সংরক্ষণ করা যায়নি")
        }
    }

    private fun saveImage(src: String) {
        if (src.startsWith("data:")) {
            saveDataUri(src)
        } else if (src.startsWith("http")) {
            val name = "MangalBrowser_" + System.currentTimeMillis() + "." + imageExt(src.substringBefore('?'))
            enqueueDownload(src, name)
        } else {
            toast("এই ছবি সংরক্ষণ করা যাবে না")
        }
    }

    private fun showImageDialog(src: String) {
        val items = arrayOf("ছবি সংরক্ষণ করুন", "ছবি নতুন পেজে খুলুন")
        AlertDialog.Builder(this)
            .setItems(items) { _, which ->
                if (which == 0) saveImage(src)
                else if (src.startsWith("http")) webView.loadUrl(src)
            }
            .show()
    }

    private fun handleLongPress(): Boolean {
        val r = webView.hitTestResult
        val type = r.type
        if (type != WebView.HitTestResult.IMAGE_TYPE &&
            type != WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE) return false
        val fallback = r.extra
        val handler = Handler(Looper.getMainLooper()) { m ->
            val src = m.data.getString("src") ?: fallback
            if (src != null) showImageDialog(src)
            true
        }
        webView.requestFocusNodeHref(handler.obtainMessage())
        return true
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val bg = Color.rgb(8, 5, 7)
        window.statusBarColor = bg
        window.navigationBarColor = bg

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(bg)
        root.setOnApplyWindowInsetsListener { v, insets ->
            v.setPadding(0, insets.systemWindowInsetTop, 0, insets.systemWindowInsetBottom)
            insets
        }

        webView = WebView(this)
        webView.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.loadsImagesAutomatically = true
        webView.webChromeClient = WebChromeClient()
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val uri = request?.url ?: return false
                val scheme = uri.scheme ?: return false
                if (scheme == "https" || scheme == "http" || scheme == "file" || scheme == "about") return false
                return try {
                    startActivity(Intent(Intent.ACTION_VIEW, uri))
                    true
                } catch (e: Exception) { true }
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                updateBar(url)
            }

            override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                updateBar(url)
            }
        }
        webView.setOnLongClickListener { handleLongPress() }
        webView.setDownloadListener { url, _, contentDisposition, mimeType, _ ->
            val name = URLUtil.guessFileName(url, contentDisposition, mimeType)
            enqueueDownload(url, name)
        }

        toolbar = LinearLayout(this)
        toolbar.orientation = LinearLayout.HORIZONTAL
        toolbar.gravity = Gravity.CENTER_VERTICAL
        toolbar.setBackgroundColor(Color.rgb(20, 9, 13))
        toolbar.setPadding(dp(4), dp(4), dp(4), dp(4))
        toolbar.visibility = View.GONE

        val homeBtn = toolButton("⌂")
        homeBtn.setOnClickListener { webView.loadUrl(homeUrl) }
        val backBtn = toolButton("←")
        backBtn.setOnClickListener {
            if (webView.canGoBack()) webView.goBack() else webView.loadUrl(homeUrl)
        }
        val reloadBtn = toolButton("⟳")
        reloadBtn.setOnClickListener { webView.reload() }
        val menuBtn = toolButton("⋮")
        menuBtn.setOnClickListener { showMenu(menuBtn) }

        urlField = EditText(this)
        val box = GradientDrawable()
        box.cornerRadius = dp(20).toFloat()
        box.setColor(Color.rgb(36, 16, 22))
        box.setStroke(dp(1), Color.rgb(255, 51, 72))
        urlField.background = box
        urlField.setTextColor(Color.WHITE)
        urlField.setHintTextColor(Color.rgb(160, 150, 160))
        urlField.hint = "সার্চ করুন বা ওয়েবসাইট লিখুন"
        urlField.textSize = 14f
        urlField.setSingleLine(true)
        urlField.setPadding(dp(14), 0, dp(14), 0)
        urlField.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        urlField.imeOptions = EditorInfo.IME_ACTION_GO
        val lp = LinearLayout.LayoutParams(0, dp(40), 1f)
        lp.setMargins(dp(4), 0, dp(4), 0)
        urlField.layoutParams = lp
        urlField.setOnEditorActionListener { v, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) {
                go(v.text.toString())
                true
            } else false
        }

        toolbar.addView(homeBtn)
        toolbar.addView(backBtn)
        toolbar.addView(urlField)
        toolbar.addView(reloadBtn)
        toolbar.addView(menuBtn)

        root.addView(toolbar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(webView)
        setContentView(root)
        webView.loadUrl(homeUrl)
    }

    override fun onBackPressed() {
        if (::webView.isInitialized && webView.canGoBack()) webView.goBack()
        else super.onBackPressed()
    }
}

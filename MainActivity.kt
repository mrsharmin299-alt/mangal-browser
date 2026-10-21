package com.mangal.browser

import android.app.Activity
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.text.InputType
import android.text.TextUtils
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File

class Tab(val webView: WebView) {
    var title: String = "নতুন ট্যাব"
    var group: String? = null
}

class MainActivity : Activity() {
    private val tabs = ArrayList<Tab>()
    private val groups = ArrayList<String>()
    private lateinit var current: Tab
    private val webView: WebView get() = current.webView

    private lateinit var contentFrame: FrameLayout
    private lateinit var toolbar: LinearLayout
    private lateinit var urlField: EditText
    private lateinit var tabsBtn: TextView
    private lateinit var switcher: LinearLayout
    private lateinit var tabsTitle: TextView
    private lateinit var tabList: LinearLayout

    private val homeUrl = "file:///android_asset/web/index.html"
    private val palette = intArrayOf(
        Color.rgb(255, 90, 90), Color.rgb(255, 170, 60), Color.rgb(90, 200, 120),
        Color.rgb(80, 160, 255), Color.rgb(190, 110, 255), Color.rgb(255, 110, 190)
    )

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    private fun groupColor(g: String?): Int =
        if (g == null) Color.rgb(110, 100, 108) else palette[Math.abs(g.hashCode() % palette.size)]

    private fun toolButton(label: String): TextView {
        val t = TextView(this)
        t.text = label
        t.textSize = 20f
        t.setTextColor(Color.WHITE)
        t.gravity = Gravity.CENTER
        t.layoutParams = LinearLayout.LayoutParams(dp(42), dp(42))
        return t
    }

    inner class Bridge {
        @JavascriptInterface
        fun openTabs() {
            runOnUiThread { showSwitcher() }
        }
    }

    // ---------- Navigation bar ----------

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
        menu.menu.add(0, 1, 0, "নতুন ট্যাব")
        menu.menu.add(0, 2, 1, "সব ট্যাব দেখুন")
        menu.menu.add(0, 3, 2, "রিফ্রেশ")
        menu.menu.add(0, 4, 3, "সামনে যান")
        menu.menu.add(0, 5, 4, "অন্য ব্রাউজারে খুলুন")
        menu.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> switchTo(newTab(homeUrl))
                2 -> showSwitcher()
                3 -> webView.reload()
                4 -> if (webView.canGoForward()) webView.goForward()
                5 -> {
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

    // ---------- Tabs ----------

    private fun newTab(url: String): Tab {
        val wv = WebView(this)
        wv.layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        wv.settings.javaScriptEnabled = true
        wv.settings.domStorageEnabled = true
        wv.settings.loadsImagesAutomatically = true
        val tab = Tab(wv)
        wv.webChromeClient = object : WebChromeClient() {
            override fun onReceivedTitle(view: WebView?, title: String?) {
                if (title != null) tab.title = title
            }
        }
        wv.webViewClient = object : WebViewClient() {
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
                if (::current.isInitialized && tab === current) updateBar(url)
            }

            override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                if (::current.isInitialized && tab === current) updateBar(url)
            }
        }
        wv.setOnLongClickListener { handleLongPress(wv) }
        wv.setDownloadListener { dlUrl, _, contentDisposition, mimeType, _ ->
            val name = URLUtil.guessFileName(dlUrl, contentDisposition, mimeType)
            enqueueDownload(dlUrl, name)
        }
        wv.addJavascriptInterface(Bridge(), "MangalApp")
        tabs.add(tab)
        wv.loadUrl(url)
        return tab
    }

    private fun switchTo(t: Tab) {
        if (::current.isInitialized) contentFrame.removeView(current.webView)
        current = t
        urlField.clearFocus()
        contentFrame.addView(t.webView, 0)
        hideSwitcher()
        updateBar(t.webView.url ?: homeUrl)
        tabsBtn.text = tabs.size.toString()
        t.webView.requestFocus()
    }

    private fun closeTab(t: Tab) {
        val idx = tabs.indexOf(t)
        if (idx < 0) return
        val wasOpen = switcher.visibility == View.VISIBLE
        val wasCurrent = ::current.isInitialized && t === current
        tabs.removeAt(idx)
        contentFrame.removeView(t.webView)
        t.webView.stopLoading()
        t.webView.destroy()
        if (tabs.isEmpty()) {
            switchTo(newTab(homeUrl))
        } else if (wasCurrent) {
            switchTo(tabs[minOf(idx, tabs.size - 1)])
        }
        tabsBtn.text = tabs.size.toString()
        if (wasOpen) showSwitcher()
    }

    private fun showSwitcher() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(urlField.windowToken, 0)
        refreshSwitcher()
        switcher.visibility = View.VISIBLE
        switcher.bringToFront()
    }

    private fun hideSwitcher() {
        if (::switcher.isInitialized) switcher.visibility = View.GONE
    }

    private fun refreshSwitcher() {
        groups.removeAll { g -> tabs.none { it.group == g } }
        tabsBtn.text = tabs.size.toString()
        tabsTitle.text = "ট্যাব (" + tabs.size + ")"
        tabList.removeAllViews()
        val ungrouped = tabs.filter { it.group == null }
        if (groups.isNotEmpty() && ungrouped.isNotEmpty()) addHeader(null, "সাধারণ")
        for (t in ungrouped) addTabRow(t)
        for (g in groups) {
            val ts = tabs.filter { it.group == g }
            addHeader(g, g + " (" + ts.size + ")")
            for (t in ts) addTabRow(t)
        }
    }

    private fun addHeader(g: String?, label: String) {
        val h = TextView(this)
        h.text = (if (g != null) "● " else "") + label
        h.textSize = 14f
        h.setTypeface(null, Typeface.BOLD)
        h.setTextColor(groupColor(g))
        h.setPadding(dp(18), dp(14), dp(18), dp(4))
        if (g != null) h.setOnClickListener { showGroupOptions(g) }
        tabList.addView(h)
    }

    private fun addTabRow(t: Tab) {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        val bgd = GradientDrawable()
        bgd.cornerRadius = dp(14).toFloat()
        bgd.setColor(Color.rgb(26, 13, 18))
        val isCurrent = t === current
        bgd.setStroke(dp(if (isCurrent) 2 else 1), if (isCurrent) Color.rgb(255, 51, 72) else Color.rgb(70, 34, 40))
        row.background = bgd
        row.setPadding(dp(12), dp(10), dp(4), dp(10))
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.setMargins(dp(12), dp(4), dp(12), dp(4))
        row.layoutParams = lp

        val dot = View(this)
        val dg = GradientDrawable()
        dg.shape = GradientDrawable.OVAL
        dg.setColor(groupColor(t.group))
        dot.background = dg
        val dlp = LinearLayout.LayoutParams(dp(10), dp(10))
        dlp.setMargins(0, 0, dp(10), 0)
        dot.layoutParams = dlp

        val title = TextView(this)
        title.text = if (t.title.isBlank()) "নতুন ট্যাব" else t.title
        title.setTextColor(Color.WHITE)
        title.textSize = 15f
        title.maxLines = 1
        title.ellipsize = TextUtils.TruncateAt.END
        title.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)

        val close = TextView(this)
        close.text = "✕"
        close.textSize = 18f
        close.setTextColor(Color.rgb(200, 190, 196))
        close.gravity = Gravity.CENTER
        close.layoutParams = LinearLayout.LayoutParams(dp(44), dp(36))
        close.setOnClickListener { closeTab(t) }

        row.addView(dot)
        row.addView(title)
        row.addView(close)
        row.setOnClickListener { switchTo(t) }
        row.setOnLongClickListener { showTabOptions(t); true }
        tabList.addView(row)
    }

    private fun showTabOptions(t: Tab) {
        val opts = ArrayList<String>()
        val acts = ArrayList<() -> Unit>()
        opts.add("গ্রুপে যোগ করুন")
        acts.add { chooseGroup(t) }
        if (t.group != null) {
            opts.add("গ্রুপ থেকে সরান")
            acts.add { t.group = null; refreshSwitcher() }
        }
        opts.add("ট্যাব বন্ধ করুন")
        acts.add { closeTab(t) }
        AlertDialog.Builder(this)
            .setItems(opts.toTypedArray()) { _, i -> acts[i]() }
            .show()
    }

    private fun chooseGroup(t: Tab) {
        val names = ArrayList<String>()
        for (g in groups) if (g != t.group) names.add(g)
        names.add("＋ নতুন গ্রুপ")
        AlertDialog.Builder(this)
            .setItems(names.toTypedArray()) { _, i ->
                if (i == names.size - 1) askGroupName(t)
                else { t.group = names[i]; refreshSwitcher() }
            }
            .show()
    }

    private fun askGroupName(t: Tab) {
        val input = EditText(this)
        input.hint = "গ্রুপের নাম"
        input.setSingleLine(true)
        AlertDialog.Builder(this)
            .setTitle("নতুন গ্রুপ")
            .setView(input)
            .setPositiveButton("ঠিক আছে") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    if (!groups.contains(name)) groups.add(name)
                    t.group = name
                    refreshSwitcher()
                }
            }
            .setNegativeButton("বাতিল", null)
            .show()
    }

    private fun showGroupOptions(g: String) {
        val opts = arrayOf("গ্রুপের সব ট্যাব বন্ধ করুন", "গ্রুপ ভেঙে দিন")
        AlertDialog.Builder(this)
            .setTitle(g)
            .setItems(opts) { _, i ->
                val members = tabs.filter { it.group == g }
                if (i == 0) {
                    for (m in members) closeTab(m)
                } else {
                    for (m in members) m.group = null
                    refreshSwitcher()
                }
            }
            .show()
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
        val items = arrayOf("ছবি সংরক্ষণ করুন", "ছবি নতুন ট্যাবে খুলুন")
        AlertDialog.Builder(this)
            .setItems(items) { _, which ->
                if (which == 0) saveImage(src)
                else if (src.startsWith("http")) switchTo(newTab(src))
            }
            .show()
    }

    private fun handleLongPress(wv: WebView): Boolean {
        val r = wv.hitTestResult
        val type = r.type
        if (type != WebView.HitTestResult.IMAGE_TYPE &&
            type != WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE) return false
        val fallback = r.extra
        val handler = Handler(Looper.getMainLooper()) { m ->
            val src = m.data.getString("src") ?: fallback
            if (src != null) showImageDialog(src)
            true
        }
        wv.requestFocusNodeHref(handler.obtainMessage())
        return true
    }

    // ---------- Activity ----------

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

        contentFrame = FrameLayout(this)
        contentFrame.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)

        // Toolbar
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

        tabsBtn = TextView(this)
        tabsBtn.text = "1"
        tabsBtn.textSize = 13f
        tabsBtn.setTypeface(null, Typeface.BOLD)
        tabsBtn.setTextColor(Color.WHITE)
        tabsBtn.gravity = Gravity.CENTER
        val tbg = GradientDrawable()
        tbg.cornerRadius = dp(7).toFloat()
        tbg.setStroke(dp(2), Color.WHITE)
        tabsBtn.background = tbg
        val tlp = LinearLayout.LayoutParams(dp(28), dp(26))
        tlp.setMargins(dp(8), 0, dp(8), 0)
        tabsBtn.layoutParams = tlp
        tabsBtn.setOnClickListener { showSwitcher() }

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
        toolbar.addView(tabsBtn)
        toolbar.addView(menuBtn)

        // Tab switcher overlay
        switcher = LinearLayout(this)
        switcher.orientation = LinearLayout.VERTICAL
        switcher.setBackgroundColor(Color.rgb(12, 6, 9))
        switcher.layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        switcher.isClickable = true
        switcher.visibility = View.GONE

        val header = LinearLayout(this)
        header.orientation = LinearLayout.HORIZONTAL
        header.gravity = Gravity.CENTER_VERTICAL
        header.setPadding(dp(16), dp(10), dp(8), dp(10))

        tabsTitle = TextView(this)
        tabsTitle.textSize = 18f
        tabsTitle.setTypeface(null, Typeface.BOLD)
        tabsTitle.setTextColor(Color.WHITE)
        tabsTitle.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)

        val newBtn = TextView(this)
        newBtn.text = "＋ নতুন ট্যাব"
        newBtn.textSize = 13f
        newBtn.setTextColor(Color.WHITE)
        newBtn.gravity = Gravity.CENTER
        val nbg = GradientDrawable()
        nbg.cornerRadius = dp(18).toFloat()
        nbg.setColor(Color.rgb(200, 24, 44))
        newBtn.background = nbg
        newBtn.setPadding(dp(14), dp(8), dp(14), dp(8))
        newBtn.setOnClickListener { switchTo(newTab(homeUrl)) }

        val closeSwitcher = toolButton("✕")
        closeSwitcher.setOnClickListener { hideSwitcher() }

        header.addView(tabsTitle)
        header.addView(newBtn)
        header.addView(closeSwitcher)

        val scroll = ScrollView(this)
        scroll.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        tabList = LinearLayout(this)
        tabList.orientation = LinearLayout.VERTICAL
        scroll.addView(tabList)

        switcher.addView(header)
        switcher.addView(scroll)
        contentFrame.addView(switcher)

        root.addView(toolbar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(contentFrame)
        setContentView(root)

        switchTo(newTab(homeUrl))
    }

    override fun onBackPressed() {
        if (::switcher.isInitialized && switcher.visibility == View.VISIBLE) {
            hideSwitcher()
        } else if (::current.isInitialized && webView.canGoBack()) {
            webView.goBack()
        } else if (::current.isInitialized && tabs.size > 1) {
            closeTab(current)
        } else {
            super.onBackPressed()
        }
    }
}

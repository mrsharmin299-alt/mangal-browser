package com.mangal.browser

import android.app.Activity
import android.app.PictureInPictureParams
import android.speech.RecognizerIntent
import org.mozilla.geckoview.StorageController
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.PorterDuff
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.animation.OvershootInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.app.DownloadManager
import android.content.pm.PackageManager
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.WindowInsetsController
import android.webkit.URLUtil
import org.json.JSONArray
import org.json.JSONObject
import org.mozilla.geckoview.WebExtension
import org.mozilla.geckoview.WebExtensionController
import org.mozilla.geckoview.WebResponse
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.ContentBlocking
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.GeckoView
import java.net.URLEncoder

/**
 * Mangal Browser — Chrome-এর মতো লেআউট, গ্লাস + 3D স্টাইলে।
 * উপরে: হোম, অ্যাড্রেস বার, ＋, ট্যাব সংখ্যা, ⋮
 * ট্যাব সুইচার (গ্রিড + সার্চ + ইনকগনিটো), অ্যাড্রেস-বার স্ক্রিন, ⋮ মেনু,
 * লিংক লং-প্রেস মেনু, নিচে ট্যাব গ্রুপ স্ট্রিপ।
 */
class MainActivity : Activity() {

    companion object {
        private var runtime: GeckoRuntime? = null
        private const val HOME = "resource://android/assets/web/index.html"
        private val BG = Color.rgb(5, 3, 4)
        private val RED = Color.rgb(255, 41, 69)
        private val VIOLET = Color.rgb(150, 90, 255)
    }

    private class Tab(val session: GeckoSession, val priv: Boolean) {
        var title = ""
        var url = HOME
        var canBack = false
        var canFwd = false
        var thumb: Bitmap? = null
        var group = 0
        var desktop = false
        var blocked = 0
    }

    private class MenuItem(val label: String, val enabled: Boolean = true, val action: () -> Unit)

    private val tabs = mutableListOf<Tab>()
    private var current = 0
    private var nextGroup = 1
    private var overlay: View? = null
    private var renderSwitcher: (() -> Unit)? = null
    private var showPrivateInSwitcher = false
    private var switcherQuery = ""

    private lateinit var root: FrameLayout
    private lateinit var geckoView: GeckoView
    private lateinit var addressPill: LinearLayout
    private lateinit var addressIcon: TextView
    private lateinit var addressText: TextView
    private lateinit var tabCount: TextView
    private lateinit var progress: View
    private lateinit var groupStrip: LinearLayout
    private lateinit var topBarView: View
    private lateinit var shieldBadge: TextView
    private lateinit var bottomNav: LinearLayout
    private lateinit var navHomeIcon: TextView
    private lateinit var navHomeLabel: TextView
    private var adblock = true
    private var blockedToday = 0
    private var blockedTotal = 0
    private var blockedDay = 0L
    private var blockedDirty = 0
    private lateinit var findBar: LinearLayout
    private lateinit var findField: EditText
    private lateinit var findCount: TextView
    private var fullscreen = false
    private var overlayCancel: (() -> Unit)? = null
    private var permCallback: GeckoSession.PermissionDelegate.Callback? = null
    private var filePrompt: GeckoSession.PromptDelegate.FilePrompt? = null
    private var fileResult: GeckoResult<GeckoSession.PromptDelegate.PromptResponse>? = null
    private val prefs by lazy { getSharedPreferences("mangal", MODE_PRIVATE) }

    private val cur: Tab get() = tabs[current]

    // ================= সাহায্যকারী: মাপ, গ্লাস, 3D =================

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun dpf(v: Int) = v * resources.displayMetrics.density

    /** গ্লাস: স্বচ্ছ গ্রেডিয়েন্ট + আলোকিত বর্ডার + উপরে শাইন */
    private fun glass(
        radius: Int,
        tint: Int = Color.WHITE,
        alpha: Int = 0x30,
        strokeAlpha: Int = 0x66
    ): LayerDrawable {
        val r = dpf(radius)
        val base = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(
                Color.argb(alpha, Color.red(tint), Color.green(tint), Color.blue(tint)),
                Color.argb(alpha / 3, Color.red(tint), Color.green(tint), Color.blue(tint))
            )
        ).apply {
            cornerRadius = r
            setStroke(dp(1), Color.argb(strokeAlpha, 255, 255, 255))
        }
        val shine = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(Color.argb(0x45, 255, 255, 255), Color.TRANSPARENT)
        ).apply { cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f) }
        return LayerDrawable(arrayOf(base, shine)).apply {
            setLayerInset(1, dp(1), dp(1), dp(1), 0)
            setLayerGravity(1, Gravity.TOP)
            setLayerHeight(1, maxOf(dp(radius), dp(10)))
        }
    }

    /** 3D গভীরতা: এলিভেশন + রঙিন ছায়া */
    private fun lift(v: View, elev: Int, glow: Int = RED) {
        v.elevation = dpf(elev)
        if (Build.VERSION.SDK_INT >= 28) {
            v.outlineSpotShadowColor = glow
            v.outlineAmbientShadowColor = glow
        }
    }

    /** চাপলে 3D টিল্ট + স্কেল */
    private fun pressable(v: View) {
        v.cameraDistance = resources.displayMetrics.density * 8000f
        v.setOnTouchListener { view, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN ->
                    view.animate().scaleX(.92f).scaleY(.92f).rotationX(10f).setDuration(90).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    view.animate().scaleX(1f).scaleY(1f).rotationX(0f).setDuration(150).start()
            }
            false
        }
    }

    private fun glassButton(label: String, onClick: () -> Unit): TextView = TextView(this).apply {
        text = label
        textSize = 20f
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        background = glass(21)
        lift(this, 6)
        setOnClickListener { onClick() }
        pressable(this)
    }

    private fun letterDot(tab: Tab, size: Int): TextView = TextView(this).apply {
        val host = if (tab.url.startsWith("resource://")) "M" else (Uri.parse(tab.url).host ?: "?")
        text = host.removePrefix("www.").take(1).uppercase()
        textSize = size * 0.42f
        gravity = Gravity.CENTER
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(Color.WHITE)
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(if (tab.priv) Color.rgb(90, 50, 170) else Color.rgb(150, 30, 50))
        }
        layoutParams = LinearLayout.LayoutParams(dp(size), dp(size))
    }

    private fun EditText.onTextChanged(cb: (String) -> Unit) {
        addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { cb(s?.toString() ?: "") }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
    }

    // ================= সার্চ ইঞ্জিন, সেটিংস, সেশন, ইমপোর্ট, ভয়েস =================

    private class Engine(val name: String, val url: String)

    private val engines = listOf(
        Engine("Google", "https://www.google.com/search?q="),
        Engine("Bing", "https://www.bing.com/search?q="),
        Engine("DuckDuckGo", "https://duckduckgo.com/?q="),
        Engine("Brave", "https://search.brave.com/search?q="),
        Engine("Ecosia", "https://www.ecosia.org/search?q=")
    )
    private val fontSteps = listOf(1.0f, 1.15f, 1.3f, 1.5f)

    private fun engIdx() = prefs.getInt("engine", 0).coerceIn(0, engines.size - 1)
    private fun fontIdx() = prefs.getInt("font", 0).coerceIn(0, fontSteps.size - 1)

    private fun applyRuntimePrefs() {
        val r = runtime ?: return
        try {
            r.settings.automaticFontSizeAdjustment = false
            r.settings.fontSizeFactor = fontSteps[fontIdx()]
            r.settings.preferredColorScheme =
                if (prefs.getBoolean("night", false)) GeckoRuntimeSettings.COLOR_SCHEME_DARK
                else GeckoRuntimeSettings.COLOR_SCHEME_SYSTEM
        } catch (e: Exception) { }
    }

    private fun intentUrl(i: Intent?): String? =
        if (i != null && i.action == Intent.ACTION_VIEW) i.dataString else null

    private fun isDefaultBrowser(): Boolean {
        if (Build.VERSION.SDK_INT < 29) return false
        return try {
            val rm = getSystemService(Context.ROLE_SERVICE) as android.app.role.RoleManager
            rm.isRoleHeld(android.app.role.RoleManager.ROLE_BROWSER)
        } catch (e: Exception) { false }
    }

    private fun requestDefaultBrowser() {
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                val rm = getSystemService(Context.ROLE_SERVICE) as android.app.role.RoleManager
                if (rm.isRoleAvailable(android.app.role.RoleManager.ROLE_BROWSER)) {
                    if (rm.isRoleHeld(android.app.role.RoleManager.ROLE_BROWSER)) {
                        toast("মঙ্গল আগে থেকেই ডিফল্ট ব্রাউজার"); return
                    }
                    startActivityForResult(rm.createRequestRoleIntent(android.app.role.RoleManager.ROLE_BROWSER), 3001)
                    return
                }
            }
            startActivity(Intent(android.provider.Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
        } catch (e: Exception) { toast("সেটিংস খোলা যায়নি") }
    }

    private fun saveSession() {
        try {
            val a = JSONArray()
            var idx = 0
            tabs.forEachIndexed { i, t ->
                if (!t.priv && !isHome(t.url) && !t.url.startsWith("about:") && a.length() < 15) {
                    if (i == current) idx = a.length()
                    a.put(t.url)
                }
            }
            prefs.edit().putString("sess", a.toString()).putInt("sessIdx", idx).apply()
        } catch (e: Exception) { }
    }

    private fun loadSession(): List<String> {
        val out = mutableListOf<String>()
        try {
            val a = JSONArray(prefs.getString("sess", "[]"))
            for (i in 0 until a.length()) out.add(a.getString(i))
        } catch (e: Exception) { }
        return out
    }

    private fun unescapeHtml(x: String) = x.replace("&amp;", "&").replace("&quot;", "\"")
        .replace("&#39;", "'").replace("&lt;", "<").replace("&gt;", ">")

    private fun pickBookmarkFile() {
        try {
            val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
            }
            startActivityForResult(i, 3002)
        } catch (e: Exception) { toast("ফাইল বাছাই খোলা যায়নি") }
    }

    private fun importBookmarks(uri: Uri) {
        try {
            val html = contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            val re = Regex("<a\\s[^>]*?href=\"([^\"]+)\"[^>]*>(.*?)</a>",
                setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            val list = loadList("bm")
            val have = list.map { it.url }.toHashSet()
            var added = 0
            re.findAll(html).forEach { m ->
                val u = unescapeHtml(m.groupValues[1].trim())
                if (!(u.startsWith("http://") || u.startsWith("https://")) || u in have) return@forEach
                val t = unescapeHtml(m.groupValues[2].replace(Regex("<[^>]*>"), "").trim()).ifBlank { display(u) }
                list.add(Entry(t, u)); have.add(u); added++
            }
            saveList("bm", list)
            toast(if (added > 0) "${bn(added)}টি বুকমার্ক ইমপোর্ট হয়েছে" else "নতুন কোনো বুকমার্ক পাওয়া যায়নি")
        } catch (e: Exception) { toast("ইমপোর্ট করা যায়নি") }
    }

    private fun startVoice() {
        try {
            val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PROMPT, "বলুন…")
            }
            startActivityForResult(i, 3003)
        } catch (e: Exception) { toast("ভয়েস সার্চ এই ফোনে পাওয়া যায়নি") }
    }

    private fun translatePage(t: Tab) {
        t.session.loadUri("https://translate.google.com/translate?sl=auto&tl=bn&u=" + URLEncoder.encode(t.url, "UTF-8"))
    }

    private fun clearBrowsingData() {
        try {
            runtime!!.storageController.clearData(
                StorageController.ClearFlags.COOKIES or StorageController.ClearFlags.ALL_CACHES
            ).then<Void>({ _ -> toast("কুকি ও ক্যাশ মুছে গেছে"); null }, { _ -> toast("মোছা যায়নি"); null })
        } catch (e: Throwable) { toast("মোছা যায়নি") }
    }

    // ================= URL সাহায্যকারী =================

    private fun isHome(url: String) = url.startsWith("resource://")

    private fun display(url: String) =
        url.removePrefix("https://").removePrefix("http://").removePrefix("www.").trimEnd('/')

    private fun looksLikeUrl(s: String): Boolean {
        val t = s.trim()
        return !t.contains(' ') && (t.contains('.') || t.startsWith("http") || t.startsWith("localhost"))
    }

    private fun toUrl(input: String): String {
        val s = input.trim()
        if (s.isEmpty()) return HOME
        val schemes = listOf("http://", "https://", "file://", "resource://", "about:")
        if (schemes.any { s.startsWith(it) }) return s
        if (looksLikeUrl(s)) return "https://$s"
        return engines[engIdx()].url + URLEncoder.encode(s, "UTF-8")
    }

    private fun tabTitle(t: Tab) =
        if (isHome(t.url)) "নতুন ট্যাব" else t.title.ifBlank { display(t.url) }

    private fun shareText(s: String) {
        val i = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, s)
        }
        startActivity(Intent.createChooser(i, null))
    }

    private fun copyText(s: String) {
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText("url", s))
        Toast.makeText(this, "কপি হয়েছে", Toast.LENGTH_SHORT).show()
    }

    private fun hideKeyboard() {
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(root.windowToken, 0)
    }

    // ================= লাইফসাইকেল =================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 30) window.setDecorFitsSystemWindows(false)
        window.statusBarColor = BG
        window.navigationBarColor = BG
        if (runtime == null) {
            val cb = ContentBlocking.Settings.Builder()
                .antiTracking(
                    ContentBlocking.AntiTracking.AD or ContentBlocking.AntiTracking.ANALYTIC or
                        ContentBlocking.AntiTracking.SOCIAL or ContentBlocking.AntiTracking.CRYPTOMINING or
                        ContentBlocking.AntiTracking.FINGERPRINTING
                )
                .build()
            runtime = GeckoRuntime.create(
                applicationContext,
                GeckoRuntimeSettings.Builder().contentBlocking(cb).build()
            )
        }
        adblock = prefs.getBoolean("adblock", true)
        loadBlocked()
        setupExtensionPrompts()
        applyRuntimePrefs()

        geckoView = GeckoView(this)
        geckoView.setAutofillEnabled(true) // সিস্টেমের পাসওয়ার্ড ম্যানেজার/অটোফিল

        root = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                gradientType = GradientDrawable.RADIAL_GRADIENT
                gradientRadius = dpf(520)
                setGradientCenter(0.5f, 0f)
                colors = intArrayOf(Color.rgb(58, 10, 10), BG)
            }
            // স্ট্যাটাস বার / নেভিগেশন বারের জায়গা
            setOnApplyWindowInsetsListener { v, ins ->
                @Suppress("DEPRECATION")
                val top: Int
                val bottom: Int
                if (Build.VERSION.SDK_INT >= 30) {
                    val b = ins.getInsets(WindowInsets.Type.systemBars())
                    top = b.top; bottom = b.bottom
                } else {
                    top = ins.systemWindowInsetTop; bottom = ins.systemWindowInsetBottom
                }
                v.setPadding(0, top, 0, bottom)
                ins
            }
        }

        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        topBarView = buildTopBar()
        column.addView(topBarView, LinearLayout.LayoutParams(-1, -2))

        progress = View(this).apply {
            setBackgroundColor(RED)
            pivotX = 0f
            scaleX = 0f
            visibility = View.INVISIBLE
        }
        column.addView(progress, LinearLayout.LayoutParams(-1, dp(2)))
        findBar = buildFindBar()
        column.addView(findBar, LinearLayout.LayoutParams(-1, dp(52)))
        column.addView(geckoView, LinearLayout.LayoutParams(-1, 0, 1f))

        groupStrip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(6), dp(10), dp(6))
            background = glass(0, alpha = 0x28)
            visibility = View.GONE
        }
        column.addView(groupStrip, LinearLayout.LayoutParams(-1, dp(52)))
        column.addView(buildBottomNav(), LinearLayout.LayoutParams(-1, -2))

        root.addView(column, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)

        val first = createTab(true)
        switchTo(tabs.indexOf(first))
        val vu = intentUrl(intent)
        val saved = if (vu == null && prefs.getBoolean("restore", true)) loadSession() else emptyList()
        when {
            vu != null -> first.session.loadUri(vu)
            saved.isNotEmpty() -> {
                first.session.loadUri(saved[0])
                for (k in 1 until saved.size) newTab(false, saved[k], 0, false)
                val si = prefs.getInt("sessIdx", 0)
                if (si in 1 until tabs.size) switchTo(si)
            }
            else -> first.session.loadUri(homeUrl())
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        val u = intentUrl(intent) ?: return
        captureThumb { newTab(false, u) }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // ফুলস্ক্রিন ভিডিও চলার সময় হোমে গেলে ছোট ভাসমান উইন্ডোতে চলতে থাকে (PiP)
        if (fullscreen && Build.VERSION.SDK_INT >= 26) {
            try { enterPictureInPictureMode(PictureInPictureParams.Builder().build()) } catch (e: Exception) { }
        }
    }

    override fun onDestroy() {
        tabs.forEach { it.session.close() }
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (fullscreen) { cur.session.exitFullScreen(); return }
        if (overlay != null) { val c = overlayCancel; dismissOverlay(); c?.invoke(); return }
        if (findBar.visibility == View.VISIBLE) { closeFind(); return }
        val t = cur
        if (t.canBack) t.session.goBack()
        else if (tabs.size > 1) closeTab(current)
        else super.onBackPressed()
    }

    // ================= উপরের বার (Chrome-এর মতো) =================

    private fun buildTopBar(): View {
        val home = glassButton("⌂") { cur.session.loadUri(homeUrl()) }

        addressIcon = TextView(this).apply { text = "🔍"; textSize = 14f }
        addressText = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.WHITE)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        shieldBadge = TextView(this).apply {
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            setPadding(dp(8), dp(3), dp(8), dp(3))
            background = glass(12, Color.rgb(60, 255, 140), 0x55, 0x99)
            visibility = View.GONE
            setOnClickListener { showShieldInfo() }
        }
        addressPill = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), 0, dp(14), 0)
            background = glass(22, alpha = 0x24)
            lift(this, 5)
            addView(addressIcon)
            addView(addressText, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(8) })
            addView(shieldBadge, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(6) })
            setOnClickListener { showOmnibox() }
        }

        val plus = glassButton("＋") { captureThumb { newTab() } }

        tabCount = TextView(this).apply {
            textSize = 12f
            gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                setStroke(dp(2), Color.WHITE)
                cornerRadius = dpf(6)
            }
        }
        val tabBtn = FrameLayout(this).apply {
            background = glass(21)
            lift(this, 6)
            addView(tabCount, FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER))
            setOnClickListener { captureThumb { }; showTabSwitcher() }
            pressable(this)
        }

        val menu = glassButton("⋮") { showMainMenu() }

        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
            clipToPadding = false
            setPadding(dp(8), dp(8), dp(8), dp(8))
            addView(home, LinearLayout.LayoutParams(dp(42), dp(42)))
            addView(addressPill, LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginStart = dp(6) })
            addView(plus, LinearLayout.LayoutParams(dp(42), dp(42)).apply { marginStart = dp(6) })
            addView(tabBtn, LinearLayout.LayoutParams(dp(42), dp(42)).apply { marginStart = dp(6) })
            addView(menu, LinearLayout.LayoutParams(dp(42), dp(42)).apply { marginStart = dp(6) })
        }
    }

    private fun refreshChrome() {
        val t = cur
        val home = isHome(t.url)
        addressIcon.text = when {
            t.priv -> "🕶"
            home -> "🔍"
            t.url.startsWith("https") -> "🔒"
            else -> "🌐"
        }
        addressText.text = if (home) "সার্চ করুন বা URL লিখুন" else display(t.url)
        addressText.setTextColor(if (home) Color.argb(160, 255, 255, 255) else Color.WHITE)
        addressPill.background = glass(22, if (t.priv) VIOLET else Color.WHITE, 0x24)
        tabCount.text = tabs.size.toString()
        updateBadge()
        updateNav()
        rebuildGroupStrip()
    }

    private fun setLoadProgress(p: Int) {
        progress.visibility = if (p in 1..99) View.VISIBLE else View.INVISIBLE
        progress.scaleX = p / 100f
    }

    // ================= ট্যাব =================

    private fun createTab(openNow: Boolean, priv: Boolean = false): Tab {
        val settings = GeckoSessionSettings.Builder().usePrivateMode(priv).useTrackingProtection(adblock).build()
        val s = GeckoSession(settings)
        val tab = Tab(s, priv)

        s.navigationDelegate = object : GeckoSession.NavigationDelegate {
            override fun onCanGoBack(session: GeckoSession, canGoBack: Boolean) { tab.canBack = canGoBack }
            override fun onCanGoForward(session: GeckoSession, canGoForward: Boolean) { tab.canFwd = canGoForward }

            override fun onLocationChange(
                session: GeckoSession,
                url: String?,
                perms: List<GeckoSession.PermissionDelegate.ContentPermission>,
                hasUserGesture: Boolean
            ) {
                val newUrl = url ?: HOME
                if (newUrl != tab.url) tab.blocked = 0
                tab.url = newUrl
                addHistory(tab)
                if (tabs.getOrNull(current) === tab) refreshChrome()
            }

            // mangal://... কমান্ড (হোম পেজ থেকে নেটিভ ফিচার) এবং বাইরের অ্যাপের লিংক (tel:, mailto:, ...)
            override fun onLoadRequest(
                session: GeckoSession,
                request: GeckoSession.NavigationDelegate.LoadRequest
            ): GeckoResult<AllowOrDeny>? {
                val uri = request.uri
                if (uri.startsWith("mangal://")) {
                    geckoView.post { handleCommand(uri.removePrefix("mangal://").trimEnd('/')) }
                    return GeckoResult.fromValue(AllowOrDeny.DENY)
                }
                val scheme = Uri.parse(uri).scheme?.lowercase() ?: ""
                val internal = listOf(
                    "http", "https", "resource", "about", "file", "data",
                    "blob", "javascript", "view-source", "moz-extension"
                )
                if (scheme.isNotEmpty() && scheme !in internal) {
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        return GeckoResult.fromValue(AllowOrDeny.DENY)
                    } catch (e: Exception) { }
                }
                return null
            }

            // target="_blank" / window.open
            override fun onNewSession(session: GeckoSession, uri: String): GeckoResult<GeckoSession>? {
                val nt = createTab(false, tab.priv)
                nt.group = tab.group
                geckoView.post { switchTo(tabs.indexOf(nt)) }
                return GeckoResult.fromValue(nt.session)
            }
        }

        s.contentDelegate = object : GeckoSession.ContentDelegate {
            override fun onTitleChange(session: GeckoSession, title: String?) {
                tab.title = title ?: ""
                updateHistoryTitle(tab)
            }

            override fun onFullScreen(session: GeckoSession, fullScreen: Boolean) {
                if (tabs.getOrNull(current) === tab) setFullscreen(fullScreen)
            }

            // ফাইল ডাউনলোড
            override fun onExternalResponse(session: GeckoSession, response: WebResponse) {
                startDownload(response)
            }

            override fun onContextMenu(
                session: GeckoSession,
                screenX: Int,
                screenY: Int,
                element: GeckoSession.ContentDelegate.ContextElement
            ) {
                showLinkMenu(tab, element)
            }
        }

        s.progressDelegate = object : GeckoSession.ProgressDelegate {
            override fun onProgressChange(session: GeckoSession, progress: Int) {
                if (tabs.getOrNull(current) === tab) setLoadProgress(progress)
            }
        }

        s.contentBlockingDelegate = object : ContentBlocking.Delegate {
            override fun onContentBlocked(session: GeckoSession, event: ContentBlocking.BlockEvent) {
                tab.blocked++
                countBlocked()
                if (tabs.getOrNull(current) === tab) updateBadge()
            }
        }

        s.permissionDelegate = object : GeckoSession.PermissionDelegate {
            override fun onAndroidPermissionsRequest(
                session: GeckoSession,
                permissions: Array<out String>?,
                callback: GeckoSession.PermissionDelegate.Callback
            ) {
                val need = permissions?.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED } ?: emptyList()
                if (need.isEmpty()) { callback.grant(); return }
                permCallback = callback
                requestPermissions(need.toTypedArray(), 1001)
            }

            override fun onContentPermissionRequest(
                session: GeckoSession,
                perm: GeckoSession.PermissionDelegate.ContentPermission
            ): GeckoResult<Int>? {
                val res = GeckoResult<Int>()
                val host = Uri.parse(perm.uri).host ?: perm.uri
                val allow = GeckoSession.PermissionDelegate.ContentPermission.VALUE_ALLOW
                val deny = GeckoSession.PermissionDelegate.ContentPermission.VALUE_DENY
                when (perm.permission) {
                    GeckoSession.PermissionDelegate.PERMISSION_GEOLOCATION -> askPermission("লোকেশন", host) { res.complete(if (it) allow else deny) }
                    GeckoSession.PermissionDelegate.PERMISSION_DESKTOP_NOTIFICATION -> askPermission("নোটিফিকেশন", host) { res.complete(if (it) allow else deny) }
                    GeckoSession.PermissionDelegate.PERMISSION_AUTOPLAY_AUDIBLE -> res.complete(deny)
                    else -> res.complete(allow)
                }
                return res
            }

            override fun onMediaPermissionRequest(
                session: GeckoSession,
                uri: String,
                video: Array<out GeckoSession.PermissionDelegate.MediaSource>?,
                audio: Array<out GeckoSession.PermissionDelegate.MediaSource>?,
                callback: GeckoSession.PermissionDelegate.MediaCallback
            ) {
                val hasV = video != null && video.isNotEmpty()
                val hasA = audio != null && audio.isNotEmpty()
                val what = if (hasV && hasA) "ক্যামেরা ও মাইক্রোফোন" else if (hasV) "ক্যামেরা" else "মাইক্রোফোন"
                askPermission(what, Uri.parse(uri).host ?: uri) { ok ->
                    if (ok) callback.grant(video?.firstOrNull(), audio?.firstOrNull()) else callback.reject()
                }
            }
        }

        s.promptDelegate = object : GeckoSession.PromptDelegate {
            // <input type=file> — ফাইল বাছাই
            override fun onFilePrompt(
                session: GeckoSession,
                prompt: GeckoSession.PromptDelegate.FilePrompt
            ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse>? {
                val res = GeckoResult<GeckoSession.PromptDelegate.PromptResponse>()
                filePrompt = prompt
                fileResult = res
                val i = Intent(Intent.ACTION_GET_CONTENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, prompt.type == GeckoSession.PromptDelegate.FilePrompt.Type.MULTIPLE)
                }
                try {
                    startActivityForResult(i, 2001)
                } catch (e: Exception) {
                    filePrompt = null; fileResult = null
                    res.complete(prompt.dismiss())
                }
                return res
            }

            // alert()
            override fun onAlertPrompt(
                session: GeckoSession,
                prompt: GeckoSession.PromptDelegate.AlertPrompt
            ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse>? {
                val res = GeckoResult<GeckoSession.PromptDelegate.PromptResponse>()
                showPopup("পেজ বলছে", prompt.message ?: "",
                    listOf(MenuItem("✓  ঠিক আছে") { res.complete(prompt.dismiss()) }), false) {
                    res.complete(prompt.dismiss())
                }
                return res
            }

            // confirm()
            override fun onButtonPrompt(
                session: GeckoSession,
                prompt: GeckoSession.PromptDelegate.ButtonPrompt
            ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse>? {
                val res = GeckoResult<GeckoSession.PromptDelegate.PromptResponse>()
                showPopup("নিশ্চিত করুন", prompt.message ?: "", listOf(
                    MenuItem("✓  হ্যাঁ") { res.complete(prompt.confirm(GeckoSession.PromptDelegate.ButtonPrompt.Type.POSITIVE)) },
                    MenuItem("✕  না") { res.complete(prompt.confirm(GeckoSession.PromptDelegate.ButtonPrompt.Type.NEGATIVE)) }
                ), false) { res.complete(prompt.dismiss()) }
                return res
            }
        }

        if (openNow) s.open(runtime!!)
        tabs.add(tab)
        return tab
    }

    private fun switchTo(i: Int) {
        if (findBar.visibility == View.VISIBLE) closeFind()
        if (fullscreen) setFullscreen(false)
        current = i
        geckoView.setSession(tabs[i].session)
        setLoadProgress(0)
        refreshChrome()
    }

    private fun newTab(priv: Boolean = false, url: String = "", group: Int = 0, activate: Boolean = true) {
        val t = createTab(true, priv)
        t.group = group
        t.session.loadUri(if (url.isEmpty()) homeUrl() else url)
        if (activate) switchTo(tabs.indexOf(t)) else refreshChrome()
    }

    private fun closeTab(i: Int) {
        if (tabs.size == 1) { tabs[0].session.loadUri(homeUrl()); return }
        val t = tabs.removeAt(i)
        val wasCurrent = i == current
        if (i < current) current--
        if (wasCurrent) {
            current = minOf(i, tabs.size - 1)
            geckoView.setSession(tabs[current].session)
        }
        t.session.close()
        refreshChrome()
    }

    /** ট্যাব সুইচারের থাম্বনেইলের জন্য বর্তমান পেজের স্ক্রিনশট */
    private fun captureThumb(done: () -> Unit) {
        val t = cur
        try {
            geckoView.capturePixels().then<Void>({ bmp ->
                if (bmp != null) {
                    val w = 360
                    t.thumb = Bitmap.createScaledBitmap(bmp, w, maxOf(1, bmp.height * w / maxOf(1, bmp.width)), true)
                }
                done()
                null
            }, { _ ->
                done()
                null
            })
        } catch (e: Throwable) {
            done()
        }
    }

    // ================= নিচের ট্যাব গ্রুপ স্ট্রিপ =================

    private fun rebuildGroupStrip() {
        val g = cur.group
        val members = if (g == 0) emptyList() else tabs.filter { it.group == g }
        groupStrip.removeAllViews()
        if (members.size < 2) { groupStrip.visibility = View.GONE; return }
        groupStrip.visibility = View.VISIBLE

        members.forEach { m ->
            val isCur = m === cur
            val pill = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(3), dp(3), dp(if (isCur) 8 else 3), dp(3))
                background = if (isCur) glass(18, RED, 0x55, 0xFF) else null
                setOnClickListener { switchTo(tabs.indexOf(m)) }
            }
            pill.addView(letterDot(m, 30))
            if (isCur) {
                pill.addView(TextView(this).apply {
                    text = "✕"; textSize = 12f; setTextColor(Color.WHITE)
                    setPadding(dp(8), 0, 0, 0)
                    setOnClickListener { closeTab(tabs.indexOf(m)) }
                })
            }
            groupStrip.addView(pill, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) })
        }
        groupStrip.addView(glassButton("＋") { newTab(cur.priv, "", g, true) }.apply {
            textSize = 18f
        }, LinearLayout.LayoutParams(dp(36), dp(36)))
        groupStrip.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        groupStrip.addView(glassButton("⌃") { captureThumb { }; showTabSwitcher() }
            .apply { textSize = 18f }, LinearLayout.LayoutParams(dp(36), dp(36)))
    }

    // ================= ওভারলে ব্যবস্থাপনা =================

    private fun addOverlay(v: View) {
        dismissOverlay(false)
        overlay = v
        root.addView(v, FrameLayout.LayoutParams(-1, -1))
        v.alpha = 0f
        v.animate().alpha(1f).setDuration(160).start()
    }

    private fun dismissOverlay(animate: Boolean = true) {
        val v = overlay ?: return
        overlay = null
        renderSwitcher = null
        overlayCancel = null
        hideKeyboard()
        if (animate) v.animate().alpha(0f).setDuration(120).withEndAction { root.removeView(v) }.start()
        else root.removeView(v)
    }

    // ================= ট্যাব সুইচার =================

    private fun showTabSwitcher() {
        showPrivateInSwitcher = cur.priv
        switcherQuery = ""

        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.argb(0xF2, 5, 3, 4))
            isClickable = true
            setPadding(dp(12), dp(8), dp(12), 0)
        }
        val grid = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
        }

        val normalPill = TextView(this).apply { text = "▢ ট্যাব" }
        val privPill = TextView(this).apply { text = "🕶 ইনকগনিটো" }
        listOf(normalPill, privPill).forEach {
            it.textSize = 13f
            it.gravity = Gravity.CENTER
            it.setPadding(dp(14), dp(8), dp(14), dp(8))
        }

        fun renderGrid() {
            grid.removeAllViews()
            val list = tabs.withIndex().filter { iv ->
                iv.value.priv == showPrivateInSwitcher &&
                    (switcherQuery.isBlank() || (iv.value.title + iv.value.url).contains(switcherQuery, true))
            }
            if (list.isEmpty()) {
                grid.addView(TextView(this).apply {
                    text = "কোনো ট্যাব নেই"
                    setTextColor(Color.argb(160, 255, 255, 255))
                    gravity = Gravity.CENTER
                    setPadding(0, dp(60), 0, 0)
                })
                return
            }
            list.chunked(2).forEachIndexed { r, pair ->
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    clipChildren = false
                }
                pair.forEachIndexed { c, iv ->
                    row.addView(
                        tabCard(iv.index, iv.value, r * 2 + c),
                        LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(dp(5), dp(5), dp(5), dp(5)) }
                    )
                }
                if (pair.size == 1) row.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
                grid.addView(row)
            }
        }

        fun styleModes() {
            for ((v, isPriv) in listOf(normalPill to false, privPill to true)) {
                val on = showPrivateInSwitcher == isPriv
                v.background = if (on) glass(18, if (isPriv) VIOLET else RED, 0x66, 0xAA) else null
                v.setTextColor(if (on) Color.WHITE else Color.argb(150, 255, 255, 255))
            }
        }
        normalPill.setOnClickListener { showPrivateInSwitcher = false; styleModes(); renderGrid() }
        privPill.setOnClickListener { showPrivateInSwitcher = true; styleModes(); renderGrid() }

        val modeBox = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = glass(20, alpha = 0x22)
            setPadding(dp(3), dp(3), dp(3), dp(3))
            addView(normalPill)
            addView(privPill)
        }

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
            addView(glassButton("＋") { newTab(priv = showPrivateInSwitcher); dismissOverlay() },
                LinearLayout.LayoutParams(dp(42), dp(42)))
            addView(View(this@MainActivity), LinearLayout.LayoutParams(0, 1, 1f))
            addView(modeBox)
            addView(View(this@MainActivity), LinearLayout.LayoutParams(0, 1, 1f))
            addView(glassButton("✕") { dismissOverlay() }, LinearLayout.LayoutParams(dp(42), dp(42)))
        }
        page.addView(top, LinearLayout.LayoutParams(-1, -2))

        val search = EditText(this).apply {
            hint = "ট্যাব খুঁজুন"
            setHintTextColor(Color.argb(150, 255, 255, 255))
            setTextColor(Color.WHITE)
            textSize = 14f
            isSingleLine = true
            background = glass(16, alpha = 0x22)
            setPadding(dp(16), dp(10), dp(16), dp(10))
            onTextChanged { switcherQuery = it; renderGrid() }
        }
        page.addView(search, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, dp(12), 0, dp(6)) })

        page.addView(ScrollView(this).apply { addView(grid) }, LinearLayout.LayoutParams(-1, 0, 1f))

        renderSwitcher = { renderGrid() }
        styleModes()
        renderGrid()
        addOverlay(page)
    }

    private fun tabCard(index: Int, tab: Tab, order: Int): View {
        val isCur = index == current
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = glass(
                18,
                if (isCur) RED else if (tab.priv) VIOLET else Color.WHITE,
                if (isCur) 0x55 else 0x28,
                if (isCur) 0xFF else 0x55
            )
            clipToOutline = true
            lift(this, if (isCur) 12 else 6, if (isCur) RED else Color.BLACK)
        }

        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(8), dp(4), dp(6))
        }
        head.addView(letterDot(tab, 20))
        head.addView(TextView(this).apply {
            text = tabTitle(tab)
            textSize = 12f
            setTextColor(Color.WHITE)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(6) })
        head.addView(TextView(this).apply {
            text = "✕"
            textSize = 14f
            setTextColor(Color.WHITE)
            setPadding(dp(10), dp(4), dp(10), dp(4))
            setOnClickListener { closeTab(index); renderSwitcher?.invoke() }
        })
        card.addView(head)

        val box = FrameLayout(this)
        val bmp = tab.thumb
        if (bmp != null) {
            val iv = ImageView(this).apply {
                scaleType = ImageView.ScaleType.MATRIX
                setImageBitmap(bmp)
                addOnLayoutChangeListener { v, l, _, r, _, _, _, _, _ ->
                    val sc = (r - l).toFloat() / bmp.width
                    (v as ImageView).imageMatrix = Matrix().apply { setScale(sc, sc) }
                }
            }
            box.addView(iv, FrameLayout.LayoutParams(-1, -1))
        } else {
            box.addView(TextView(this).apply {
                text = "🪐"; textSize = 34f; gravity = Gravity.CENTER
            }, FrameLayout.LayoutParams(-1, -1))
        }
        card.addView(box, LinearLayout.LayoutParams(-1, dp(190)))

        card.setOnClickListener { switchTo(index); dismissOverlay() }
        pressable(card)

        // 3D ঢোকার অ্যানিমেশন
        card.cameraDistance = resources.displayMetrics.density * 9000f
        card.alpha = 0f
        card.rotationX = -22f
        card.translationY = dpf(30)
        card.animate().alpha(1f).rotationX(0f).translationY(0f)
            .setStartDelay(order * 35L).setDuration(260).start()
        return card
    }

    // ================= অ্যাড্রেস বার স্ক্রিন =================

    private fun suggestRow(icon: String, title: String, sub: String, onClick: () -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = glass(16, alpha = 0x22)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            setOnClickListener { onClick() }
            pressable(this)
        }
        row.addView(TextView(this).apply { text = icon; textSize = 18f })
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(TextView(this).apply {
            text = title; textSize = 14f; setTextColor(Color.WHITE); maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })
        col.addView(TextView(this).apply {
            text = sub; textSize = 12f; setTextColor(Color.argb(160, 255, 255, 255)); maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })
        row.addView(col, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(12) })
        return row
    }

    private fun showOmnibox() {
        val t = cur
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.argb(0xF4, 5, 3, 4))
            isClickable = true
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }

        fun go(text: String) { dismissOverlay(); cur.session.loadUri(toUrl(text)) }

        val field = EditText(this).apply {
            hint = "Google সার্চ করুন বা URL লিখুন"
            setHintTextColor(Color.argb(150, 255, 255, 255))
            setTextColor(Color.WHITE)
            textSize = 15f
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_ACTION_GO
            background = null
            setOnEditorActionListener { v, _, _ -> go(v.text.toString()); true }
        }
        val clear = TextView(this).apply {
            text = "✕"; textSize = 16f; setTextColor(Color.WHITE)
            setPadding(dp(8), dp(4), dp(4), dp(4))
            visibility = View.GONE
            setOnClickListener { field.setText("") }
        }
        val pill = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = glass(22, alpha = 0x2A)
            lift(this, 6)
            setPadding(dp(14), 0, dp(10), 0)
            addView(TextView(this@MainActivity).apply { text = "🔍"; textSize = 14f })
            addView(field, LinearLayout.LayoutParams(0, -1, 1f).apply { marginStart = dp(8) })
            addView(clear)
        }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
            addView(glassButton("←") { dismissOverlay() }, LinearLayout.LayoutParams(dp(42), dp(42)))
            addView(pill, LinearLayout.LayoutParams(0, dp(46), 1f).apply { marginStart = dp(8) })
        }
        page.addView(bar, LinearLayout.LayoutParams(-1, -2))

        val typed = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun updateTyped(s: String) {
            clear.visibility = if (s.isEmpty()) View.GONE else View.VISIBLE
            typed.removeAllViews()
            if (s.isBlank()) return
            val isUrl = looksLikeUrl(s)
            typed.addView(
                suggestRow(if (isUrl) "🌐" else "🔍", s, if (isUrl) "ওয়েবসাইট খুলুন" else "Google-এ সার্চ করুন") { go(s) },
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) }
            )
        }
        field.onTextChanged { updateTyped(it) }
        page.addView(typed, LinearLayout.LayoutParams(-1, -2))

        // বর্তমান পেজ (শেয়ার, কপি, এডিট)
        if (!isHome(t.url)) {
            val rowWrap = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = glass(16, alpha = 0x22)
                setPadding(dp(14), dp(10), dp(8), dp(10))
            }
            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            col.addView(TextView(this).apply {
                text = tabTitle(t); textSize = 14f; setTextColor(Color.WHITE); maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            })
            col.addView(TextView(this).apply {
                text = display(t.url); textSize = 12f; setTextColor(Color.argb(160, 255, 255, 255)); maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            })
            rowWrap.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
            fun act(sym: String, f: () -> Unit) = TextView(this).apply {
                text = sym; textSize = 18f; setTextColor(Color.WHITE)
                setPadding(dp(10), dp(6), dp(10), dp(6))
                setOnClickListener { f() }
            }
            rowWrap.addView(act("⇪") { dismissOverlay(); shareText(t.url) })
            rowWrap.addView(act("⧉") { copyText(t.url) })
            rowWrap.addView(act("✎") { field.setText(t.url); field.setSelection(field.text.length) })
            page.addView(rowWrap, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        }

        // শর্টকাট
        val shortcuts = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = glass(18, alpha = 0x1E)
            setPadding(dp(8), dp(14), dp(8), dp(14))
        }
        listOf(
            "Google" to "https://www.google.com",
            "GitHub" to "https://github.com",
            "Blogger" to "https://www.blogger.com",
            "YouTube" to "https://m.youtube.com"
        ).forEach { (name, url) ->
            val cell = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setOnClickListener { go(url) }
                pressable(this)
            }
            cell.addView(TextView(this).apply {
                text = name.take(1); textSize = 20f; gravity = Gravity.CENTER
                typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE)
                background = glass(26, RED, 0x55, 0x99)
                lift(this, 6)
            }, LinearLayout.LayoutParams(dp(52), dp(52)))
            cell.addView(TextView(this).apply {
                text = name; textSize = 11f; setTextColor(Color.argb(200, 255, 255, 255))
                gravity = Gravity.CENTER; setPadding(0, dp(6), 0, 0)
            })
            shortcuts.addView(cell, LinearLayout.LayoutParams(0, -2, 1f))
        }
        page.addView(shortcuts, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })

        if (!isHome(t.url)) { field.setText(t.url); field.selectAll() }
        addOverlay(page)
        page.post {
            field.requestFocus()
            (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(field, 0)
        }
    }

    // ================= পপআপ মেনু (⋮ ও লিংক লং-প্রেস) =================

    // ================= বুকমার্ক ও হিস্টরি (SharedPreferences) =================

    private class Entry(val title: String, val url: String)

    private fun loadList(key: String): MutableList<Entry> {
        val out = mutableListOf<Entry>()
        try {
            val a = JSONArray(prefs.getString(key, "[]"))
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                out.add(Entry(o.optString("t"), o.optString("u")))
            }
        } catch (e: Exception) { }
        return out
    }

    private fun saveList(key: String, list: List<Entry>) {
        val a = JSONArray()
        list.forEach { a.put(JSONObject().put("t", it.title).put("u", it.url)) }
        prefs.edit().putString(key, a.toString()).apply()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private fun isBookmarked(url: String) = loadList("bm").any { it.url == url }

    private fun toggleBookmark(t: Tab) {
        val l = loadList("bm")
        val ex = l.indexOfFirst { it.url == t.url }
        if (ex >= 0) { l.removeAt(ex); toast("বুকমার্ক সরানো হয়েছে") }
        else { l.add(0, Entry(tabTitle(t), t.url)); toast("বুকমার্ক যোগ হয়েছে") }
        saveList("bm", l)
    }

    private fun addHistory(t: Tab) {
        if (t.priv || isHome(t.url) || t.url.startsWith("about:")) return
        val l = loadList("hist")
        if (l.firstOrNull()?.url == t.url) return
        l.add(0, Entry(t.title.ifBlank { display(t.url) }, t.url))
        while (l.size > 300) l.removeAt(l.size - 1)
        saveList("hist", l)
    }

    private fun updateHistoryTitle(t: Tab) {
        if (t.priv || isHome(t.url) || t.title.isBlank()) return
        val l = loadList("hist")
        if (l.firstOrNull()?.url == t.url) { l[0] = Entry(t.title, t.url); saveList("hist", l) }
    }

    private fun showLibrary(startHistory: Boolean) {
        var hist = startHistory
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.argb(0xF2, 5, 3, 4))
            isClickable = true
            setPadding(dp(12), dp(8), dp(12), 0)
        }
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val bmPill = TextView(this).apply { text = "★ বুকমার্ক" }
        val hiPill = TextView(this).apply { text = "🕘 হিস্টরি" }
        listOf(bmPill, hiPill).forEach {
            it.textSize = 13f
            it.gravity = Gravity.CENTER
            it.setPadding(dp(14), dp(8), dp(14), dp(8))
        }
        val clearBtn = glassButton("🗑") { }

        fun render() {
            for ((v, h) in listOf(bmPill to false, hiPill to true)) {
                val on = hist == h
                v.background = if (on) glass(18, RED, 0x66, 0xAA) else null
                v.setTextColor(if (on) Color.WHITE else Color.argb(150, 255, 255, 255))
            }
            clearBtn.visibility = if (hist) View.VISIBLE else View.INVISIBLE
            list.removeAllViews()
            val key = if (hist) "hist" else "bm"
            val items = loadList(key)
            if (items.isEmpty()) {
                list.addView(TextView(this).apply {
                    text = if (hist) "হিস্টরি খালি" else "কোনো বুকমার্ক নেই"
                    setTextColor(Color.argb(160, 255, 255, 255))
                    gravity = Gravity.CENTER
                    setPadding(0, dp(60), 0, 0)
                })
                return
            }
            items.forEach { e ->
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                row.addView(
                    suggestRow(if (hist) "🕘" else "★", e.title.ifBlank { display(e.url) }, display(e.url)) {
                        dismissOverlay(); cur.session.loadUri(e.url)
                    }, LinearLayout.LayoutParams(0, -2, 1f)
                )
                row.addView(TextView(this).apply {
                    text = "✕"; textSize = 16f; setTextColor(Color.WHITE)
                    setPadding(dp(14), dp(8), dp(8), dp(8))
                    setOnClickListener {
                        val l = loadList(key)
                        l.removeAll { x -> x.url == e.url }
                        saveList(key, l)
                        render()
                    }
                })
                list.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
            }
        }
        bmPill.setOnClickListener { hist = false; render() }
        hiPill.setOnClickListener { hist = true; render() }
        clearBtn.setOnClickListener { saveList("hist", emptyList()); render() }

        val modeBox = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = glass(20, alpha = 0x22)
            setPadding(dp(3), dp(3), dp(3), dp(3))
            addView(bmPill)
            addView(hiPill)
        }
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
            addView(glassButton("←") { dismissOverlay() }, LinearLayout.LayoutParams(dp(42), dp(42)))
            addView(View(this@MainActivity), LinearLayout.LayoutParams(0, 1, 1f))
            addView(modeBox)
            addView(View(this@MainActivity), LinearLayout.LayoutParams(0, 1, 1f))
            addView(clearBtn, LinearLayout.LayoutParams(dp(42), dp(42)))
        }
        page.addView(top, LinearLayout.LayoutParams(-1, -2))
        page.addView(ScrollView(this).apply { addView(list) },
            LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(10) })
        render()
        addOverlay(page)
    }

    // ================= পেজে খুঁজুন =================

    private fun buildFindBar(): LinearLayout {
        findField = EditText(this).apply {
            hint = "পেজে খুঁজুন"
            setHintTextColor(Color.argb(150, 255, 255, 255))
            setTextColor(Color.WHITE)
            textSize = 14f
            isSingleLine = true
            background = null
            onTextChanged { q -> findIn(q) }
        }
        findCount = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.argb(180, 255, 255, 255))
            setPadding(dp(6), 0, dp(6), 0)
        }
        fun sym(t: String, f: () -> Unit) = TextView(this).apply {
            text = t; textSize = 18f; setTextColor(Color.WHITE)
            setPadding(dp(12), dp(6), dp(12), dp(6))
            setOnClickListener { f() }
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(4), dp(6), dp(4))
            background = glass(0, alpha = 0x30)
            visibility = View.GONE
            addView(findField, LinearLayout.LayoutParams(0, -1, 1f))
            addView(findCount)
            addView(sym("▲") { findStep(false) })
            addView(sym("▼") { findStep(true) })
            addView(sym("✕") { closeFind() })
        }
    }

    private fun findIn(q: String) {
        val f = cur.session.finder
        if (q.isEmpty()) { f.clear(); findCount.text = ""; return }
        f.find(q, 0).then<Void>({ r ->
            if (r != null) findCount.text = if (r.total > 0) "${r.current}/${r.total}" else "০"
            null
        }, { _ -> null })
    }

    // 0 = সামনে, 1 = পেছনে (FINDER_FIND_BACKWARDS)
    private fun findStep(forward: Boolean) {
        cur.session.finder.find(null, if (forward) 0 else 1)
            .then<Void>({ r ->
                if (r != null) findCount.text = if (r.total > 0) "${r.current}/${r.total}" else "০"
                null
            }, { _ -> null })
    }

    private fun openFind() {
        findBar.visibility = View.VISIBLE
        findField.setText("")
        findField.requestFocus()
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(findField, 0)
    }

    private fun closeFind() {
        try { cur.session.finder.clear() } catch (e: Throwable) { }
        findBar.visibility = View.GONE
        findCount.text = ""
        hideKeyboard()
    }

    // ================= বিজ্ঞাপন ও ট্র্যাকার ব্লক + কাউন্টার =================

    private fun dayIndex() = (System.currentTimeMillis() + java.util.TimeZone.getDefault().rawOffset) / 86400000L

    private fun loadBlocked() {
        val today = dayIndex()
        blockedTotal = prefs.getInt("blk_total", 0)
        val savedDay = prefs.getLong("blk_day", today)
        blockedToday = if (savedDay == today) prefs.getInt("blk_today", 0) else 0
        blockedDay = today
    }

    private fun saveBlocked() {
        prefs.edit()
            .putInt("blk_total", blockedTotal)
            .putInt("blk_today", blockedToday)
            .putLong("blk_day", blockedDay)
            .apply()
        blockedDirty = 0
    }

    private fun countBlocked() {
        val d = dayIndex()
        if (d != blockedDay) { blockedDay = d; blockedToday = 0 }
        blockedToday++
        blockedTotal++
        blockedDirty++
        if (blockedDirty >= 25) saveBlocked()
    }

    override fun onPause() {
        saveBlocked()
        saveSession()
        super.onPause()
    }

    /** হোম পেজ URL-এর # অংশে সংখ্যা পাঠায়: #b=আজ&t=মোট (bridge.js পড়ে দেখায়) */
    private fun homeUrl() = "$HOME#b=$blockedToday&t=$blockedTotal"

    private fun bn(n: Int): String =
        n.toString().map { c -> if (c in '0'..'9') "০১২৩৪৫৬৭৮৯"[c - '0'] else c }.joinToString("")

    private fun updateBadge() {
        val t = cur
        if (isHome(t.url) || t.blocked == 0) { shieldBadge.visibility = View.GONE; return }
        shieldBadge.text = "🛡 ${bn(t.blocked)}"
        shieldBadge.visibility = View.VISIBLE
    }

    private fun showShieldInfo() {
        val t = cur
        showPopup(
            "🛡 ট্র্যাকার সুরক্ষা", "এই পেজে ${bn(t.blocked)}টি আটকানো হয়েছে",
            listOf(
                MenuItem("📊  আজ ${bn(blockedToday)}টি • মোট ${bn(blockedTotal)}টি") { },
                MenuItem(if (adblock) "⏸  সুরক্ষা বন্ধ করুন" else "▶  সুরক্ষা চালু করুন") { toggleAdblock() }
            ),
            false
        )
    }

    private fun toggleAdblock() {
        adblock = !adblock
        prefs.edit().putBoolean("adblock", adblock).apply()
        tabs.forEach { it.session.settings.useTrackingProtection = adblock }
        cur.session.reload()
        toast(if (adblock) "বিজ্ঞাপন ব্লক চালু" else "বিজ্ঞাপন ব্লক বন্ধ")
    }

    // ================= নিচের স্থায়ী বার (সব সাইটে থাকে) =================

    private fun navItem(icon: String, label: String, onClick: () -> Unit): LinearLayout {
        val ic = TextView(this).apply {
            text = icon; textSize = 21f; gravity = Gravity.CENTER; setTextColor(Color.WHITE)
        }
        val lb = TextView(this).apply {
            text = label; textSize = 11f; gravity = Gravity.CENTER
            setTextColor(Color.argb(175, 255, 255, 255))
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            addView(ic)
            addView(lb)
            setOnClickListener { onClick() }
            pressable(this)
        }
    }

    private fun buildBottomNav(): View {
        val home = navItem("⌂", "হোম") { cur.session.loadUri(homeUrl()) }
        navHomeIcon = home.getChildAt(0) as TextView
        navHomeLabel = home.getChildAt(1) as TextView
        val items = listOf(
            home,
            navItem("▢", "ট্যাব") { captureThumb { }; showTabSwitcher() },
            navItem("★", "বুকমার্ক") { showLibrary(false) },
            navItem("⬇", "ডাউনলোড") { showDownloads() },
            navItem("⚙", "সেটিংস") { showSettings() }
        )
        bottomNav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
            background = glass(0, alpha = 0x30)
            setPadding(dp(4), dp(6), dp(4), dp(6))
            lift(this, 10)
        }
        items.forEach { bottomNav.addView(it, LinearLayout.LayoutParams(0, dp(52), 1f)) }
        return bottomNav
    }

    private fun updateNav() {
        val active = isHome(cur.url)
        navHomeIcon.setTextColor(if (active) RED else Color.WHITE)
        navHomeLabel.setTextColor(if (active) RED else Color.argb(175, 255, 255, 255))
        navHomeIcon.setShadowLayer(if (active) dpf(8) else 0f, 0f, 0f, RED)
    }

    private fun openDownloads() {
        try {
            startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS))
        } catch (e: Exception) {
            toast("ডাউনলোড তালিকা খোলা যায়নি")
        }
    }

    // ================= সেটিংস =================

    private fun showSettings() {
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.argb(0xF2, 5, 3, 4))
            isClickable = true
            setPadding(dp(12), dp(8), dp(12), 0)
        }
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        fun render() {
            list.removeAllViews()
            fun row(icon: String, title: String, sub: String, f: () -> Unit) {
                list.addView(
                    suggestRow(icon, title, sub) { f() },
                    LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) }
                )
            }
            row("🛡", "বিজ্ঞাপন ও ট্র্যাকার ব্লক",
                if (adblock) "চালু • চাপলে বন্ধ হবে" else "বন্ধ • চাপলে চালু হবে") { toggleAdblock(); render() }
            row("📊", "আজ ${bn(blockedToday)}টি • মোট ${bn(blockedTotal)}টি আটকানো হয়েছে", "ট্র্যাকার সুরক্ষার হিসাব") { }
            row("🖥", "ডেস্কটপ সাইট",
                if (isHome(cur.url)) "কোনো ওয়েবসাইট খুলে চাপুন"
                else if (cur.desktop) "এই ট্যাবে চালু • চাপলে বন্ধ হবে" else "এই ট্যাবে বন্ধ • চাপলে চালু হবে") {
                if (isHome(cur.url)) toast("আগে কোনো ওয়েবসাইট খুলুন") else { toggleDesktop(); render() }
            }
            row("⭐", "ডিফল্ট ব্রাউজার",
                if (isDefaultBrowser()) "মঙ্গল এখন ডিফল্ট ✓" else "অন্য অ্যাপের লিংক এখানে খুলতে চাপুন") { requestDefaultBrowser() }
            row("🔍", "সার্চ ইঞ্জিন", "${engines[engIdx()].name} • চাপলে বদলাবে") {
                prefs.edit().putInt("engine", (engIdx() + 1) % engines.size).apply(); render()
            }
            row("🔤", "লেখার আকার", "${(fontSteps[fontIdx()] * 100).toInt()}% • চাপলে বদলাবে") {
                prefs.edit().putInt("font", (fontIdx() + 1) % fontSteps.size).apply()
                applyRuntimePrefs(); render()
            }
            row("🌙", "নাইট মোড (সাইটে ডার্ক থিম)",
                if (prefs.getBoolean("night", false)) "চালু • পেজ রিলোড করুন" else "বন্ধ • চাপলে চালু হবে") {
                prefs.edit().putBoolean("night", !prefs.getBoolean("night", false)).apply()
                applyRuntimePrefs(); render()
            }
            row("🗂", "আগের ট্যাব ফিরিয়ে আনুন",
                if (prefs.getBoolean("restore", true)) "চালু • অ্যাপ খুললে আগের ট্যাব আসবে" else "বন্ধ") {
                prefs.edit().putBoolean("restore", !prefs.getBoolean("restore", true)).apply(); render()
            }
            row("📥", "বুকমার্ক ইমপোর্ট", "Chrome বা অন্য ব্রাউজারের HTML ফাইল থেকে") { pickBookmarkFile() }
            row("🍪", "কুকি ও ক্যাশ মুছুন", "সব সাইটের লগইনও চলে যাবে") { clearBrowsingData() }
            row("🧩", "এক্সটেনশন", "uBlock Origin, Dark Reader ও আরও") { dismissOverlay(); showExtensions() }
            row("🕶", "নতুন ইনকগনিটো ট্যাব", "হিস্টরি সেভ হয় না") {
                dismissOverlay(); captureThumb { newTab(priv = true) }
            }
            row("🕘", "হিস্টরি দেখুন", "${bn(loadList("hist").size)}টি পেজ") { dismissOverlay(); showLibrary(true) }
            row("🧹", "হিস্টরি মুছুন", "সব ব্রাউজিং হিস্টরি মুছে যাবে") {
                saveList("hist", emptyList()); toast("হিস্টরি মুছে গেছে"); render()
            }
            row("🗑", "সব বুকমার্ক মুছুন", "${bn(loadList("bm").size)}টি বুকমার্ক আছে") {
                saveList("bm", emptyList()); toast("বুকমার্ক মুছে গেছে"); render()
            }
        }

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
            addView(glassButton("←") { dismissOverlay() }, LinearLayout.LayoutParams(dp(42), dp(42)))
            addView(TextView(this@MainActivity).apply {
                text = "⚙ সেটিংস"; textSize = 18f; typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.WHITE)
                setShadowLayer(dpf(6), 0f, dpf(2), Color.argb(190, 255, 41, 69))
            }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(14) })
        }
        page.addView(top, LinearLayout.LayoutParams(-1, -2))
        page.addView(ScrollView(this).apply { addView(list) },
            LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(6) })
        render()
        addOverlay(page)
    }

    // ================= হোম পেজ থেকে নেটিভ কমান্ড =================

    private fun handleCommand(cmd: String) {
        when (cmd) {
            "tabs" -> { captureThumb { }; showTabSwitcher() }
            "bookmarks" -> showLibrary(false)
            "history" -> showLibrary(true)
            "incognito" -> captureThumb { newTab(priv = true) }
            "newtab" -> captureThumb { newTab() }
            "downloads" -> showDownloads()
            "extensions" -> showExtensions()
            "voice" -> startVoice()
            "settings" -> showSettings()
        }
    }

    // ================= ডাউনলোড (IDM-স্টাইল ম্যানেজার) =================

    private class DlItem(
        val id: Long, val title: String, val status: Int,
        val done: Long, val total: Long, val mime: String?, val uri: String?
    )

    private class DlHolder(
        val card: View, val icon: TextView, val name: TextView, val fill: View,
        val info: TextView, val chip: TextView, val actions: LinearLayout
    ) {
        var lastBytes = 0L
        var lastTime = 0L
        var speed = 0.0
        var lastStatus = -1
    }

    private val dlHandler = Handler(Looper.getMainLooper())

    private fun dm() = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

    private fun enqueueDownload(url: String, name: String, referer: String?) {
        try {
            val req = DownloadManager.Request(Uri.parse(url))
                .setTitle(name)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
            if (referer != null && referer.startsWith("http")) req.addRequestHeader("Referer", referer)
            dm().enqueue(req)
            toast("ডাউনলোড শুরু: $name")
        } catch (e: Exception) {
            toast("ডাউনলোড করা যায়নি")
        }
    }

    /** ছবি / ভিডিও / অডিও / লিংক ডাউনলোড (চেপে ধরা মেনু থেকে) */
    private fun downloadUrl(url: String, kind: String, referer: String?) {
        if (url.startsWith("data:")) { saveDataUri(url); return }
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            toast("এই লিংক ডাউনলোড করা যাবে না"); return
        }
        var name = URLUtil.guessFileName(url, null, null)
        if (name.endsWith(".bin")) {
            val ext = when (kind) { "image" -> ".jpg"; "video" -> ".mp4"; "audio" -> ".mp3"; else -> ".bin" }
            name = "mangal_" + System.currentTimeMillis() + ext
        }
        enqueueDownload(url, name, referer)
    }

    /** data:image/...;base64,... ধরনের ছবি সরাসরি সেভ করে */
    private fun saveDataUri(uri: String) {
        try {
            val comma = uri.indexOf(',')
            val meta = uri.substring(5, comma)
            val mime = meta.substringBefore(';')
            val raw = uri.substring(comma + 1)
            val bytes = if (meta.contains("base64")) android.util.Base64.decode(raw, android.util.Base64.DEFAULT)
            else Uri.decode(raw).toByteArray()
            val ext = android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) ?: "bin"
            val name = "mangal_" + System.currentTimeMillis() + "." + ext
            if (Build.VERSION.SDK_INT >= 29) {
                val cv = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, name)
                    put(MediaStore.Downloads.MIME_TYPE, mime)
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                val u = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv)
                    ?: throw IllegalStateException("insert failed")
                contentResolver.openOutputStream(u)!!.use { it.write(bytes) }
            } else {
                java.io.File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), name).writeBytes(bytes)
            }
            toast("সেভ হয়েছে: $name")
        } catch (e: Exception) {
            toast("ছবি সেভ করা যায়নি")
        }
    }

    /** পেজ নিজে যে ফাইল পাঠায় (PDF, APK, ZIP ইত্যাদি) */
    private fun startDownload(r: WebResponse) {
        val cd = r.headers.entries.firstOrNull { it.key.equals("content-disposition", true) }?.value
        val mime = r.headers.entries.firstOrNull { it.key.equals("content-type", true) }?.value?.substringBefore(';')
        val name = URLUtil.guessFileName(r.uri, cd, mime)
        enqueueDownload(r.uri, name, tabs.getOrNull(current)?.url)
    }

    private fun queryDownloads(): List<DlItem> {
        val out = mutableListOf<DlItem>()
        try {
            dm().query(DownloadManager.Query())?.use { c ->
                while (c.moveToNext()) {
                    out.add(
                        DlItem(
                            c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_ID)),
                            c.getString(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TITLE)) ?: "ফাইল",
                            c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)),
                            c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)),
                            c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)),
                            c.getString(c.getColumnIndexOrThrow(DownloadManager.COLUMN_MEDIA_TYPE)),
                            c.getString(c.getColumnIndexOrThrow(DownloadManager.COLUMN_URI))
                        )
                    )
                }
            }
        } catch (e: Exception) { }
        return out.sortedByDescending { it.id }
    }

    private fun openDownloaded(item: DlItem) {
        try {
            val u = dm().getUriForDownloadedFile(item.id)
            startActivity(
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(u, item.mime ?: "*/*")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: Exception) {
            toast("এই ফাইল খোলার কোনো অ্যাপ পাওয়া যায়নি")
        }
    }

    private fun retryDownload(item: DlItem) {
        val u = item.uri ?: return
        try {
            val req = DownloadManager.Request(Uri.parse(u))
                .setTitle(item.title)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, item.title)
            dm().remove(item.id)
            dm().enqueue(req)
        } catch (e: Exception) {
            toast("আবার চেষ্টা করা যায়নি")
        }
    }

    private fun showDownloads() {
        val holders = mutableMapOf<Long, DlHolder>()
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.argb(0xF2, 5, 3, 4))
            isClickable = true
            setPadding(dp(12), dp(8), dp(12), 0)
        }
        val summary = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.WHITE)
            background = glass(16, RED, 0x30, 0x77)
            lift(this, 8)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            setShadowLayer(dpf(6), 0f, dpf(2), Color.argb(190, 255, 41, 69))
        }
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
        }
        val empty = TextView(this).apply {
            text = "কোনো ডাউনলোড নেই\nকোনো ছবি বা লিংক চেপে ধরে ডাউনলোড করুন"
            gravity = Gravity.CENTER
            setTextColor(Color.argb(160, 255, 255, 255))
            setPadding(0, dp(60), 0, 0)
            visibility = View.GONE
        }

        fun fmt(b: Long): String = android.text.format.Formatter.formatShortFileSize(this, maxOf(0L, b))

        fun iconFor(it: DlItem): String {
            val n = it.title.lowercase()
            val m = it.mime ?: ""
            return when {
                m.startsWith("image") || n.endsWith(".jpg") || n.endsWith(".png") || n.endsWith(".webp") || n.endsWith(".gif") -> "🖼"
                m.startsWith("video") || n.endsWith(".mp4") || n.endsWith(".mkv") || n.endsWith(".webm") -> "🎬"
                m.startsWith("audio") || n.endsWith(".mp3") || n.endsWith(".m4a") -> "🎵"
                n.endsWith(".apk") -> "📱"
                n.endsWith(".zip") || n.endsWith(".rar") || n.endsWith(".7z") -> "📦"
                n.endsWith(".pdf") || n.endsWith(".doc") || n.endsWith(".docx") || n.endsWith(".txt") -> "📄"
                else -> "📁"
            }
        }

        fun mini(label: String, f: () -> Unit) = TextView(this).apply {
            text = label
            textSize = 12f
            setTextColor(Color.WHITE)
            setPadding(dp(14), dp(7), dp(14), dp(7))
            background = glass(14, alpha = 0x30)
            setOnClickListener { f() }
            pressable(this)
        }

        fun deleteBtn(item: DlItem): TextView {
            val b = mini("🗑 মুছুন") { }
            var armed = false
            b.setOnClickListener {
                if (!armed) {
                    armed = true
                    b.text = "নিশ্চিত? ফাইলও মুছবে"
                    b.background = glass(14, RED, 0x66, 0xCC)
                    b.postDelayed({
                        armed = false
                        b.text = "🗑 মুছুন"
                        b.background = glass(14, alpha = 0x30)
                    }, 2500)
                } else {
                    dm().remove(item.id)
                }
            }
            return b
        }

        fun rebuildActions(h: DlHolder, item: DlItem) {
            h.actions.removeAllViews()
            val lp = { LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) } }
            when (item.status) {
                DownloadManager.STATUS_SUCCESSFUL -> {
                    h.actions.addView(mini("▶ খুলুন") { openDownloaded(item) }, lp())
                    h.actions.addView(deleteBtn(item), lp())
                }
                DownloadManager.STATUS_FAILED -> {
                    h.actions.addView(mini("↻ আবার") { retryDownload(item) }, lp())
                    h.actions.addView(deleteBtn(item), lp())
                }
                else -> h.actions.addView(mini("✕ বাতিল") { dm().remove(item.id) }, lp())
            }
        }

        fun makeCard(item: DlItem): DlHolder {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = glass(18, alpha = 0x26)
                lift(this, 7)
                setPadding(dp(12), dp(12), dp(12), dp(10))
            }
            val icon = TextView(this).apply {
                textSize = 20f
                gravity = Gravity.CENTER
                background = glass(21, RED, 0x44, 0x99)
                lift(this, 5)
            }
            val name = TextView(this).apply {
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.WHITE)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }
            val info = TextView(this).apply {
                textSize = 11.5f
                setTextColor(Color.argb(175, 255, 255, 255))
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }
            val chip = TextView(this).apply {
                textSize = 11f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.WHITE)
                setPadding(dp(10), dp(4), dp(10), dp(4))
            }
            val col = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(name)
                addView(info)
            }
            val head = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(icon, LinearLayout.LayoutParams(dp(42), dp(42)))
                addView(col, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(12); marginEnd = dp(8) })
                addView(chip)
            }
            card.addView(head)

            // 3D চকচকে প্রগ্রেস বার
            val fill = FrameLayout(this).apply {
                background = GradientDrawable(
                    GradientDrawable.Orientation.LEFT_RIGHT,
                    intArrayOf(Color.rgb(255, 41, 69), Color.rgb(255, 150, 60))
                ).apply { cornerRadius = dpf(7) }
                pivotX = 0f
                scaleX = 0f
                addView(View(this@MainActivity).apply {
                    background = GradientDrawable(
                        GradientDrawable.Orientation.TOP_BOTTOM,
                        intArrayOf(Color.argb(0x80, 255, 255, 255), Color.TRANSPARENT)
                    ).apply { cornerRadius = dpf(5) }
                }, FrameLayout.LayoutParams(-1, dp(6)).apply { topMargin = dp(1) })
            }
            val track = FrameLayout(this).apply {
                background = GradientDrawable().apply {
                    setColor(Color.argb(0x99, 0, 0, 0))
                    setStroke(dp(1), Color.argb(0x33, 255, 255, 255))
                    cornerRadius = dpf(7)
                }
                clipToOutline = true
                addView(fill, FrameLayout.LayoutParams(-1, -1))
            }
            card.addView(track, LinearLayout.LayoutParams(-1, dp(14)).apply { topMargin = dp(12) })

            val actions = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END
            }
            card.addView(actions, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })

            // 3D ঢোকার অ্যানিমেশন
            card.cameraDistance = resources.displayMetrics.density * 9000f
            card.alpha = 0f
            card.rotationX = -20f
            card.translationY = dpf(24)
            card.animate().alpha(1f).rotationX(0f).translationY(0f).setDuration(260).start()
            return DlHolder(card, icon, name, fill, info, chip, actions)
        }

        fun refresh() {
            val items = queryDownloads()
            val ids = items.map { it.id }.toSet()
            holders.keys.filter { it !in ids }.forEach { id ->
                holders.remove(id)?.let { list.removeView(it.card) }
            }
            items.reversed().forEach { item ->
                if (!holders.containsKey(item.id)) {
                    val h = makeCard(item)
                    holders[item.id] = h
                    list.addView(h.card, 0, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
                }
            }
            empty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE

            var totalSpeed = 0.0
            var running = 0
            val now = System.currentTimeMillis()
            items.forEach { item ->
                val h = holders[item.id] ?: return@forEach
                if (item.status == DownloadManager.STATUS_RUNNING) {
                    running++
                    if (h.lastTime > 0 && item.done >= h.lastBytes) {
                        val inst = (item.done - h.lastBytes) * 1000.0 / maxOf(1L, now - h.lastTime)
                        h.speed = if (h.speed == 0.0) inst else h.speed * 0.6 + inst * 0.4
                    }
                } else {
                    h.speed = 0.0
                }
                h.lastBytes = item.done
                h.lastTime = now
                totalSpeed += h.speed

                val frac = if (item.status == DownloadManager.STATUS_SUCCESSFUL) 1f
                else if (item.total > 0) (item.done.toFloat() / item.total).coerceIn(0f, 1f) else 0f
                h.fill.animate().scaleX(frac).setDuration(400).start()

                h.icon.text = iconFor(item)
                h.name.text = item.title
                val pct = (frac * 100).toInt()
                when (item.status) {
                    DownloadManager.STATUS_SUCCESSFUL -> {
                        h.info.text = "${fmt(if (item.total > 0) item.total else item.done)} • সম্পন্ন"
                        h.chip.text = "✓ সম্পন্ন"
                        h.chip.background = glass(12, Color.rgb(60, 255, 140), 0x55, 0x99)
                    }
                    DownloadManager.STATUS_FAILED -> {
                        h.info.text = "ব্যর্থ হয়েছে • আবার চেষ্টা করুন"
                        h.chip.text = "✕ ব্যর্থ"
                        h.chip.background = glass(12, RED, 0x66, 0xCC)
                    }
                    DownloadManager.STATUS_PAUSED -> {
                        h.info.text = "${fmt(item.done)} • নেটওয়ার্কের অপেক্ষায়"
                        h.chip.text = "⏸ অপেক্ষা"
                        h.chip.background = glass(12, alpha = 0x40)
                    }
                    DownloadManager.STATUS_PENDING -> {
                        h.info.text = "শুরু হচ্ছে…"
                        h.chip.text = "⏳ সারিতে"
                        h.chip.background = glass(12, alpha = 0x40)
                    }
                    else -> {
                        val rem = if (item.total > 0) item.total - item.done else 0L
                        val eta = if (h.speed >= 1 && rem > 0) {
                            val sec = (rem / h.speed).toLong()
                            String.format("%d:%02d", sec / 60, sec % 60)
                        } else "--"
                        val totalTxt = if (item.total > 0) fmt(item.total) else "?"
                        h.info.text = "${fmt(item.done)} / $totalTxt • ${fmt(h.speed.toLong())}/s • ETA $eta"
                        h.chip.text = if (item.total > 0) "⬇ ${bn(pct)}%" else "⬇ চলছে"
                        h.chip.background = glass(12, RED, 0x55, 0xAA)
                    }
                }
                if (h.lastStatus != item.status) {
                    h.lastStatus = item.status
                    rebuildActions(h, item)
                }
            }
            summary.text = "⚡ ${fmt(totalSpeed.toLong())}/s   •   চলছে ${bn(running)}টি   •   মোট ${bn(items.size)}টি"
        }

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
            addView(glassButton("←") { dismissOverlay() }, LinearLayout.LayoutParams(dp(42), dp(42)))
            addView(TextView(this@MainActivity).apply {
                text = "⬇ ডাউনলোড ম্যানেজার"; textSize = 17f; typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.WHITE)
                setShadowLayer(dpf(6), 0f, dpf(2), Color.argb(190, 255, 41, 69))
            }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(14) })
            addView(glassButton("📂") { openDownloads() }, LinearLayout.LayoutParams(dp(42), dp(42)))
        }
        page.addView(top, LinearLayout.LayoutParams(-1, -2))
        page.addView(summary, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        page.addView(empty, LinearLayout.LayoutParams(-1, -2))
        page.addView(ScrollView(this).apply { addView(list) }, LinearLayout.LayoutParams(-1, 0, 1f))

        val tick = object : Runnable {
            override fun run() {
                if (overlay !== page) return
                refresh()
                dlHandler.postDelayed(this, 500)
            }
        }
        refresh()
        addOverlay(page)
        dlHandler.postDelayed(tick, 500)
    }

    // ================= এক্সটেনশন (Firefox add-ons, GeckoView) =================

    private class ExtInfo(
        val name: String, val desc: String, val id: String, val url: String, val icon: String,
        val cat: String = "", val about: String = ""
    )

    /** মঙ্গল এক্সটেনশন স্টোর: নতুন এক্সটেনশন যোগ করতে এখানে একটা ExtInfo লাইন বাড়ান */
    private val curatedExtensions = listOf(
        ExtInfo("uBlock Origin", "শক্তিশালী বিজ্ঞাপন ব্লকার", "uBlock0@raymondhill.net",
            "https://addons.mozilla.org/firefox/downloads/latest/ublock-origin/latest.xpi", "🛡",
            "বিজ্ঞাপন ও নিরাপত্তা",
            "ওয়েবসাইটের বিজ্ঞাপন, পপআপ ও ট্র্যাকার আটকায়। পেজ দ্রুত লোড হয় এবং ডেটা কম খরচ হয়।"),
        ExtInfo("Dark Reader", "যেকোনো ওয়েবসাইটে ডার্ক মোড", "addon@darkreader.org",
            "https://addons.mozilla.org/firefox/downloads/latest/darkreader/latest.xpi", "🌙",
            "চোখের আরাম",
            "সব ওয়েবসাইটকে ডার্ক থিমে বদলে দেয়। রাতে পড়তে চোখের চাপ কমে, উজ্জ্বলতা ও কনট্রাস্ট নিজের মতো ঠিক করা যায়।"),
        ExtInfo("Privacy Badger", "লুকানো ট্র্যাকার আটকায় (EFF)", "jid1-MnnxcxisBPnSXQ@jetpack",
            "https://addons.mozilla.org/firefox/downloads/latest/privacy-badger17/latest.xpi", "🦡",
            "গোপনীয়তা",
            "আপনাকে সাইট থেকে সাইটে অনুসরণ করা অদৃশ্য ট্র্যাকার নিজে শিখে আটকে দেয়। তৈরি করেছে EFF।"),
        ExtInfo("SponsorBlock", "ইউটিউবের স্পনসর অংশ নিজে স্কিপ করে", "sponsorBlocker@ajay.app",
            "https://addons.mozilla.org/firefox/downloads/latest/sponsorblock/latest.xpi", "⏭",
            "ইউটিউব",
            "ভিডিওর স্পনসর, ইন্ট্রো, আউট্রো ও সাবস্ক্রাইব-রিমাইন্ডার ব্যবহারকারীদের দেওয়া তথ্য দেখে নিজে থেকে এড়িয়ে যায়।"),
        ExtInfo("Bitwarden", "নিরাপদ পাসওয়ার্ড ম্যানেজার", "{446900e4-71c2-419f-a6a7-df9c091e268b}",
            "https://addons.mozilla.org/firefox/downloads/latest/bitwarden-password-manager/latest.xpi", "🔐",
            "নিরাপত্তা",
            "সব পাসওয়ার্ড এক জায়গায় এনক্রিপ্ট করে রাখে এবং লগইনের সময় নিজে বসিয়ে দেয়। ওপেন সোর্স ও বিনামূল্যে ব্যবহার করা যায়।"),
        ExtInfo("Return YouTube Dislike", "ইউটিউবে ডিসলাইন সংখ্যা ফিরিয়ে আনে", "{762f9885-5a13-4abd-9c77-433dcd38b8fd}",
            "https://addons.mozilla.org/firefox/downloads/latest/return-youtube-dislike/latest.xpi", "👎",
            "ইউটিউব",
            "ভিডিও দেখার আগেই বোঝা যায় ভিডিওটা কতটা কাজের, কারণ ডিসলাইনের সংখ্যা আবার দেখা যায়।"),
        ExtInfo("I still don't care about cookies", "কুকি পপআপ নিজে বন্ধ করে", "idcac-pub@guus.ninja",
            "https://addons.mozilla.org/firefox/downloads/latest/i-still-dont-care-about-cookies/latest.xpi", "🍪",
            "বিরক্তি কমায়",
            "প্রায় সব ওয়েবসাইটের \"কুকি গ্রহণ করুন\" ব্যানার নিজে থেকে বন্ধ করে দেয়, তাই বারবার চাপ দিতে হয় না।"),
        ExtInfo("Translate Web Pages", "পুরো ওয়েবপেজ এক ট্যাপে অনুবাদ", "{036a55b4-5e72-4d05-a06c-cba2dfcc134a}",
            "https://addons.mozilla.org/firefox/downloads/latest/traduzir-paginas-web/latest.xpi", "🌐",
            "ভাষা",
            "যেকোনো বিদেশি ভাষার পেজ বাংলাসহ পছন্দের ভাষায় অনুবাদ করে। কোনো সাইনআপ লাগে না।"),
        ExtInfo("Video DownloadHelper", "ওয়েবসাইট থেকে ভিডিও ডাউনলোড", "{b9db16a4-6edc-47ec-a1f4-b86292ed211d}",
            "https://addons.mozilla.org/firefox/downloads/latest/video-downloadhelper/latest.xpi", "⬇",
            "ডাউনলোড",
            "পেজে থাকা ভিডিও ও অডিও শনাক্ত করে ডাউনলোডের অপশন দেখায়।"),
        ExtInfo("Decentraleyes", "বাইরের সার্ভারে ট্র্যাকিং কমায়", "jid1-BoFifL9Vbdl2zQ@jetpack",
            "https://addons.mozilla.org/firefox/downloads/latest/decentraleyes/latest.xpi", "👁",
            "গোপনীয়তা",
            "Google-এর মতো বড় কোম্পানির সার্ভার থেকে লাইব্রেরি আনা আটকে নিজের ডিভাইস থেকে দেয়, ফলে সাইট ঠিক থাকে আর ট্র্যাকিং কমে।"),
        ExtInfo("Unhook", "ইউটিউবের অপ্রয়োজনীয় সাজেশন লুকায়", "myallychou@gmail.com",
            "https://addons.mozilla.org/firefox/downloads/latest/youtube-recommended-videos/latest.xpi", "🎯",
            "মনোযোগ",
            "ইউটিউবের রেকমেন্ডেশন, কমেন্ট, শর্টস ও হোমপেজের ভিড় লুকিয়ে শুধু যা খুঁজছেন সেটাই দেখায়।")
    )

    /** ইনস্টলের আগে অনুমতি জিজ্ঞাসা (গ্লাস পপআপ) */
    private fun setupExtensionPrompts() {
        runtime!!.webExtensionController.promptDelegate = object : WebExtensionController.PromptDelegate {
            override fun onInstallPromptRequest(
                extension: WebExtension,
                permissions: Array<String>,
                origins: Array<String>
            ): GeckoResult<WebExtension.PermissionPromptResponse>? {
                val res = GeckoResult<WebExtension.PermissionPromptResponse>()
                val name = extension.metaData.name ?: "এক্সটেনশন"
                showPopup(
                    "$name ইনস্টল করবেন?", "এটি ওয়েবসাইটের ডেটা পড়তে ও বদলাতে পারবে",
                    listOf(
                        MenuItem("✓  ইনস্টল করুন") { res.complete(WebExtension.PermissionPromptResponse(true, true)) },
                        MenuItem("✕  বাতিল") { res.complete(WebExtension.PermissionPromptResponse(false, false)) }
                    ),
                    false
                ) { res.complete(WebExtension.PermissionPromptResponse(false, false)) }
                return res
            }
        }
    }

    private fun installExtension(name: String, url: String, after: () -> Unit) {
        toast("$name ইনস্টল হচ্ছে…")
        runtime!!.webExtensionController.install(url, "manager").then<Void>({ _ ->
            toast("✓ $name ইনস্টল হয়েছে")
            after()
            null
        }, { _ ->
            toast("ইনস্টল হয়নি")
            null
        })
    }

    private fun openStoreSite(url: String) {
        dismissOverlay()
        captureThumb { newTab(false, url) }
    }

    private fun showExtensions() {
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.argb(0xF2, 5, 3, 4))
            isClickable = true
            setPadding(dp(12), dp(8), dp(12), 0)
        }
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            setPadding(0, 0, 0, dp(28))
        }
        val installed = mutableMapOf<String, WebExtension>()

        fun findInstalled(info: ExtInfo): WebExtension? =
            installed[info.id] ?: installed.values.firstOrNull {
                it.metaData.name?.contains(info.name, ignoreCase = true) == true
            }

        /** নিচের 3D স্টোর বাটন */
        fun storeButton(icon: String, title: String, sub: String, onClick: () -> Unit): View {
            val b = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = glass(22, Color.WHITE, 0x30, 0x77)
                setPadding(dp(14), dp(14), dp(14), dp(14))
                lift(this, 12)
                pressable(this)
                setOnClickListener { onClick() }
            }
            b.addView(TextView(this).apply {
                text = icon; textSize = 22f; gravity = Gravity.CENTER
                background = glass(24, RED, 0x44, 0x99)
                lift(this, 6)
            }, LinearLayout.LayoutParams(dp(48), dp(48)))
            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            col.addView(TextView(this).apply {
                text = title; textSize = 16f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE)
                glowText(this)
            })
            col.addView(TextView(this).apply {
                text = sub; textSize = 12f; setTextColor(Color.argb(170, 255, 255, 255))
            })
            b.addView(col, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(14) })
            b.addView(TextView(this).apply {
                text = "›"; textSize = 26f; setTextColor(Color.argb(200, 255, 255, 255))
            })
            return b
        }

        fun render() {
            list.removeAllViews()

            list.addView(TextView(this).apply {
                text = "মঙ্গল স্টোরের বাছাই করা এক্সটেনশন"
                textSize = 13f
                setTextColor(Color.argb(190, 255, 255, 255))
                setPadding(dp(4), dp(8), dp(4), dp(0))
            })

            curatedExtensions.forEach { info ->
                val ext = findInstalled(info)
                val card = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    background = glass(26, alpha = 0x2A, strokeAlpha = 0x77)
                    lift(this, 12)
                    setPadding(dp(18), dp(18), dp(18), dp(18))
                }

                val head = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                head.addView(TextView(this).apply {
                    text = info.icon; textSize = 32f; gravity = Gravity.CENTER
                    background = glass(32, RED, 0x55, 0xAA)
                    lift(this, 8)
                }, LinearLayout.LayoutParams(dp(64), dp(64)))
                val titleCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                titleCol.addView(TextView(this).apply {
                    text = info.name; textSize = 20f; typeface = Typeface.DEFAULT_BOLD
                    setTextColor(Color.WHITE); glowText(this)
                })
                if (info.cat.isNotEmpty()) titleCol.addView(TextView(this).apply {
                    text = info.cat; textSize = 12f; setTextColor(Color.argb(180, 255, 140, 150))
                    setPadding(0, dp(2), 0, 0)
                })
                head.addView(titleCol, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(14) })
                card.addView(head)

                card.addView(TextView(this).apply {
                    text = info.desc; textSize = 15f; typeface = Typeface.DEFAULT_BOLD
                    setTextColor(Color.WHITE)
                    setPadding(0, dp(14), 0, 0)
                })
                if (info.about.isNotEmpty()) card.addView(TextView(this).apply {
                    text = info.about; textSize = 13.5f
                    setTextColor(Color.argb(200, 255, 255, 255))
                    setLineSpacing(dpf(3), 1f)
                    setPadding(0, dp(6), 0, 0)
                })
                if (ext != null) card.addView(TextView(this).apply {
                    text = "✓ ইনস্টল করা আছে"; textSize = 12.5f
                    setTextColor(Color.rgb(109, 255, 176))
                    setPadding(0, dp(10), 0, 0)
                })

                card.addView(TextView(this).apply {
                    text = if (ext != null) "সরান" else "ইনস্টল করুন"
                    textSize = 16f
                    typeface = Typeface.DEFAULT_BOLD
                    gravity = Gravity.CENTER
                    setTextColor(Color.WHITE)
                    background = if (ext != null) glass(24, alpha = 0x30) else glass(24, RED, 0x77, 0xDD)
                    lift(this, 8)
                    pressable(this)
                    setOnClickListener {
                        if (ext != null) {
                            runtime!!.webExtensionController.uninstall(ext).then<Void>({ _ ->
                                toast("সরানো হয়েছে"); installed.remove(ext.id); render(); null
                            }, { _ -> toast("সরানো যায়নি"); null })
                        } else {
                            installExtension(info.name, info.url) {
                                runtime!!.webExtensionController.list().then<Void>({ exts ->
                                    installed.clear()
                                    exts?.forEach { installed[it.id] = it }
                                    render()
                                    null
                                }, { _ -> null })
                            }
                        }
                    }
                }, LinearLayout.LayoutParams(-1, dp(50)).apply { topMargin = dp(16) })

                list.addView(card, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })
            }

            // ===== একদম নিচে: অন্যান্য স্টোর =====
            list.addView(TextView(this).apply {
                text = "অন্যান্য স্টোর থেকে খুঁজুন"
                textSize = 13f
                setTextColor(Color.argb(190, 255, 255, 255))
                setPadding(dp(4), dp(28), dp(4), dp(0))
            })
            list.addView(
                storeButton("🦊", "Mozilla এক্সটেনশন স্টোর", "অফিশিয়াল Firefox add-ons") {
                    openStoreSite("https://addons.mozilla.org/android/")
                },
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) }
            )
            list.addView(
                storeButton("🌐", "Chrome এক্সটেনশন স্টোর", "দেখা যাবে, তবে সরাসরি চলবে না") {
                    openStoreSite("https://chromewebstore.google.com/category/extensions")
                },
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) }
            )
            list.addView(TextView(this).apply {
                text = "দ্রষ্টব্য: Chrome-এর এক্সটেনশন এই ব্রাউজারে সরাসরি ইনস্টল হয় না। Firefox-এ থাকা একই এক্সটেনশন Mozilla স্টোর থেকে নিন।"
                textSize = 11.5f
                setTextColor(Color.argb(150, 255, 255, 255))
                setPadding(dp(4), dp(10), dp(4), 0)
            })
        }

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
            addView(glassButton("←") { dismissOverlay() }, LinearLayout.LayoutParams(dp(42), dp(42)))
            addView(TextView(this@MainActivity).apply {
                text = "🧩 মঙ্গল এক্সটেনশন স্টোর"; textSize = 18f; typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.WHITE)
                setShadowLayer(dpf(6), 0f, dpf(2), Color.argb(190, 255, 41, 69))
            }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(14) })
        }
        page.addView(top, LinearLayout.LayoutParams(-1, -2))
        page.addView(ScrollView(this).apply { addView(list) }, LinearLayout.LayoutParams(-1, 0, 1f))
        render()
        addOverlay(page)

        // ইনস্টল করা এক্সটেনশনগুলোর তালিকা আনা
        runtime!!.webExtensionController.list().then<Void>({ exts ->
            installed.clear()
            exts?.forEach { installed[it.id] = it }
            render()
            null
        }, { _ -> null })
    }

    // ================= পারমিশন, ফাইল বাছাই, ফুলস্ক্রিন =================

    private fun askPermission(what: String, site: String, onAnswer: (Boolean) -> Unit) {
        showPopup("$what ব্যবহারের অনুমতি?", site, listOf(
            MenuItem("✓  অনুমতি দিন") { onAnswer(true) },
            MenuItem("✕  দেবেন না") { onAnswer(false) }
        ), false) { onAnswer(false) }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1001) {
            val cb = permCallback
            permCallback = null
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) cb?.grant()
            else cb?.reject()
        }
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 3002) {
            if (resultCode == RESULT_OK) data?.data?.let { importBookmarks(it) }
            return
        }
        if (requestCode == 3003) {
            if (resultCode == RESULT_OK) {
                val said = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
                if (!said.isNullOrBlank()) cur.session.loadUri(toUrl(said))
            }
            return
        }
        if (requestCode != 2001) return
        val p = filePrompt
        val r = fileResult
        filePrompt = null
        fileResult = null
        if (p == null || r == null) return
        if (resultCode != RESULT_OK || data == null) { r.complete(p.dismiss()); return }
        val uris = mutableListOf<Uri>()
        data.data?.let { uris.add(it) }
        data.clipData?.let { c -> for (i in 0 until c.itemCount) uris.add(c.getItemAt(i).uri) }
        val list = uris.distinct()
        when {
            list.isEmpty() -> r.complete(p.dismiss())
            list.size == 1 -> r.complete(p.confirm(this, list[0]))
            else -> r.complete(p.confirm(this, list.toTypedArray()))
        }
    }

    private fun setFullscreen(on: Boolean) {
        fullscreen = on
        topBarView.visibility = if (on) View.GONE else View.VISIBLE
        bottomNav.visibility = if (on) View.GONE else View.VISIBLE
        progress.visibility = View.INVISIBLE
        if (on) groupStrip.visibility = View.GONE else rebuildGroupStrip()
        if (Build.VERSION.SDK_INT >= 30) {
            window.insetsController?.let {
                if (on) {
                    it.hide(WindowInsets.Type.systemBars())
                    it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                } else {
                    it.show(WindowInsets.Type.systemBars())
                }
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = if (on) (
                View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                ) else View.SYSTEM_UI_FLAG_VISIBLE
        }
    }

    /** পপআপ: আগে পেছনের পেজের ঝাপসা ছবি নেয়, তারপর কাচের কার্ড দেখায় */
    private fun showPopup(
        header: String?, sub: String?, items: List<MenuItem>, topRight: Boolean,
        onCancel: (() -> Unit)? = null
    ) {
        captureBlurred { shot -> buildPopup(shot, header, sub, items, topRight, onCancel) }
    }

    /** পেজের স্ক্রিনশট ছোট করে নেয় (বড় করলে ঝাপসা দেখায়); ৪০০ms-এ না এলে ছবি ছাড়াই চলে */
    private fun captureBlurred(done: (Bitmap?) -> Unit) {
        var fired = false
        val fire: (Bitmap?) -> Unit = { b -> if (!fired) { fired = true; done(b) } }
        geckoView.postDelayed({ fire(null) }, 400)
        try {
            geckoView.capturePixels().then<Void>({ bmp ->
                if (bmp == null) {
                    fire(null)
                } else {
                    val sw = maxOf(1, bmp.width / 12)
                    val sh = maxOf(1, bmp.height / 12)
                    fire(Bitmap.createScaledBitmap(bmp, sw, sh, true))
                }
                null
            }, { _ ->
                fire(null)
                null
            })
        } catch (e: Throwable) {
            fire(null)
        }
    }

    private fun glowText(tv: TextView) {
        tv.setShadowLayer(dpf(6), 0f, dpf(2), Color.argb(190, 255, 41, 69))
    }

    private fun buildPopup(
        shot: Bitmap?, header: String?, sub: String?, items: List<MenuItem>, topRight: Boolean,
        onCancel: (() -> Unit)?
    ) {
        val scrim = FrameLayout(this).apply {
            setBackgroundColor(Color.argb(0x66, 5, 3, 4))
            setOnClickListener { val c = overlayCancel; dismissOverlay(); c?.invoke() }
        }

        // ঝাপসা কাচের ব্যাকগ্রাউন্ড: পেছনের পেজেরই ঝাপসা ছবি
        if (shot != null) {
            val loc = IntArray(2)
            val rloc = IntArray(2)
            geckoView.getLocationInWindow(loc)
            root.getLocationInWindow(rloc)
            val blur = ImageView(this).apply {
                scaleType = ImageView.ScaleType.FIT_XY
                setImageBitmap(shot)
                setColorFilter(Color.argb(120, 12, 4, 8), PorterDuff.Mode.SRC_ATOP)
                if (Build.VERSION.SDK_INT >= 31) {
                    setRenderEffect(RenderEffect.createBlurEffect(24f, 24f, Shader.TileMode.CLAMP))
                }
            }
            scrim.addView(blur, FrameLayout.LayoutParams(geckoView.width, geckoView.height).apply {
                leftMargin = loc[0] - rloc[0]
                topMargin = loc[1] - rloc[1]
            })
        }

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = glass(24, Color.WHITE, 0x3A, 0x88)
            setPadding(dp(6), dp(10), dp(6), dp(10))
            isClickable = true
            lift(this, 18)
        }

        if (header != null) {
            card.addView(TextView(this).apply {
                text = header; textSize = 15f; setTextColor(Color.WHITE); maxLines = 1
                ellipsize = TextUtils.TruncateAt.END; typeface = Typeface.DEFAULT_BOLD
                setPadding(dp(14), dp(6), dp(14), 0)
                glowText(this)
            })
            if (sub != null) card.addView(TextView(this).apply {
                text = sub; textSize = 12f; setTextColor(Color.argb(170, 255, 255, 255)); maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setPadding(dp(14), dp(2), dp(14), dp(8))
            })
        }

        items.forEachIndexed { idx, item ->
            val parts = item.label.split("  ", limit = 2)
            val glyph = if (parts.size == 2) parts[0] else ""
            val lbl = if (parts.size == 2) parts[1] else item.label
            val finalAlpha = if (item.enabled) 1f else .35f

            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10), dp(9), dp(16), dp(9))
                if (item.enabled) setOnClickListener { dismissOverlay(); item.action() }
                pressable(this)
            }
            if (glyph.isNotEmpty()) {
                row.addView(TextView(this).apply {
                    text = glyph
                    textSize = 16f
                    gravity = Gravity.CENTER
                    setTextColor(Color.WHITE)
                    background = glass(18, RED, 0x44, 0x99)
                    lift(this, 6)
                }, LinearLayout.LayoutParams(dp(36), dp(36)))
            }
            row.addView(TextView(this).apply {
                text = lbl
                textSize = 15f
                setTextColor(Color.WHITE)
                glowText(this)
            }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(14) })
            card.addView(row)

            if (idx < items.size - 1) {
                card.addView(View(this).apply { setBackgroundColor(Color.argb(0x22, 255, 255, 255)) },
                    LinearLayout.LayoutParams(-1, dp(1)).apply { setMargins(dp(14), 0, dp(14), 0) })
            }

            // লেখা 3D-তে ভেসে ওঠে (ওপর থেকে উল্টে নেমে আসে)
            row.cameraDistance = resources.displayMetrics.density * 9000f
            row.pivotY = 0f
            row.alpha = 0f
            row.rotationX = -55f
            row.translationY = dpf(18)
            row.animate().alpha(finalAlpha).rotationX(0f).translationY(0f)
                .setStartDelay(120L + idx * 45L).setDuration(320)
                .setInterpolator(OvershootInterpolator(1.2f)).start()
        }

        val w = minOf(dp(300), (resources.displayMetrics.widthPixels * 0.82f).toInt())
        val lp = FrameLayout.LayoutParams(
            w + dp(28), -2,
            if (topRight) Gravity.TOP or Gravity.END else Gravity.CENTER
        )
        if (topRight) { lp.topMargin = dp(46); lp.marginEnd = dp(0) }
        scrim.addView(ScrollView(this).apply {
            setPadding(dp(14), dp(14), dp(14), dp(14))
            clipToPadding = false
            clipChildren = false
            addView(card)
        }, lp)

        // কার্ড 3D-তে খোলে
        card.cameraDistance = resources.displayMetrics.density * 9000f
        card.pivotY = 0f
        card.rotationX = -22f
        card.scaleX = .9f
        card.scaleY = .9f
        card.translationY = dpf(20)
        card.animate().rotationX(0f).scaleX(1f).scaleY(1f).translationY(0f)
            .setDuration(260).setInterpolator(OvershootInterpolator(0.9f)).start()
        addOverlay(scrim)
        overlayCancel = onCancel
    }

    private fun showMainMenu() {
        val t = cur
        val real = !isHome(t.url)
        val bm = real && isBookmarked(t.url)
        showPopup(null, null, listOf(
            MenuItem("←  পেছনে", t.canBack) { t.session.goBack() },
            MenuItem("→  সামনে", t.canFwd) { t.session.goForward() },
            MenuItem("↻  রিফ্রেশ") { t.session.reload() },
            MenuItem("＋  নতুন ট্যাব") { captureThumb { newTab() } },
            MenuItem("🕶  নতুন ইনকগনিটো ট্যাব") { captureThumb { newTab(priv = true) } },
            MenuItem(if (bm) "★  বুকমার্ক সরান" else "☆  বুকমার্কে যোগ করুন", real) { toggleBookmark(t) },
            MenuItem("▤  বুকমার্ক তালিকা") { showLibrary(false) },
            MenuItem("🕘  হিস্টরি") { showLibrary(true) },
            MenuItem("🔎  পেজে খুঁজুন", real) { openFind() },
            MenuItem("⬇  ডাউনলোড ম্যানেজার") { showDownloads() },
            MenuItem("🧩  এক্সটেনশন") { showExtensions() },
            MenuItem(if (adblock) "🛡  বিজ্ঞাপন ব্লক: চালু" else "🛡  বিজ্ঞাপন ব্লক: বন্ধ") { toggleAdblock() },
            MenuItem("⇪  শেয়ার", real) { shareText(t.url) },
            MenuItem("⧉  লিংক কপি", real) { copyText(t.url) },
            MenuItem(if (t.desktop) "✓  ডেস্কটপ সাইট" else "🖥  ডেস্কটপ সাইট", real) { toggleDesktop() },
            MenuItem("🌐  পেজ অনুবাদ (বাংলা)", real) { translatePage(t) }
        ), true)
    }

    private fun toggleDesktop() {
        val t = cur
        t.desktop = !t.desktop
        t.session.settings.userAgentMode =
            if (t.desktop) GeckoSessionSettings.USER_AGENT_MODE_DESKTOP else GeckoSessionSettings.USER_AGENT_MODE_MOBILE
        t.session.settings.viewportMode =
            if (t.desktop) GeckoSessionSettings.VIEWPORT_MODE_DESKTOP else GeckoSessionSettings.VIEWPORT_MODE_MOBILE
        t.session.reload()
    }

    private fun showLinkMenu(tab: Tab, el: GeckoSession.ContentDelegate.ContextElement) {
        val kind = when (el.type) {
            GeckoSession.ContentDelegate.ContextElement.TYPE_IMAGE -> "image"
            GeckoSession.ContentDelegate.ContextElement.TYPE_VIDEO -> "video"
            GeckoSession.ContentDelegate.ContextElement.TYPE_AUDIO -> "audio"
            else -> ""
        }
        val media = if (kind.isNotEmpty()) el.srcUri else null
        val link = el.linkUri
        val main = link ?: media ?: return
        val opened = { Toast.makeText(this, "ট্যাব খোলা হয়েছে", Toast.LENGTH_SHORT).show() }

        val items = mutableListOf<MenuItem>()
        if (link != null) {
            items.add(MenuItem("＋  নতুন ট্যাবে খুলুন") { newTab(tab.priv, link, 0, false); opened() })
            items.add(MenuItem("▦  নতুন ট্যাব গ্রুপে খুলুন") {
                if (tab.group == 0) tab.group = nextGroup++
                newTab(tab.priv, link, tab.group, false)
                opened()
            })
            items.add(MenuItem("🕶  ইনকগনিটো ট্যাবে খুলুন") { newTab(true, link, 0, false); opened() })
        }
        if (media != null) {
            val label = when (kind) { "image" -> "ছবি"; "video" -> "ভিডিও"; else -> "অডিও" }
            items.add(MenuItem("⬇  $label ডাউনলোড") { downloadUrl(media, kind, tab.url) })
            if (kind == "image") items.add(MenuItem("🖼  ছবি নতুন ট্যাবে খুলুন") { newTab(tab.priv, media, 0, false); opened() })
        }
        if (link != null) items.add(MenuItem("⬇  লিংক ডাউনলোড") { downloadUrl(link, "", tab.url) })
        items.add(MenuItem("⧉  ঠিকানা কপি") { copyText(main) })
        items.add(MenuItem("⇪  শেয়ার") { shareText(main) })

        showPopup(Uri.parse(main).host ?: "লিংক", display(main), items, false)
    }
}

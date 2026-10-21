package com.mangal.browser

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
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
        return "https://www.google.com/search?q=" + URLEncoder.encode(s, "UTF-8")
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
        if (runtime == null) runtime = GeckoRuntime.create(applicationContext)

        geckoView = GeckoView(this)

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
        column.addView(buildTopBar(), LinearLayout.LayoutParams(-1, -2))

        progress = View(this).apply {
            setBackgroundColor(RED)
            pivotX = 0f
            scaleX = 0f
            visibility = View.INVISIBLE
        }
        column.addView(progress, LinearLayout.LayoutParams(-1, dp(2)))
        column.addView(geckoView, LinearLayout.LayoutParams(-1, 0, 1f))

        groupStrip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(6), dp(10), dp(6))
            background = glass(0, alpha = 0x28)
            visibility = View.GONE
        }
        column.addView(groupStrip, LinearLayout.LayoutParams(-1, dp(52)))

        root.addView(column, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)

        val first = createTab(true)
        switchTo(tabs.indexOf(first))
        first.session.loadUri(HOME)
    }

    override fun onDestroy() {
        tabs.forEach { it.session.close() }
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (overlay != null) { dismissOverlay(); return }
        val t = cur
        if (t.canBack) t.session.goBack()
        else if (tabs.size > 1) closeTab(current)
        else super.onBackPressed()
    }

    // ================= উপরের বার (Chrome-এর মতো) =================

    private fun buildTopBar(): View {
        val home = glassButton("⌂") { cur.session.loadUri(HOME) }

        addressIcon = TextView(this).apply { text = "🔍"; textSize = 14f }
        addressText = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.WHITE)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        addressPill = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), 0, dp(14), 0)
            background = glass(22, alpha = 0x24)
            lift(this, 5)
            addView(addressIcon)
            addView(addressText, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(8) })
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
        rebuildGroupStrip()
    }

    private fun setLoadProgress(p: Int) {
        progress.visibility = if (p in 1..99) View.VISIBLE else View.INVISIBLE
        progress.scaleX = p / 100f
    }

    // ================= ট্যাব =================

    private fun createTab(openNow: Boolean, priv: Boolean = false): Tab {
        val settings = GeckoSessionSettings.Builder().usePrivateMode(priv).build()
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
                tab.url = url ?: HOME
                if (tabs.getOrNull(current) === tab) refreshChrome()
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
            override fun onTitleChange(session: GeckoSession, title: String?) { tab.title = title ?: "" }

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

        if (openNow) s.open(runtime!!)
        tabs.add(tab)
        return tab
    }

    private fun switchTo(i: Int) {
        current = i
        geckoView.setSession(tabs[i].session)
        setLoadProgress(0)
        refreshChrome()
    }

    private fun newTab(priv: Boolean = false, url: String = HOME, group: Int = 0, activate: Boolean = true) {
        val t = createTab(true, priv)
        t.group = group
        t.session.loadUri(url)
        if (activate) switchTo(tabs.indexOf(t)) else refreshChrome()
    }

    private fun closeTab(i: Int) {
        if (tabs.size == 1) { tabs[0].session.loadUri(HOME); return }
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
        groupStrip.addView(glassButton("＋") { newTab(cur.priv, HOME, g, true) }.apply {
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

    /** পপআপ: আগে পেছনের পেজের ঝাপসা ছবি নেয়, তারপর কাচের কার্ড দেখায় */
    private fun showPopup(header: String?, sub: String?, items: List<MenuItem>, topRight: Boolean) {
        captureBlurred { shot -> buildPopup(shot, header, sub, items, topRight) }
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

    private fun buildPopup(shot: Bitmap?, header: String?, sub: String?, items: List<MenuItem>, topRight: Boolean) {
        val scrim = FrameLayout(this).apply {
            setBackgroundColor(Color.argb(0x66, 5, 3, 4))
            setOnClickListener { dismissOverlay() }
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
    }

    private fun showMainMenu() {
        val t = cur
        val real = !isHome(t.url)
        showPopup(null, null, listOf(
            MenuItem("←  পেছনে", t.canBack) { t.session.goBack() },
            MenuItem("→  সামনে", t.canFwd) { t.session.goForward() },
            MenuItem("↻  রিফ্রেশ") { t.session.reload() },
            MenuItem("＋  নতুন ট্যাব") { captureThumb { newTab() } },
            MenuItem("🕶  নতুন ইনকগনিটো ট্যাব") { captureThumb { newTab(priv = true) } },
            MenuItem("⇪  শেয়ার", real) { shareText(t.url) },
            MenuItem("⧉  লিংক কপি", real) { copyText(t.url) },
            MenuItem(if (t.desktop) "✓  ডেস্কটপ সাইট" else "🖥  ডেস্কটপ সাইট", real) { toggleDesktop() }
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
        val link = el.linkUri ?: el.srcUri ?: return
        val toast = { Toast.makeText(this, "ট্যাব খোলা হয়েছে", Toast.LENGTH_SHORT).show() }
        showPopup(
            Uri.parse(link).host ?: "লিংক", display(link),
            listOf(
                MenuItem("＋  নতুন ট্যাবে খুলুন") { newTab(tab.priv, link, 0, false); toast() },
                MenuItem("▦  নতুন ট্যাব গ্রুপে খুলুন") {
                    if (tab.group == 0) tab.group = nextGroup++
                    newTab(tab.priv, link, tab.group, false)
                    toast()
                },
                MenuItem("🕶  ইনকগনিটো ট্যাবে খুলুন") { newTab(true, link, 0, false); toast() },
                MenuItem("⧉  লিংকের ঠিকানা কপি") { copyText(link) },
                MenuItem("⇪  লিংক শেয়ার") { shareText(link) }
            ),
            false
        )
    }
}

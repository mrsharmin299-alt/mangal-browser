package com.mangal.browser

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView

class MainActivity : Activity() {
    companion object {
        private var runtime: GeckoRuntime? = null
        private const val HOME = "resource://android/assets/web/index.html"
        private val BG = Color.rgb(5, 3, 4)
        private val BAR = Color.rgb(20, 8, 12)
        private val RED = Color.rgb(255, 41, 69)
    }

    private class Tab(val session: GeckoSession) {
        var title = "নতুন ট্যাব"
        var canBack = false
        var canFwd = false
    }

    private val tabs = mutableListOf<Tab>()
    private var current = 0
    private lateinit var geckoView: GeckoView
    private lateinit var backBtn: TextView
    private lateinit var fwdBtn: TextView
    private lateinit var tabCount: TextView

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 30) window.setDecorFitsSystemWindows(false)
        window.statusBarColor = BG
        window.navigationBarColor = BAR

        if (runtime == null) runtime = GeckoRuntime.create(applicationContext)

        geckoView = GeckoView(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
        }
        // স্ট্যাটাস বার / নেভিগেশন বারের জায়গা ছেড়ে দেয় (সাইন-ইন বাটন আর উপরে চলে যাবে না)
        root.setOnApplyWindowInsetsListener { v, ins ->
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

        root.addView(geckoView, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(buildBottomBar(), LinearLayout.LayoutParams(-1, dp(56)))
        setContentView(root)

        val first = createTab(true)
        switchTo(tabs.indexOf(first))
        first.session.loadUri(HOME)
    }

    // ---------- নিচের টুলবার ----------
    private fun barButton(label: String, onClick: () -> Unit) = TextView(this).apply {
        text = label
        textSize = 24f
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(0, -1, 1f)
    }

    private fun buildBottomBar(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(BAR)
        }
        backBtn = barButton("‹") { goBackOrCloseTab() }
        fwdBtn = barButton("›") { tabs[current].session.goForward() }
        val home = barButton("⌂") { tabs[current].session.loadUri(HOME) }
        val add = barButton("＋") { newTab() }

        // ট্যাব সংখ্যার বক্স
        tabCount = TextView(this).apply {
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                setStroke(dp(2), Color.WHITE)
                cornerRadius = dp(6).toFloat()
            }
        }
        val tabsBtn = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, -1, 1f)
            setOnClickListener { showTabs() }
            addView(tabCount, FrameLayout.LayoutParams(dp(26), dp(26), Gravity.CENTER))
        }
        val menu = barButton("⋮") {}
        menu.setOnClickListener { showMenu(it) }

        listOf(backBtn, fwdBtn, home, add, tabsBtn, menu).forEach { bar.addView(it) }
        return bar
    }

    private fun refreshBar() {
        val t = tabs[current]
        backBtn.alpha = if (t.canBack || tabs.size > 1) 1f else .35f
        fwdBtn.alpha = if (t.canFwd) 1f else .35f
        tabCount.text = tabs.size.toString()
    }

    // ---------- ট্যাব ----------
    private fun createTab(openNow: Boolean): Tab {
        val s = GeckoSession()
        val tab = Tab(s)

        s.navigationDelegate = object : GeckoSession.NavigationDelegate {
            override fun onCanGoBack(session: GeckoSession, canGoBack: Boolean) {
                tab.canBack = canGoBack
                if (tabs.getOrNull(current) === tab) refreshBar()
            }

            override fun onCanGoForward(session: GeckoSession, canGoForward: Boolean) {
                tab.canFwd = canGoForward
                if (tabs.getOrNull(current) === tab) refreshBar()
            }

            // target="_blank" ও window.open নতুন ট্যাবে খুলবে
            override fun onNewSession(session: GeckoSession, uri: String): GeckoResult<GeckoSession>? {
                val nt = createTab(false)
                geckoView.post { switchTo(tabs.indexOf(nt)) }
                return GeckoResult.fromValue(nt.session)
            }
        }
        s.contentDelegate = object : GeckoSession.ContentDelegate {
            override fun onTitleChange(session: GeckoSession, title: String?) {
                tab.title = if (title.isNullOrBlank()) "নতুন ট্যাব" else title
            }
        }

        if (openNow) s.open(runtime!!)
        tabs.add(tab)
        return tab
    }

    private fun switchTo(i: Int) {
        current = i
        geckoView.setSession(tabs[i].session)
        refreshBar()
    }

    private fun newTab() {
        val t = createTab(true)
        switchTo(tabs.indexOf(t))
        t.session.loadUri(HOME)
    }

    private fun closeCurrentTab() {
        if (tabs.size == 1) {
            tabs[0].session.loadUri(HOME)
            return
        }
        val t = tabs.removeAt(current)
        val next = minOf(current, tabs.size - 1)
        switchTo(next)
        t.session.close()
    }

    private fun goBackOrCloseTab() {
        val t = tabs[current]
        if (t.canBack) t.session.goBack() else if (tabs.size > 1) closeCurrentTab()
    }

    private fun showTabs() {
        val titles = tabs.mapIndexed { i, t -> (if (i == current) "● " else "○ ") + t.title }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("ট্যাব (${tabs.size})")
            .setItems(titles) { _, i -> switchTo(i) }
            .setPositiveButton("＋ নতুন ট্যাব") { _, _ -> newTab() }
            .setNegativeButton("এই ট্যাব বন্ধ") { _, _ -> closeCurrentTab() }
            .show()
    }

    private fun showMenu(anchor: View) {
        PopupMenu(this, anchor).apply {
            menu.add(0, 1, 0, "নতুন ট্যাব")
            menu.add(0, 2, 1, "রিফ্রেশ")
            menu.add(0, 3, 2, "ট্যাব বন্ধ করুন")
            setOnMenuItemClickListener {
                when (it.itemId) {
                    1 -> newTab()
                    2 -> tabs[current].session.reload()
                    3 -> closeCurrentTab()
                }
                true
            }
            show()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        val t = tabs[current]
        if (t.canBack) t.session.goBack()
        else if (tabs.size > 1) closeCurrentTab()
        else super.onBackPressed()
    }

    override fun onDestroy() {
        tabs.forEach { it.session.close() }
        super.onDestroy()
    }
}

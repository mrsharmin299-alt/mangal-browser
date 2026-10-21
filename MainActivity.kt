package com.mangal.browser

import android.app.Activity
import android.os.Bundle
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView

class MainActivity : Activity() {
    companion object {
        private var runtime: GeckoRuntime? = null
        private const val HOME = "resource://android/assets/web/index.html"
    }

    private lateinit var geckoView: GeckoView
    private lateinit var session: GeckoSession
    private var canGoBack = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = android.graphics.Color.rgb(8, 5, 7)
        window.navigationBarColor = android.graphics.Color.rgb(8, 5, 7)

        if (runtime == null) runtime = GeckoRuntime.create(applicationContext)

        geckoView = GeckoView(this)
        session = GeckoSession()
        session.navigationDelegate = object : GeckoSession.NavigationDelegate {
            override fun onCanGoBack(session: GeckoSession, canGoBack: Boolean) {
                this@MainActivity.canGoBack = canGoBack
            }
        }
        session.open(runtime!!)
        geckoView.setSession(session)
        setContentView(geckoView)
        session.loadUri(HOME)
    }

    override fun onBackPressed() {
        if (canGoBack) session.goBack() else super.onBackPressed()
    }
}

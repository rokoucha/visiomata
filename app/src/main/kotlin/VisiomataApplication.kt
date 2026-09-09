package net.rokoucha.visiomata

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class VisiomataApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        if (BuildConfig.APP_DISTRIBUTION_FEEDBACK_ENABLED) {
            registerActivityLifecycleCallbacks(FirebaseFeedbackInsets)
        }
    }
}

private object FirebaseFeedbackInsets : Application.ActivityLifecycleCallbacks {
    private const val FEEDBACK_ACTIVITY =
        "com.google.firebase.appdistribution.impl.FeedbackActivity"

    override fun onActivityCreated(
        activity: Activity,
        savedInstanceState: Bundle?,
    ) {
        if (activity.javaClass.name != FEEDBACK_ACTIVITY) return

        val content = activity.findViewById<View>(android.R.id.content)
        val initialPaddingLeft = content.paddingLeft
        val initialPaddingTop = content.paddingTop
        val initialPaddingRight = content.paddingRight
        val initialPaddingBottom = content.paddingBottom

        WindowCompat.getInsetsController(activity.window, content).run {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }
        ViewCompat.setOnApplyWindowInsetsListener(content) { view, windowInsets ->
            val safeInsets =
                windowInsets.getInsets(
                    WindowInsetsCompat.Type.systemBars() or
                        WindowInsetsCompat.Type.displayCutout() or
                        WindowInsetsCompat.Type.ime(),
                )
            view.updatePadding(
                left = initialPaddingLeft + safeInsets.left,
                top = initialPaddingTop + safeInsets.top,
                right = initialPaddingRight + safeInsets.right,
                bottom = initialPaddingBottom + safeInsets.bottom,
            )
            windowInsets
        }
        ViewCompat.requestApplyInsets(content)
    }

    override fun onActivityStarted(activity: Activity) = Unit

    override fun onActivityResumed(activity: Activity) = Unit

    override fun onActivityPaused(activity: Activity) = Unit

    override fun onActivityStopped(activity: Activity) = Unit

    override fun onActivitySaveInstanceState(
        activity: Activity,
        outState: Bundle,
    ) = Unit

    override fun onActivityDestroyed(activity: Activity) = Unit
}

package com.milen.grounpringtonesetter

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.TextPaint
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.util.TypedValue
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.core.view.WindowCompat
import androidx.core.view.isVisible
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import com.google.android.gms.ads.MobileAds
import com.milen.grounpringtonesetter.customviews.dialog.ButtonData
import com.milen.grounpringtonesetter.customviews.dialog.showCustomViewAlertDialog
import com.milen.grounpringtonesetter.customviews.ui.texts.CustomTextView
import com.milen.grounpringtonesetter.databinding.ActivityMainBinding
import com.milen.grounpringtonesetter.ui.home.HomeThemeOption
import com.milen.grounpringtonesetter.ui.home.toAppearance
import com.milen.grounpringtonesetter.utils.applyNavAndImePadding
import com.milen.grounpringtonesetter.utils.applyStatusBarPadding

class MainActivity : AppCompatActivity() {

    private lateinit var navController: NavController
    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setWindowBackground()
        WindowCompat.enableEdgeToEdge(window)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyCurrentTheme()

        binding.toolbarMain.applyStatusBarPadding()
        binding.container.applyNavAndImePadding()

        MobileAds.initialize(this)

        val navHostFragment =
            supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        navController = navHostFragment.navController
        navController.setGraph(R.navigation.nav_graph)

        setUpToolbar()
    }

    private fun setWindowBackground() {
        val themeOption = (application as App).currentThemeOption()
        runCatching {
            if (themeOption == HomeThemeOption.CLASSIC) {
                val drawable = ContextCompat.getDrawable(this, R.drawable.ringtone_background_3)
                window.setBackgroundDrawable(drawable)
            } else {
                val backgroundColor = ContextCompat.getColor(
                    this,
                    themeOption.toAppearance().screenBackgroundColorRes
                )
                window.setBackgroundDrawable(ColorDrawable(backgroundColor))
            }
        }.onFailure {
            val fallbackColor = if (themeOption == HomeThemeOption.LIGHT_HIGH_CONTRAST) {
                android.R.color.white
            } else {
                android.R.color.black
            }
            window.setBackgroundDrawableResource(fallbackColor)
        }
    }

    private fun setUpToolbar() {
        binding.toolbarMain.apply {
            setActionClick {
                if (!navController.navigateUp()) {
                    finish()
                }
            }
            setInfoData {
                val msg = android.text.SpannableStringBuilder()
                    .append(getString(R.string.info_text))
                    .append("\n\n")
                    .append(buildSpannableHiperLink())
                    .append("\n\n")
                    .append("(${BuildConfig.VERSION_NAME})")

                showCustomViewAlertDialog(
                    titleResId = R.string.info,
                    customView = CustomTextView(this@MainActivity).apply {
                        setText(msg, TextView.BufferType.SPANNABLE)
                        movementMethod = LinkMovementMethod.getInstance()
                        linksClickable = true
                        highlightColor = Color.TRANSPARENT
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                        setPaddingRelative(dp(24), dp(16), dp(24), dp(8))
                    },
                    confirmButtonData = ButtonData(R.string.ok)
                )
            }
        }
    }

    private fun applyCurrentTheme() {
        val themeOption = (application as App).currentThemeOption()
        val themeAppearance = themeOption.toAppearance()
        val screenBackgroundColor =
            ContextCompat.getColor(this, themeAppearance.screenBackgroundColorRes)
        val textColor = ContextCompat.getColor(this, themeAppearance.textColorRes)
        val iconTintColor = ContextCompat.getColor(this, themeAppearance.iconTintColorRes)
        val progressTintColor =
            ContextCompat.getColor(this, themeAppearance.actionButtonBackgroundColorRes)
        val shouldUseSolidScreenBackground = themeOption != HomeThemeOption.CLASSIC
        val toolbarBackgroundColor = if (themeOption == HomeThemeOption.CLASSIC) {
            Color.TRANSPARENT
        } else {
            screenBackgroundColor
        }

        binding.navHostFragment.setBackgroundColor(
            if (shouldUseSolidScreenBackground) {
                screenBackgroundColor
            } else {
                Color.TRANSPARENT
            }
        )
        binding.toolbarMain.applyColors(
            backgroundColor = toolbarBackgroundColor,
            textColor = textColor,
            iconTintColor = iconTintColor
        )
        binding.progressIndicator.indeterminateTintList = ColorStateList.valueOf(progressTintColor)

        val insetsController = WindowCompat.getInsetsController(window, binding.root)
        val isLightTheme = themeOption == HomeThemeOption.LIGHT_HIGH_CONTRAST
        insetsController.isAppearanceLightStatusBars = isLightTheme
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            insetsController.isAppearanceLightNavigationBars = isLightTheme
        }
    }

    fun handleLoading(isLoading: Boolean) {
        binding.progress.isVisible = isLoading
    }

    fun setCustomTitle(title: String) {
        binding.toolbarMain.setTitle(title)
    }
}

private fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

private fun Activity.buildSpannableHiperLink(
    linkLabel: String = getString(R.string.click_here),
    base: String = getString(R.string.my_song_please_promo_text, linkLabel),
    uriStr: String = getString(R.string.my_song_please_url),
): CharSequence {
    val span = SpannableString(base)
    val start = base.indexOf(linkLabel)
    if (start >= 0) {
        val end = start + linkLabel.length
        span.setSpan(object : ClickableSpan() {
            override fun onClick(widget: View) {
                startActivity(Intent(Intent.ACTION_VIEW, uriStr.toUri()))
            }

            override fun updateDrawState(ds: TextPaint) {
                super.updateDrawState(ds)
                ds.isUnderlineText = true
            }
        }, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
    return span
}

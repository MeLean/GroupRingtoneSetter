package com.milen.grounpringtonesetter


import android.os.Bundle
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import com.google.android.gms.ads.MobileAds
import com.milen.grounpringtonesetter.customviews.dialog.ButtonData
import com.milen.grounpringtonesetter.customviews.dialog.showAlertDialog
import com.milen.grounpringtonesetter.databinding.ActivityMainBinding
import com.milen.grounpringtonesetter.ui.viewmodel.AppViewModel
import com.milen.grounpringtonesetter.ui.viewmodel.AppViewModelFactory
import com.milen.grounpringtonesetter.utils.applyNavAndImePadding
import com.milen.grounpringtonesetter.utils.applyStatusBarPadding


class MainActivity : AppCompatActivity() {

    private lateinit var navController: NavController
    private lateinit var binding: ActivityMainBinding

    private val viewModel: AppViewModel by viewModels {
        AppViewModelFactory.provideFactory(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)

        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbarMain.applyStatusBarPadding()
        binding.container.applyNavAndImePadding()

        MobileAds.initialize(this)

        val navHostFragment =
            supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        navController = navHostFragment.navController
        navController.setGraph(R.navigation.nav_graph)

        setUpToolbar()

        viewModel.start()
    }

    private fun setUpToolbar() {
        binding.toolbarMain.apply {
            setActionClick {
                if (!navController.navigateUp()) {
                    finish()
                }
            }
            setInfoData {
                showAlertDialog(
                    titleResId = R.string.info,
                    message = getString(R.string.info_text) + "\n\n(${BuildConfig.VERSION_NAME})",
                    confirmButtonData = ButtonData(R.string.ok)
                )
            }
        }
    }

    fun handleLoading(isLoading: Boolean) {
        binding.progress.isVisible = isLoading
    }

    fun setCustomTitle(title: String) {
        binding.toolbarMain.setTitle(title)
    }
}
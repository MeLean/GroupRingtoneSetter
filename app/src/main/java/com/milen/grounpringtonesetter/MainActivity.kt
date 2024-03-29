package com.milen.grounpringtonesetter


import android.os.Bundle
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import com.google.android.gms.ads.MobileAds
import com.milen.grounpringtonesetter.databinding.ActivityMainBinding
import com.milen.grounpringtonesetter.screens.viewmodel.MainViewModel
import com.milen.grounpringtonesetter.screens.viewmodel.MainViewModelFactory
import com.milen.grounpringtonesetter.utils.hasInternetConnection
import com.milen.grounpringtonesetter.utils.navigateSingleTop


class MainActivity : AppCompatActivity() {

    private lateinit var navController: NavController
    private lateinit var binding: ActivityMainBinding

    private val viewModel: MainViewModel by viewModels {
        MainViewModelFactory.provideFactory(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        MobileAds.initialize(this)

        val navHostFragment =
            supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        navController = navHostFragment.navController
        navController.setGraph(R.navigation.nav_graph)

        setUpToolbar()

        if (hasInternetConnection()) {
            navController.navigateSingleTop(R.id.homeFragment)
        } else {
            navController.navigateSingleTop(R.id.noInternetFragment)
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
                viewModel.showInfoDialog()
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
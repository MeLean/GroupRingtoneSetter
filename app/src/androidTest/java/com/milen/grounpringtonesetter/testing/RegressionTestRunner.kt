package com.milen.grounpringtonesetter.testing

import android.app.Application
import android.content.Context
import android.os.Bundle
import androidx.test.runner.AndroidJUnitRunner

/**
 * Shared entry point for the regression suite.
 *
 * Keeping a project-owned runner gives the test suite one place to install deterministic
 * application dependencies as feature containers are introduced, without coupling tests to the
 * production manifest.
 */
class RegressionTestRunner : AndroidJUnitRunner() {
    override fun newApplication(
        classLoader: ClassLoader,
        className: String,
        context: Context,
    ): Application = super.newApplication(
        classLoader,
        RegressionTestApplication::class.java.name,
        context,
    )

    override fun onCreate(arguments: Bundle) {
        arguments.putString("clearPackageData", "true")
        super.onCreate(arguments)
    }
}

package net.alexsaez.cadence

import android.app.Application
import android.content.Context
import androidx.test.runner.AndroidJUnitRunner
import net.alexsaez.cadence.di.AppContainer

/** Starts the app with [TestCadenceApp] so tests can inject fakes. */
class CadenceTestRunner : AndroidJUnitRunner() {
    override fun newApplication(cl: ClassLoader?, className: String?, context: Context?): Application =
        super.newApplication(cl, TestCadenceApp::class.java.name, context)
}

class TestCadenceApp : CadenceApp() {
    @Volatile
    var testContainer: AppContainer? = null

    override val container: AppContainer
        get() = testContainer ?: error("TestEnvironment rule did not install a container")
}

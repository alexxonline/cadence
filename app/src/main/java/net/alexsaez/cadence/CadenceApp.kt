package net.alexsaez.cadence

import android.app.Application
import net.alexsaez.cadence.di.AppContainer

open class CadenceApp : Application() {
    open val container: AppContainer by lazy { AppContainer.production(this) }
}

val android.content.Context.appContainer: AppContainer
    get() = (applicationContext as CadenceApp).container

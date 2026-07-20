package io.github.cl0ura.hypericonpack

import android.app.Application
import io.github.cl0ura.hypericonpack.config.IconSettingsStore
import io.github.cl0ura.hypericonpack.xposed.XposedServiceBridge

class HyperIconPackApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        XposedServiceBridge.initialize(this, IconSettingsStore(this).read())
    }
}

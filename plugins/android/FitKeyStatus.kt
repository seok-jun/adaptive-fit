package com.adaptivefit.personal

import android.content.Context
import com.facebook.react.ReactPackage
import com.facebook.react.bridge.*
import com.facebook.react.uimanager.ViewManager

/** Presence only, following the entry names used by pinned expo-secure-store 15.0.8. */
class FitKeyStatus(context: ReactApplicationContext) : ReactContextBaseJavaModule(context) {
    override fun getName() = "FitKeyStatus"

    @ReactMethod
    fun contains(provider: String, promise: Promise) {
        if (provider != "openai" && provider != "anthropic") {
            promise.reject("provider", "Unknown provider")
            return
        }
        try {
            val prefs = reactApplicationContext.getSharedPreferences("SecureStore", Context.MODE_PRIVATE)
            val slot = "adaptivefit.key.$provider"
            promise.resolve(prefs.contains("key_v1-$slot") || prefs.contains(slot))
        } catch (error: RuntimeException) {
            promise.reject("storage", "Storage unavailable")
        }
    }
}

class FitKeyPackage : ReactPackage {
    override fun createNativeModules(context: ReactApplicationContext): List<NativeModule> = listOf(FitKeyStatus(context))
    override fun createViewManagers(context: ReactApplicationContext): List<ViewManager<*, *>> = emptyList()
}

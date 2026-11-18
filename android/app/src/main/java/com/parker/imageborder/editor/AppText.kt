package com.parker.imageborder.editor

import android.content.Context
import android.os.Build
import android.os.LocaleList
import android.content.res.Configuration
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

/** Standard Android string resources, also available to background rendering and export. */
object AppText {
    @Volatile private var application:Context?=null
    private val preview=ThreadLocal<Context>()
    fun initialize(context:Context){application=context.applicationContext}
    fun previewContext(context:Context){if(application==null)preview.set(context)}
    fun context():Context {
        val context=checkNotNull(application?:preview.get())
        if(Build.VERSION.SDK_INT>=33)return context
        val locales=AppCompatDelegate.getApplicationLocales()
        if(locales.isEmpty)return context
        return context.createConfigurationContext(Configuration(context.resources.configuration).apply{setLocales(LocaleList.forLanguageTags(locales.toLanguageTags()))})
    }
    fun get(@StringRes id:Int,vararg args:Any?):String=if(args.isEmpty())context().getString(id)else context().getString(id,*args.map{it?:""}.toTypedArray())
}
fun appText(@StringRes id:Int,vararg args:Any?):String=AppText.get(id,*args)

object AppLanguage {
    fun selection():String {
        // LocaleManager is authoritative on Android 13+, even before an
        // AppCompat activity/delegate has attached after process recreation.
        val language=if(Build.VERSION.SDK_INT>=33)AppText.context().getSystemService(android.app.LocaleManager::class.java).applicationLocales.get(0)?.language
            else AppCompatDelegate.getApplicationLocales().get(0)?.language
        return language?.takeIf{it in listOf("zh","en")}?:"system"
    }
    fun set(value:String){
        require(value in listOf("system","zh","en"))
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(if(value=="system")""else if(value=="zh")"zh-Hans"else "en"))
    }
}

package com.parker.imageborder

import android.content.Intent
import android.os.Bundle
import android.os.Build
import android.net.Uri
import android.content.pm.ActivityInfo
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.parker.imageborder.editor.*
import org.json.JSONTokener

class MainActivity:AppCompatActivity(){
    private val model:EditorViewModel by viewModels()
    private val migration=registerForActivityResult(ActivityResultContracts.StartActivityForResult()){result->
        val text=result.data?.getStringExtra("settings")
        model.migrateLegacy(runCatching{JSONTokener(text?:"null").nextValue() as? String}.getOrNull())
    }
    override fun onCreate(state:Bundle?){
        installSplashScreen();super.onCreate(state);enableEdgeToEdge()
        if(BuildConfig.DEBUG)window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent{EditorScreen(model,
            openLegacy={startActivity(Intent(this,LegacyActivity::class.java))},
            migrate={migration.launch(Intent(this,LegacyActivity::class.java).putExtra("migrate",true))},
            hdrWindow={hdr->window.colorMode=if(hdr)ActivityInfo.COLOR_MODE_HDR else ActivityInfo.COLOR_MODE_DEFAULT})}
        if(state==null)receive(intent)
    }
    override fun onNewIntent(intent:Intent){super.onNewIntent(intent);setIntent(intent);receive(intent)}
    override fun onStop(){
        model.persistNow()
        if(Build.VERSION.SDK_INT<35&&!isChangingConfigurations)(application as EditorApplication).cancelExport.set(true)
        super.onStop()
    }
    @Suppress("DEPRECATION")
    private fun receive(intent:Intent?){
        val uris=when(intent?.action){
            Intent.ACTION_SEND->listOfNotNull(if(Build.VERSION.SDK_INT>=33)intent.getParcelableExtra(Intent.EXTRA_STREAM,Uri::class.java)else intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
            Intent.ACTION_SEND_MULTIPLE->if(Build.VERSION.SDK_INT>=33)intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM,Uri::class.java)?:emptyList()else intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?:emptyList()
            else->emptyList()
        }
        if(uris.isNotEmpty())model.replaceAsset(uris)
    }
}

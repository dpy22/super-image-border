package com.parker.imageborder.editor

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest

@PreviewTest @Preview(name="Metadata form light",widthDp=402,heightDp=600,locale="zh-rCN")
@Composable fun MetadataLightPreview(){MetadataFormPreview(false)}
@PreviewTest @Preview(name="Metadata form dark large",widthDp=360,heightDp=760,fontScale=1.5f,locale="zh-rCN")
@Composable fun MetadataLargePreview(){MetadataFormPreview(true)}
@PreviewTest @Preview(name="English large metadata",widthDp=360,heightDp=760,fontScale=1.5f,locale="en")
@Composable fun MetadataEnglishLargePreview(){MetadataFormPreview(false)}
@Composable private fun MetadataFormPreview(dark:Boolean){
    AppText.previewContext(LocalContext.current)
    LiquidEditorTheme(dark){Surface{Column(Modifier.fillMaxSize().padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
        Text(uiLabel("参数"),style=MaterialTheme.typography.titleLarge)
        for((label,value)in listOf("品牌" to "Canon","相机型号" to "EOS R50","镜头" to "RF-S18–150mm F3.5–6.3 IS STM","拍摄日期" to "2026-10-27 09:30:00","时区" to "+08:00","地点文字" to "")){
            MetadataInputRow(value,uiLabel(label),true,label=="品牌",{},{},{},{})
        }
    }}}
}

package com.parker.imageborder.editor

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest
import com.parker.imageborder.R

@PreviewTest @Preview(name="English text controls",widthDp=320,heightDp=650,locale="en")
@Preview(name="English text controls large",widthDp=320,heightDp=850,fontScale=1.5f,locale="en")
@Composable fun EnglishTextControlsPreview(){
    AppText.previewContext(LocalContext.current)
    LiquidEditorTheme(false){Surface{Column(Modifier.fillMaxSize().padding(16.dp),verticalArrangement=Arrangement.spacedBy(16.dp)){
        Text("Text",style=MaterialTheme.typography.titleLarge)
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
            Text(appText(R.string.ui_text_below_photo_0ff788),Modifier.weight(1f),style=MaterialTheme.typography.labelMedium)
            TextButton({}){Text(appText(R.string.ui_clear_84fcd7))}
        }
        TextAlignment("left",true){}
        ActionRow{
            TextButton({}){Text(appText(R.string.ui_text_color_07f568))}
            TextButton({}){Text(appText(R.string.ui_text_background_15c2a4))}
        }
        MetadataInputRow("Canon EOS R50",appText(R.string.ui_camera_model_e6a388),true,true,{},{},{},{})
        LiquidSliderRow("玻璃不透明度",.45f,0f..1f,onChange={})
    }}}
}

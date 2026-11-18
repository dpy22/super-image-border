package com.parker.imageborder.editor

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.AdaptiveIconDrawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest
import com.parker.imageborder.R

/** Real packaged foreground, rendered with Android's own adaptive overscan for each mask. */
@PreviewTest @Preview(name="Android launcher masks",widthDp=440,heightDp=340,locale="zh-rCN")
@Composable fun BrandMasksPreview(){
    val context=LocalContext.current
    LiquidEditorTheme(false){Surface{Column(Modifier.fillMaxSize().padding(24.dp),verticalArrangement=Arrangement.spacedBy(24.dp)){
        Text("灵感边框",style=MaterialTheme.typography.titleLarge)
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){
            for((kind,label)in listOf("circle" to "圆形","rounded" to "圆角方形","square" to "方形","mono" to "单色")){
                val bitmap=remember(kind){
                    val size=216;val image=Bitmap.createBitmap(size,size,Bitmap.Config.ARGB_8888);val canvas=Canvas(image);val path=Path()
                    when(kind){"circle","mono"->path.addCircle(size/2f,size/2f,size/2f,Path.Direction.CW);"rounded"->path.addRoundRect(RectF(0f,0f,size.toFloat(),size.toFloat()),size*.24f,size*.24f,Path.Direction.CW);else->path.addRect(0f,0f,size.toFloat(),size.toFloat(),Path.Direction.CW)}
                    canvas.clipPath(path);canvas.drawColor(if(kind=="mono")0xffdedde5.toInt()else context.getColor(R.color.brand_green))
                    val drawable=context.getDrawable(if(kind=="mono")R.drawable.brand_theme else R.drawable.brand_foreground)!!.mutate()
                    if(kind=="mono")drawable.setTint(0xff365348.toInt())
                    val extra=(size*AdaptiveIconDrawable.getExtraInsetFraction()).toInt();drawable.setBounds(-extra,-extra,size+extra,size+extra);drawable.draw(canvas);image
                }
                Column(horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(12.dp)){
                    Image(bitmap.asImageBitmap(),label,Modifier.size(72.dp));Text(label,style=MaterialTheme.typography.labelMedium)
                    Image(bitmap.asImageBitmap(),null,Modifier.size(40.dp))
                }
            }
        }
        Text("108 dp 图层 · 主体约 48 dp",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
    }}}
}

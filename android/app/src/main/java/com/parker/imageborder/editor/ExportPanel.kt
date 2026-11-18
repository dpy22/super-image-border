package com.parker.imageborder.editor

import com.parker.imageborder.R

import android.os.Build
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

@Composable internal fun ExportPanel(vm:EditorViewModel,export:(Boolean)->Unit,modifier:Modifier=Modifier){
    val d=vm.document;val o=d.export;val scene=remember(d){vm.app.renderer.layout(d)};val size=scene.output(o)
    var hdrConfirmation by remember{mutableStateOf(false)}
    fun selectHdr(){vm.change(vm.document.copy(adjustments=Adjustments(),export=vm.document.export.copy(format="hdr",split=1,gridSplit=false)));if(!vm.hdrPreview)vm.toggleHdrPreview()}
    if(hdrConfirmation)AlertDialog(onDismissRequest={hdrConfirmation=false},title={Text(appText(R.string.ui_export_ultra_hdr_827b10))},text={Text(appText(R.string.ui_restore_original_colors_and_export_the_whole_hdr_i_ac9209))},confirmButton={TextButton(onClick={hdrConfirmation=false;selectHdr()}){Text(appText(R.string.ui_restore_and_continue_bac923))}},dismissButton={TextButton(onClick={hdrConfirmation=false}){Text(appText(R.string.ui_keep_adjustments_1c9abc))}})
    Column(modifier.fillMaxWidth().navigationBarsPadding()){
        Panel(Modifier.weight(1f).fillMaxWidth()){
            Choices(listOf("original" to appText(R.string.ui_original_photo_pixels_7c0346),"share" to appText(R.string.ui_sharing_a78df1),"custom" to appText(R.string.ui_custom_size_1387e8)),o.mode,wrap=true){mode->vm.change(vm.document.copy(export=vm.document.export.forMode(mode)))}
            Text(appText(R.string.ui_with_frame_1_s_2_s_px_21e661, size.first, size.second),style=MaterialTheme.typography.bodyLarge)
            if(o.mode=="share"){
                Choices(ExportOptions.shareEdges.map{it.toString() to "$it px"},o.maxEdge.toString(),wrap=true){edge->vm.change(vm.document.copy(export=vm.document.export.copy(maxEdge=edge.toInt())))}
                Text(appText(R.string.ui_jpg_quality_95_srgb_20ab2e),style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }else{
                if(o.mode=="custom")Control("输出最长边",o.maxEdge.toFloat(),640f..12000f,vm){vm.change(vm.document.copy(export=vm.document.export.copy(maxEdge=it.roundToInt())),true)}
                val formats=listOf("jpeg" to "JPG","png" to "PNG")+if(Build.VERSION.SDK_INT>=34&&scene.photos.any{it.asset.hdr})listOf("hdr" to "Ultra HDR")else emptyList()
                Choices(formats,o.format){format->if(format=="hdr"){if(!vm.document.adjustments.isNeutral||vm.document.export.gridSplit||vm.document.export.split>1)hdrConfirmation=true else selectHdr()}
                    else vm.change(vm.document.copy(export=vm.document.export.copy(format=format)))}
                if(o.format!="png")Control("JPG 质量",o.quality.toFloat(),80f..100f,vm){vm.change(vm.document.copy(export=vm.document.export.copy(quality=it.roundToInt())),true)}
                if(o.format!="hdr")Choices(listOf("srgb" to "sRGB","source" to appText(R.string.ui_source_gamut_a80a77)),o.profile){vm.change(vm.document.copy(export=vm.document.export.copy(profile=it)))}
                else Text("Display P3 · HDR",style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Toggle(appText(R.string.ui_keep_camera_metadata_4f68fb),o.keepExif){vm.change(vm.document.copy(export=vm.document.export.copy(keepExif=it)))}
            Toggle(appText(R.string.ui_keep_gps_8304d4),o.keepGps){vm.change(vm.document.copy(export=vm.document.export.copy(keepGps=it)))}
            if(o.format!="hdr"){
                Title(appText(R.string.ui_split_image_ae74be))
                Choices(listOf("1" to appText(R.string.ui_whole_image_d557c4),"2" to appText(R.string.ui_2_horizontal_tiles_8afa4e),"3" to appText(R.string.ui_3_horizontal_tiles_c387ff),"4" to appText(R.string.ui_4_horizontal_tiles_9ae4a8),"5" to appText(R.string.ui_5_horizontal_tiles_cde878),"9" to appText(R.string.ui_3_3_grid_47a04d)),if(o.gridSplit)"9"else o.split.toString(),wrap=true){
                    vm.change(vm.document.copy(export=vm.document.export.copy(split=if(it=="9")3 else it.toInt(),gridSplit=it=="9")))}
                if(o.gridSplit||o.split>1){
                    val rows=if(o.gridSplit)3 else 1;val cols=if(o.gridSplit)3 else o.split
                    Column(verticalArrangement=Arrangement.spacedBy(4.dp)){
                        repeat(rows){row->Row(horizontalArrangement=Arrangement.spacedBy(4.dp)){
                            repeat(cols){col->Surface(color=MaterialTheme.colorScheme.primary.copy(alpha=.10f),shape=androidx.compose.foundation.shape.RoundedCornerShape(6.dp),modifier=Modifier.weight(1f)){
                                Column(Modifier.padding(vertical=8.dp),horizontalAlignment=androidx.compose.ui.Alignment.CenterHorizontally){
                                    Text("${row*cols+col+1}",style=MaterialTheme.typography.labelLarge)
                                    val w=(col+1)*size.first/cols-col*size.first/cols;val h=(row+1)*size.second/rows-row*size.second/rows
                                    Text("$w × $h",style=MaterialTheme.typography.labelSmall)
                                }
                            }}
                        }}
                    }
                }
            }
        }
        HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant)
        Column(Modifier.fillMaxWidth().padding(horizontal=16.dp).padding(top=10.dp,bottom=24.dp),verticalArrangement=Arrangement.spacedBy(6.dp)){
            Button(onClick={export(false)},modifier=Modifier.fillMaxWidth().heightIn(min=48.dp),enabled=!d.textOnly||d.caption.isNotBlank()||d.layers.isNotEmpty()){Text(appText(R.string.ui_save_current_image_1d33ed))}
            if(d.assets.size>1&&vm.app.renderer.template(d.template).family!=7)OutlinedButton(onClick={export(true)},modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)){Text(appText(R.string.ui_batch_export_1_s_b95927, d.assets.size))}
        }
    }
}

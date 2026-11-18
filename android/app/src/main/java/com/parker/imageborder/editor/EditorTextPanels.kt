@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.parker.imageborder.editor

import com.parker.imageborder.R

import android.graphics.Color as AColor
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.text.selection.SelectionContainer
import kotlin.math.roundToInt

@Composable internal fun TextAlignment(value:String,extended:Boolean=false,descriptionFormat:Int?=null,onChange:(String)->Unit){
    val options=listOf("left" to appText(R.string.ui_left_564db6),"center" to appText(R.string.ui_center_500932),"right" to appText(R.string.ui_right_ad23b4))+if(extended)listOf("justify" to appText(R.string.ui_justify_076df0),"distribute" to appText(R.string.ui_distribute_90399b))else emptyList()
    val itemWidth=options.maxOf{labelWidth(it.second,MaterialTheme.typography.labelSmall)}.plus(20.dp).coerceAtLeast(56.dp)
    val scroll=rememberScrollState()
    Row(Modifier.fillMaxWidth().horizontalScroll(scroll),horizontalArrangement=Arrangement.spacedBy(4.dp)){
        for((key,label)in options){
            val selected=key==value
            val icon=when(key){"left"->Icons.Outlined.FormatAlignLeft;"center"->Icons.Outlined.FormatAlignCenter;"right"->Icons.Outlined.FormatAlignRight;"justify"->Icons.Outlined.FormatAlignJustify;else->Icons.Outlined.SpaceBar}
            Surface(onClick={onChange(key)},shape=RoundedCornerShape(12.dp),color=if(selected)MaterialTheme.colorScheme.primary.copy(alpha=.13f)else Color.Transparent,
                modifier=Modifier.width(itemWidth).heightIn(min=52.dp).semantics{this.selected=selected;contentDescription=appText(descriptionFormat?:if(extended)R.string.ui_body_alignment_1_s_90ead4 else R.string.ui_watermark_alignment_1_s_cc1f90,uiLabel(label))}){
                Column(Modifier.padding(vertical=6.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.Center){Icon(icon,null,Modifier.size(19.dp),tint=if(selected)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant);Text(label,style=MaterialTheme.typography.labelSmall,maxLines=1,softWrap=false)}
            }
        }
    }
}

@Composable internal fun ParagraphPanel(vm:EditorViewModel,font:()->Unit,color:(Int,(Int)->Unit)->Unit){
    val d=vm.document;val style=d.textStyle;val scene=vm.app.renderer.layout(d);val unit=scene.unit
    val bodyWidth=if(d.textOnly)scene.width-unit*(d.frame.left+d.frame.right)else scene.body?.width()?:scene.photos.takeIf{it.isNotEmpty()}?.let{photos->photos.maxOf{it.rect.right}-photos.minOf{it.rect.left}}?:1024f
    val textSize=CaptionTypography.geometry(style,bodyWidth,unit,vm.app.renderer.typeface(style.font,style.bold)).textSize
    fun update(next:TextBlockStyle,continuous:Boolean=false)=vm.change(vm.document.copy(textStyle=next),continuous)
    Panel{
        val captionLabel=if(d.textOnly)appText(R.string.ui_canvas_text_d8a9ba)else appText(R.string.ui_text_below_photo_0ff788)
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
            Text(captionLabel,Modifier.weight(1f),style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick={vm.change(vm.document.copy(caption=""))},enabled=d.caption.isNotBlank(),modifier=Modifier.semantics{contentDescription=appText(R.string.ui_clear_body_text_55a6d8)}){Text(appText(R.string.ui_clear_84fcd7))}
        }
        EditorText(d.caption,captionLabel,vm,multi=true,showLabel=false){vm.change(vm.document.copy(caption=it.take(20000)),true)}
        TextAlignment(style.alignment,true){update(vm.document.textStyle.copy(alignment=it))}
        Row(verticalAlignment=Alignment.CenterVertically){
            Box(Modifier.weight(1f)){Choices(listOf("sans-serif" to appText(R.string.ui_system_1a1f6d),"serif" to appText(R.string.ui_serif_ccf2a4),"monospace" to appText(R.string.ui_monospace_c49fc6))+if(style.font.startsWith("/"))listOf(style.font to appText(R.string.ui_custom_font_63a8e8))else emptyList(),style.font){update(vm.document.textStyle.copy(font=it))}}
            TextButton(onClick=font){Text(appText(R.string.ui_import_font_4a9676))}
        }
        Title(appText(R.string.ui_text_width_de5123))
        Choices(listOf("photo" to if(d.textOnly)appText(R.string.ui_match_canvas_edb491)else appText(R.string.ui_match_photo_c76b61),"chars" to appText(R.string.ui_by_character_count_8f076b),"fixed" to appText(R.string.ui_fixed_width_c828a5)),style.widthMode){update(vm.document.textStyle.copy(widthMode=it,widthRatio=vm.document.textStyle.widthRatio?:.9f))}
        if(style.widthMode=="chars")Control("每行字数",style.characters.toFloat(),5f..120f,vm){update(vm.document.textStyle.copy(characters=it.roundToInt()),true)}
        val availableWidth=(1f-unit*style.horizontalPadding*2/bodyWidth).coerceIn(.05f,1f)
        if(style.widthMode=="fixed")Control("文字宽度",(style.widthRatio?:.9f).coerceAtMost(availableWidth),.05f..availableWidth,vm,pixelScale=bodyWidth){update(vm.document.textStyle.copy(widthRatio=it,width=(bodyWidth*it).roundToInt()),true)}
        if(style.widthMode=="chars")Row(Modifier.fillMaxWidth().heightIn(min=48.dp),verticalAlignment=Alignment.CenterVertically){
            Text(appText(R.string.ui_font_size_576ccd),Modifier.weight(1f),style=MaterialTheme.typography.bodyMedium)
            Text(appText(R.string.ui_1_s_px_auto_c9c45a, textSize.roundToInt()),style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }else Control("字号",style.size,.008f..0.10f,vm,pixelScale=if(style.sizeBasis=="width")bodyWidth else unit){update(vm.document.textStyle.copy(size=it),true)}
        Control("行距",style.lineSpacing,1f..3f,vm){update(vm.document.textStyle.copy(lineSpacing=(it*100).roundToInt()/100f),true)}
        Control("段间距",style.paragraphSpacing,0f..3f,vm){update(vm.document.textStyle.copy(paragraphSpacing=it),true)}
        Row{StyleChoice("加粗",style.bold){update(vm.document.textStyle.copy(bold=it))}}
        ActionRow{
            TextButton(onClick={color(style.color){update(vm.document.textStyle.copy(color=it,autoColor=false))}}){Icon(Icons.Outlined.FormatColorText,null,Modifier.size(20.dp));Spacer(Modifier.width(6.dp));Text(appText(R.string.ui_text_color_07f568))}
            TextButton(onClick={color(style.background?:d.frame.color){update(vm.document.textStyle.copy(background=it))}}){Icon(Icons.Outlined.FormatColorFill,null,Modifier.size(20.dp));Spacer(Modifier.width(6.dp));Text(appText(R.string.ui_text_background_15c2a4))}
        }
        Toggle(appText(R.string.ui_automatic_text_color_a63ded),style.autoColor){update(vm.document.textStyle.copy(autoColor=it))}
        if(style.background!=null)TextButton(onClick={update(vm.document.textStyle.copy(background=null))}){Text(appText(R.string.ui_match_frame_background_e0cb4d))}
        Control("图文间距",style.gap,0f..0.15f,vm){update(vm.document.textStyle.copy(gap=it),true)}
        Control("左右留白",style.horizontalPadding,0f..0.12f,vm){update(vm.document.textStyle.copy(sidePadding=it),true)}
        Control("上下留白",style.padding,0f..0.12f,vm){update(vm.document.textStyle.copy(padding=it,sidePadding=vm.document.textStyle.horizontalPadding),true)}
        if(!d.textOnly&&vm.app.renderer.template(d.template).family in listOf(4,6))EditorText(d.title,appText(R.string.ui_template_title_778d92),vm){vm.change(vm.document.copy(title=it.take(2000)),true)}
    }
}

@Composable private fun MetadataField(vm:EditorViewModel,field:String,individual:Boolean){
    val d=vm.document;val value=d.fields()[field]?:"";val override=d.current?.let{d.overrides[it.id]}
    val manual=field in (override?.values?:emptyMap())||field in d.metadata&&field !in (override?.autoFields?:emptySet())
    fun applyValue(new:String?,visible:Boolean?=null){vm.change(vm.document.withMetadataField(field,new,individual,visible),new!=null&&visible==null)}
    MetadataInputRow(Assets.editorValue(field,value),uiLabel(Assets.fieldNames.getValue(field)),field !in d.hidden(),manual,
        visibility={applyValue(null,it)},change={applyValue(it)},clear={applyValue("")},restore={applyValue(null)},
        input={modifier->EditorText(Assets.editorValue(field,value),uiLabel(Assets.fieldNames.getValue(field)),vm,modifier,showLabel=false){applyValue(it)}})

}

/** Labels stay outside the control row so every action shares the input centre. */
@Composable internal fun MetadataInputRow(value:String,label:String,visible:Boolean,manual:Boolean,visibility:(Boolean)->Unit,change:(String)->Unit,clear:()->Unit,restore:()->Unit,input:(@Composable (Modifier)->Unit)?=null){
    val clearLabel=appText(R.string.ui_clear_84fcd7);val restoreLabel=appText(R.string.ui_restore_79748c)
    val clearWidth=(labelWidth(clearLabel,MaterialTheme.typography.bodySmall)+16.dp).coerceAtLeast(48.dp)
    val restoreWidth=(labelWidth(restoreLabel,MaterialTheme.typography.bodySmall)+16.dp).coerceAtLeast(48.dp)
    BoxWithConstraints(Modifier.fillMaxWidth()){
    val stacked=maxWidth-48.dp-clearWidth-restoreWidth<160.dp
    @Composable fun Actions(){
        TextButton(onClick=clear,enabled=value.isNotEmpty(),contentPadding=PaddingValues(0.dp),modifier=Modifier.width(clearWidth).heightIn(min=48.dp).semantics{contentDescription=appText(R.string.ui_clear_1_s_c7e3ae, uiLabel(label))}){Text(clearLabel,style=MaterialTheme.typography.bodySmall,maxLines=1,softWrap=false)}
        TextButton(onClick=restore,enabled=manual,contentPadding=PaddingValues(0.dp),modifier=Modifier.width(restoreWidth).heightIn(min=48.dp).semantics{contentDescription=appText(R.string.ui_restore_automatic_1_s_f8e14d, uiLabel(label))}){Text(restoreLabel,style=MaterialTheme.typography.bodySmall,maxLines=1,softWrap=false)}
    }
    Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(4.dp)){
        Text(label,Modifier.padding(start=48.dp),style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
            Checkbox(visible,visibility,Modifier.size(48.dp).semantics{contentDescription=appText(R.string.ui_show_1_s_373e37, uiLabel(label))})
            if(input!=null)input(Modifier.weight(1f))else LiquidTextField(value,label,change,Modifier.weight(1f),showLabel=false)
            if(!stacked)Actions()
        }
        if(stacked)Row(Modifier.fillMaxWidth().padding(start=48.dp),horizontalArrangement=Arrangement.End,verticalAlignment=Alignment.CenterVertically){Actions()}
    }
    }
}

@Composable internal fun MetadataLayoutChoices(style:MetadataStyle,template:TemplateDefinition,aspect:Float,onChange:(MetadataStyle)->Unit){
    Title(appText(R.string.ui_metadata_position))
    Choices(listOf("top" to appText(R.string.ui_top_046ca7),"bottom" to appText(R.string.ui_bottom_343b38),"left" to appText(R.string.ui_left_856692),"right" to appText(R.string.ui_right_b3ceb9)),style.position(template,aspect),wrap=true){onChange(style.copy(position=it))}
    Title(appText(R.string.ui_metadata_arrangement))
    Choices(listOf("stacked" to appText(R.string.ui_metadata_stacked),"split" to appText(R.string.ui_metadata_split)),style.arrangement(template),wrap=true){onChange(style.copy(arrangement=it))}
    Title(appText(R.string.ui_metadata_alignment))
    TextAlignment(style.alignment(template),descriptionFormat=R.string.ui_metadata_alignment_value){onChange(style.copy(alignment=it))}
}

@Composable internal fun MetadataPanel(vm:EditorViewModel){
    val d=vm.document;var individual by remember{mutableStateOf(true)}
    var layoutOpen by remember{mutableStateOf(false)}
    val populated=remember(d.id,d.current?.id,d.current?.exif){Assets.fieldNames.keys.filter{!d.fields()[it].isNullOrBlank()}}
    var missing by remember(d.id,d.current?.id){mutableStateOf(populated.isEmpty())}
    Panel{
        Toggle(appText(R.string.ui_show_camera_details_7640e2),d.showMetadata,vm::setMetadataVisible)
        if(d.showMetadata){
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
                TextButton(onClick={layoutOpen=!layoutOpen},modifier=Modifier.testTag("metadata-layout-toggle")){
                    Text(appText(R.string.ui_metadata_layout));Icon(if(layoutOpen)Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,null)
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick={vm.change(vm.document.copy(metadataStyle=MetadataStyle()))},enabled=d.metadataStyle!=MetadataStyle()){
                    Text(appText(R.string.ui_reset_a19193))
                }
            }
            if(layoutOpen){
                val scene=vm.app.renderer.layout(d);val photo=scene.photos.firstOrNull()?.rect
                val template=scene.template;val style=d.metadataStyle;val aspect=photo?.let{it.width()/it.height()}?:1f
                val position=style.position(template,aspect)
                MetadataLayoutChoices(style,template,aspect){vm.change(vm.document.copy(metadataStyle=it))}
                Control("照片间距",style.gap(d.frame,position),0f..0.3f,vm){vm.change(vm.document.copy(metadataStyle=vm.document.metadataStyle.copy(gap=it)),true)}
                if(position in listOf("left","right"))Control("参数区宽度",style.columnWidth,.25f..1.2f,vm){vm.change(vm.document.copy(metadataStyle=vm.document.metadataStyle.copy(columnWidth=it)),true)}
            }
            if(d.assets.size>1)Toggle(appText(R.string.ui_edit_this_photo_only_2a923a),individual){individual=it}
            for(field in populated)key(field){MetadataField(vm,field,individual)}
            TextButton(onClick={missing=!missing}){Icon(if(missing)Icons.Outlined.ExpandLess else Icons.Outlined.Add,null);Text(if(missing)appText(R.string.ui_hide_empty_fields_131828)else appText(R.string.ui_add_camera_details_a6387b))}
            if(missing)for(field in Assets.fieldNames.keys.filter{it !in populated})key(field){MetadataField(vm,field,individual)}
        }
    }
}

@Composable private fun StyleChoice(label:String,checked:Boolean,change:(Boolean)->Unit){
    val visible=when(label){"深色底"->"底色";"锁定位置"->"锁定";else->label}
    FilterChip(selected=checked,onClick={change(!checked)},label={Text(uiLabel(visible),style=MaterialTheme.typography.labelMedium)},
        modifier=Modifier.heightIn(min=48.dp).semantics{contentDescription=uiLabel(label)},colors=liquidChipColors(),border=null,shape=RoundedCornerShape(16.dp))
}

@Composable internal fun WatermarkPanel(vm:EditorViewModel,logo:()->Unit,font:()->Unit,color:(Int,(Int)->Unit)->Unit){
    val d=vm.document;val selected=d.layers.firstOrNull{it.id==vm.selectedLayer}
    val keyboard=LocalSoftwareKeyboardController.current;val focus=LocalFocusManager.current
    val typing=WindowInsets.ime.getBottom(LocalDensity.current)>0&&(vm.textEditorOpen||selected?.uri?.isBlank()==true)
    fun finish(){vm.finishTextWatermark();focus.clearFocus();keyboard?.hide()}
    Panel{
        if(!typing&&!vm.textEditorOpen)Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)){
            TextButton(onClick=vm::beginTextWatermark,enabled=d.layers.size<30){Icon(Icons.Outlined.Add,null,Modifier.size(20.dp));Text(appText(R.string.ui_add_text_c20593))}
            TextButton(onClick=logo,enabled=d.layers.size<30){Icon(Icons.Outlined.AddPhotoAlternate,null,Modifier.size(20.dp));Spacer(Modifier.width(4.dp));Text(appText(R.string.ui_import_logo_b6ae9e))}
            if(selected!=null)TextButton(onClick={finish();vm.deselectLayer()}){Text(appText(R.string.ui_deselect_f02e94),style=MaterialTheme.typography.labelMedium)}
        }
        if(!typing&&!vm.textEditorOpen&&d.layers.isNotEmpty())LazyRow(horizontalArrangement=Arrangement.spacedBy(6.dp)){
            itemsIndexed(d.layers,key={_,layer->layer.id}){index,layer->
                val name=if(layer.uri.isNotBlank())"Logo ${index+1}"else layer.text.replace('\n',' ').take(10)
                Box(Modifier.height(48.dp).width(IntrinsicSize.Max).widthIn(min=80.dp,max=180.dp)){
                    Surface(color=if(layer.id==vm.selectedLayer)MaterialTheme.colorScheme.primary.copy(alpha=.13f)else MaterialTheme.colorScheme.onSurface.copy(alpha=.06f),shape=RoundedCornerShape(18.dp),modifier=Modifier.align(Alignment.Center).fillMaxWidth().height(32.dp)){}
                    Box(Modifier.fillMaxSize().clickable{finish();vm.selectLayer(layer.id)}.semantics{this.selected=layer.id==vm.selectedLayer},contentAlignment=Alignment.CenterStart){
                        Text(name,maxLines=1,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.labelMedium,modifier=Modifier.padding(start=12.dp,end=42.dp))
                    }
                    IconButton(onClick={vm.removeLayer(layer.id)},modifier=Modifier.align(Alignment.CenterEnd).size(48.dp)){Icon(Icons.Outlined.Close,appText(R.string.ui_delete_watermark_1_s_388ec1, index+1),Modifier.size(15.dp))}
                }
            }
        }
        if(selected!=null&&!typing){
            val layer=selected
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(4.dp)){
                if(layer.uri.isBlank()){
                    StyleChoice("加粗",layer.bold){vm.updateLayer(layer.copy(bold=it))}
                    StyleChoice("描边",layer.outline){vm.updateLayer(layer.copy(outline=it))}
                }
                StyleChoice("阴影",layer.shadow){vm.updateLayer(layer.copy(shadow=it))}
                StyleChoice("深色底",layer.background){vm.updateLayer(layer.copy(background=it))}
                StyleChoice("锁定位置",layer.locked){vm.updateLayer(layer.copy(locked=it))}
            }
        }
        if(vm.textEditorOpen||selected!=null&&selected.uri.isBlank()){
            var focused by remember{mutableStateOf(false)};val request=remember{FocusRequester()}
            LaunchedEffect(vm.textEditorOpen){if(vm.textEditorOpen&&vm.selectedLayer==null)request.requestFocus()}
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Text(appText(R.string.ui_watermark_text_ca25db),style=MaterialTheme.typography.labelMedium,modifier=Modifier.weight(1f));
                TextButton(onClick={vm.removeLayer();finish()},modifier=Modifier.semantics{contentDescription=appText(R.string.ui_delete_watermark_6ed303)}){Text(appText(R.string.ui_delete_3755f5))}
                TextButton(onClick=::finish,modifier=Modifier.semantics{contentDescription=appText(R.string.ui_finish_text_editing_5375cc)}){Text(appText(R.string.ui_done_33246f))}}
            LiquidTextField(if(vm.textEditorOpen)vm.watermarkInput else selected?.text?:"",appText(R.string.ui_text_465861),vm::editWatermarkText,
                modifier=Modifier.focusRequester(request).onFocusChanged{state->if(state.isFocused&&!focused)vm.beginGesture();if(!state.isFocused&&focused)vm.finishTextWatermark();focused=state.isFocused},multi=true)
        }
        if(selected!=null){
            val layer=selected
            fun update(next:WatermarkLayer,continuous:Boolean=false)=vm.updateLayer(next,continuous)
            ActionRow{TextButton(onClick={update(layer.copy(tiled=false,background=false,angle=0f,opacity=.85f))}){Text(appText(R.string.ui_signature_eb5b27))};TextButton(onClick={update(layer.copy(background=true,bold=true,tiled=false,opacity=1f))}){Text(appText(R.string.ui_label_ae0a7a))};TextButton(onClick={update(layer.copy(tiled=true,angle=-30f,opacity=.24f,anchor="canvas"))}){Text(appText(R.string.ui_tile_30c39a))}}
            val layerScene=remember(d){vm.app.renderer.layout(d)}
            val shape=runCatching{vm.app.renderer.layerShape(layer,layerScene)}.getOrNull()
            val renderedSize=shape?.let{if(layer.uri.isBlank())it.paint.textSize*it.fit else it.width*it.fit}
            Control(if(layer.uri.isBlank())"字号"else "Logo 宽度",layer.size,(if(layer.uri.isBlank()).008f else .0025f)..0.15f,vm,pixelScale=layerScene.unit*layer.scale*(if(layer.uri.isBlank())1f else 8f)){v->vm.document.layers.firstOrNull{it.id==layer.id}?.let{update(it.copy(size=v),true)}}
            if(shape!=null&&shape.fit<.999f&&renderedSize!=null)Text(appText(R.string.ui_numeric_fitted,renderedSize.roundToInt()),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            Control("不透明度",layer.opacity,0f..1f,vm){v->vm.document.layers.firstOrNull{it.id==layer.id}?.let{update(it.copy(opacity=v),true)}}
            if(layer.uri.isBlank()){
                TextAlignment(layer.alignment){update(layer.copy(alignment=it))}
                ActionRow{TextButton(onClick={color(layer.color){update(layer.copy(color=it))}}){Icon(Icons.Outlined.FormatColorText,null,Modifier.size(20.dp));Text(appText(R.string.ui_text_color_07f568))};TextButton(onClick=font){Icon(Icons.Outlined.FileUpload,null,Modifier.size(20.dp));Text(appText(R.string.ui_import_font_4a9676))}}
                Choices(listOf("sans-serif" to appText(R.string.ui_system_1a1f6d),"serif" to appText(R.string.ui_serif_ccf2a4),"monospace" to appText(R.string.ui_monospace_c49fc6))+if(layer.font.startsWith("/"))listOf(layer.font to appText(R.string.ui_custom_font_63a8e8))else emptyList(),layer.font){update(layer.copy(font=it))}
            }
            if(!layer.locked){
                if(!layer.tiled){
                    Choices(listOf("photo" to appText(R.string.ui_position_on_photo_b8a2da),"canvas" to appText(R.string.ui_position_on_canvas_ecf1b0)),layer.anchor){update(layer.copy(anchor=it))}
                    val positionWidth=(listOf(appText(R.string.ui_bottom_center_db1b59),appText(R.string.ui_middle_right_50827c),appText(R.string.ui_top_center_be38c8)).maxOf{labelWidth(it,MaterialTheme.typography.labelLarge)}+24.dp).coerceAtLeast(88.dp)
                    Column(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())){
                    for(y in listOf(0f,.5f,1f))Row{
                        for(x in listOf(0f,.5f,1f)){val labels=listOf(appText(R.string.ui_top_left_734cc2),appText(R.string.ui_top_center_be38c8),appText(R.string.ui_top_right_34f14b),appText(R.string.ui_middle_left_e5c7e0),appText(R.string.ui_center_500932),appText(R.string.ui_middle_right_50827c),appText(R.string.ui_bottom_left_3f4046),appText(R.string.ui_bottom_center_db1b59),appText(R.string.ui_bottom_right_a3b8fc));val index=(y*2).toInt()*3+(x*2).toInt()
                            TextButton(onClick={update(layer.copy(x=x,y=y))},modifier=Modifier.width(positionWidth).heightIn(min=48.dp).semantics{this.selected=layer.x==x&&layer.y==y}){Text(labels[index],maxLines=1,softWrap=false)}}
                    }
                    }
                }
                Control("旋转角度",layer.angle,-180f..180f,vm){v->vm.document.layers.firstOrNull{it.id==layer.id}?.let{update(it.copy(angle=v),true)}}
                if(layer.tiled)Control("平铺间距",layer.spacing,.03f..0.5f,vm){v->vm.document.layers.firstOrNull{it.id==layer.id}?.let{update(it.copy(spacing=v),true)}}
            }
        }
    }
}

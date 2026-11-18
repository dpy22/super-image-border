package com.parker.imageborder.editor

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.parker.imageborder.BuildConfig
import com.parker.imageborder.R

/** One grid for section headings, row labels, separators and trailing accessories. */
internal object SettingsGrid {
    val page=16.dp
    val inset=16.dp
    val row=64.dp
    val accessory=24.dp
    val gap=12.dp
}

@Composable internal fun SettingsScreen(reduceTransparency:Boolean,changeTransparency:(Boolean)->Unit,back:()->Unit,legacy:()->Unit,language:String=AppLanguage.selection(),changeLanguage:(String)->Unit=AppLanguage::set){
    var selectedLanguage by rememberSaveable{mutableStateOf(language)}
    val owner=LocalLifecycleOwner.current;val inspection=LocalInspectionMode.current
    LaunchedEffect(language){selectedLanguage=language}
    DisposableEffect(owner,inspection){
        val observer=LifecycleEventObserver{_,event->if(!inspection&&event==Lifecycle.Event.ON_RESUME)selectedLanguage=AppLanguage.selection()}
        owner.lifecycle.addObserver(observer);onDispose{owner.lifecycle.removeObserver(observer)}
    }
    var languages by rememberSaveable{mutableStateOf(false)}
    var privacy by rememberSaveable{mutableStateOf(false)}
    fun leave(){if(privacy)privacy=false else back()}
    BackHandler{leave()}
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).safeDrawingPadding()){
        LiquidSurface(Modifier.fillMaxWidth().padding(horizontal=SettingsGrid.page,vertical=10.dp),shape=RoundedCornerShape(28.dp)){
            Row(Modifier.fillMaxWidth().heightIn(min=56.dp).padding(horizontal=4.dp),verticalAlignment=Alignment.CenterVertically){
                LiquidIconButton(Icons.AutoMirrored.Outlined.ArrowBack,appText(R.string.settings_back),::leave)
                Text(appText(if(privacy)R.string.settings_privacy else R.string.settings_title),style=MaterialTheme.typography.titleMedium,modifier=Modifier.weight(1f),textAlign=androidx.compose.ui.text.style.TextAlign.Center)
                Spacer(Modifier.width(48.dp))
            }
        }
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal=SettingsGrid.page).padding(top=12.dp,bottom=24.dp),verticalArrangement=Arrangement.spacedBy(24.dp)){
            if(privacy){
                PrivacySection(appText(R.string.privacy_photos_title),appText(R.string.privacy_photos_body))
                PrivacySection(appText(R.string.privacy_access_title),appText(R.string.privacy_access_body))
                PrivacySection(appText(R.string.privacy_metadata_title),appText(R.string.privacy_metadata_body))
                PrivacySection(appText(R.string.privacy_records_title),appText(R.string.privacy_records_body))
            }else{
                Row(Modifier.fillMaxWidth().padding(horizontal=SettingsGrid.inset,vertical=8.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(14.dp)){
                    BrandIcon(Modifier.size(48.dp))
                    Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)){
                        Text(appText(R.string.app_name),Modifier.fillMaxWidth(),style=MaterialTheme.typography.titleLarge)
                        Text(appText(R.string.brand_tagline),Modifier.fillMaxWidth(),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                SettingsSection("general",appText(R.string.settings_general)){
                    SettingsItem("language",appText(R.string.settings_language),languageName(selectedLanguage),action={languages=true})
                    SettingsDivider()
                    SettingsItem("transparency",appText(R.string.settings_reduce_transparency),checked=reduceTransparency,changeChecked=changeTransparency)
                }
                SettingsSection("library",appText(R.string.settings_library)){
                    SettingsItem("drafts",appText(R.string.settings_drafts),appText(R.string.settings_recent_drafts))
                    SettingsDivider()
                    SettingsItem("exports",appText(R.string.settings_exports),appText(R.string.settings_recent_exports))
                }
                SettingsSection("about",appText(R.string.settings_about)){
                    SettingsItem("version",appText(R.string.settings_version),BuildConfig.VERSION_NAME)
                    SettingsDivider()
                    SettingsItem("privacy",appText(R.string.settings_privacy),action={privacy=true})
                    SettingsDivider()
                    SettingsItem("legacy",appText(R.string.settings_legacy),action=legacy)
                }
            }
        }
    }
    if(languages)AlertDialog(onDismissRequest={languages=false},shape=RoundedCornerShape(28.dp),containerColor=MaterialTheme.colorScheme.surfaceContainerHighest,
        title={Text(appText(R.string.settings_language),style=MaterialTheme.typography.titleMedium)},text={Column{
        for(value in listOf("system","zh","en"))Row(Modifier.fillMaxWidth().heightIn(min=56.dp).selectable(selectedLanguage==value,role=Role.RadioButton,onClick={languages=false;selectedLanguage=value;changeLanguage(value)}).padding(end=8.dp),verticalAlignment=Alignment.CenterVertically){
            RadioButton(selectedLanguage==value,null,Modifier.size(48.dp));Spacer(Modifier.width(8.dp));Text(languageName(value),Modifier.weight(1f),style=MaterialTheme.typography.bodyLarge)
        }
    }},confirmButton={TextButton(onClick={languages=false}){Text(appText(R.string.settings_cancel))}})
}

@Composable internal fun BrandIcon(modifier:Modifier=Modifier){
    Surface(modifier,shape=RoundedCornerShape(14.dp),color=LiquidGreen){Image(painterResource(R.drawable.brand_mark),null,Modifier.fillMaxSize())}
}
internal fun languageName(value:String)=when(value){"zh"->"简体中文";"en"->"English";else->appText(R.string.settings_follow_system)}

@Composable private fun SettingsSection(id:String,title:String,content:@Composable ColumnScope.()->Unit){
    Column(verticalArrangement=Arrangement.spacedBy(8.dp)){
        Text(title,Modifier.fillMaxWidth().padding(start=SettingsGrid.inset,end=SettingsGrid.inset).testTag("settings-section:$id"),style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
        Surface(shape=RoundedCornerShape(24.dp),color=MaterialTheme.colorScheme.surfaceContainerHighest){Column(content=content)}
    }
}
@Composable private fun SettingsDivider(){HorizontalDivider(Modifier.padding(horizontal=SettingsGrid.inset),thickness=.5f.dp,color=MaterialTheme.colorScheme.outlineVariant)}

@Composable internal fun SettingsItem(id:String,title:String,value:String="",action:(()->Unit)?=null,checked:Boolean?=null,changeChecked:((Boolean)->Unit)?=null){
    val interaction=when{checked!=null&&changeChecked!=null->Modifier.toggleable(checked,role=Role.Switch,onValueChange=changeChecked);action!=null->Modifier.clickable(onClick=action);else->Modifier}
    BoxWithConstraints(Modifier.fillMaxWidth().then(interaction).testTag("settings-row:$id").padding(horizontal=SettingsGrid.inset)){
        val valueWidth=if(value.isNotBlank())labelWidth(value,MaterialTheme.typography.bodyMedium)+8.dp else 0.dp
        val accessoryWidth=if(checked!=null)52.dp else SettingsGrid.accessory
        val stacked=value.isNotBlank()&&labelWidth(title,MaterialTheme.typography.bodyLarge)+valueWidth+SettingsGrid.gap*2+accessoryWidth>maxWidth
        Row(Modifier.fillMaxWidth().heightIn(min=SettingsGrid.row).padding(vertical=14.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(SettingsGrid.gap)){
            if(stacked)Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)){
                Text(title,Modifier.fillMaxWidth().testTag("settings-title:$id"),style=MaterialTheme.typography.bodyLarge)
                Text(value,Modifier.fillMaxWidth().testTag("settings-value:$id"),style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }else Row(Modifier.weight(1f),horizontalArrangement=Arrangement.spacedBy(SettingsGrid.gap)){
                Text(title,Modifier.weight(1f).alignByBaseline().testTag("settings-title:$id"),style=MaterialTheme.typography.bodyLarge)
                if(value.isNotBlank())Text(value,Modifier.width(valueWidth).alignByBaseline().testTag("settings-value:$id"),style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant,textAlign=androidx.compose.ui.text.style.TextAlign.End)
            }
            if(checked!=null)Switch(checked,null,Modifier.testTag("settings-accessory:$id"),colors=SwitchDefaults.colors(checkedThumbColor=androidx.compose.ui.graphics.Color.White,checkedTrackColor=MaterialTheme.colorScheme.primary,uncheckedThumbColor=androidx.compose.ui.graphics.Color.White,uncheckedTrackColor=MaterialTheme.colorScheme.onSurface.copy(alpha=.20f),uncheckedBorderColor=androidx.compose.ui.graphics.Color.Transparent))
            else Box(Modifier.size(SettingsGrid.accessory).testTag("settings-accessory:$id"),contentAlignment=Alignment.Center){if(action!=null)Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight,null,Modifier.size(20.dp),tint=MaterialTheme.colorScheme.onSurfaceVariant)}
        }
    }
}
@Composable private fun PrivacySection(title:String,body:String){Column(Modifier.padding(horizontal=SettingsGrid.inset),verticalArrangement=Arrangement.spacedBy(8.dp)){
    Text(title,Modifier.fillMaxWidth(),style=MaterialTheme.typography.titleMedium);Text(body,Modifier.fillMaxWidth(),style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
}}

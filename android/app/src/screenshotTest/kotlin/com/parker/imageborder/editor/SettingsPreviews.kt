package com.parker.imageborder.editor

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest

@PreviewTest @Preview(name="Settings English",widthDp=402,heightDp=874,locale="en")
@Composable fun SettingsEnglishPreview(){AppText.previewContext(LocalContext.current);LiquidEditorTheme(false){SettingsScreen(false,{},{},{},"system",{})}}
@PreviewTest @Preview(name="Settings Chinese dark",widthDp=402,heightDp=874,locale="zh-rCN")
@Composable fun SettingsChinesePreview(){AppText.previewContext(LocalContext.current);LiquidEditorTheme(true){SettingsScreen(false,{},{},{},"zh",{})}}
@PreviewTest @Preview(name="Settings English large",widthDp=360,heightDp=780,fontScale=1.5f,locale="en")
@Preview(name="Settings English narrow 2x",widthDp=320,heightDp=780,fontScale=2f,locale="en")
@Composable fun SettingsLargePreview(){AppText.previewContext(LocalContext.current);LiquidEditorTheme(false){SettingsScreen(true,{},{},{},"en",{})}}

@PreviewTest @Preview(name="Settings Chinese light",widthDp=402,heightDp=874,locale="zh-rCN")
@Composable fun SettingsChineseLightPreview(){AppText.previewContext(LocalContext.current);LiquidEditorTheme(false){SettingsScreen(false,{},{},{},"zh",{})}}
@PreviewTest @Preview(name="Settings Chinese narrow 2x",widthDp=320,heightDp=874,fontScale=2f,locale="zh-rCN")
@Composable fun SettingsChineseLargePreview(){AppText.previewContext(LocalContext.current);LiquidEditorTheme(false){SettingsScreen(false,{},{},{},"system",{})}}

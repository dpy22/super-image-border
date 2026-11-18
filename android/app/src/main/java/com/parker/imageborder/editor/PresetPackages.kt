package com.parker.imageborder.editor

import com.parker.imageborder.R

import android.content.Context
import android.graphics.*
import android.net.Uri
import com.caverock.androidsvg.SVG
import org.json.JSONObject
import java.io.*
import java.util.zip.*

object PresetPackages {
    private const val MAX_ASSET=20*1024*1024
    private fun InputStream.boundedBytes(limit:Int):ByteArray {
        val out=ByteArrayOutputStream();val buffer=ByteArray(8192)
        while(out.size()<limit){val count=read(buffer,0,minOf(buffer.size,limit-out.size()));if(count<0)break;out.write(buffer,0,count)}
        return out.toByteArray()
    }
    fun importOverlay(context:Context,uri:Uri):String{
        val bytes=context.contentResolver.openInputStream(uri).use{it?.boundedBytes(MAX_ASSET+1)}?:error(appText(R.string.ui_cannot_read_logo_61255f))
        require(bytes.size<=MAX_ASSET){appText(R.string.ui_logo_must_be_20_mb_or_less_3a7a3d)}
        val dir=File(context.filesDir,"overlays").apply{mkdirs()};val out=File(dir,uid()+".png")
        val isSvg=bytes.take(500).toByteArray().toString(Charsets.UTF_8).let{it.contains("<svg")||it.contains("<?xml")}
        val bitmap=if(isSvg){
            val text=bytes.toString(Charsets.UTF_8)
            require(!Regex("<!DOCTYPE|<!ENTITY|<script|<foreignObject|(?:href|src)\\s*=\\s*['\"](?!#)|url\\s*\\(",RegexOption.IGNORE_CASE).containsMatchIn(text)){appText(R.string.ui_svg_cannot_contain_scripts_external_references_or__adb444)}
            val svg=SVG.getFromString(text);val view=svg.documentViewBox
            val w=(view?.width()?:svg.documentWidth).takeIf{it.isFinite()&&it>0}?:512f
            val h=(view?.height()?:svg.documentHeight).takeIf{it.isFinite()&&it>0}?:512f
            val scale=2048f/maxOf(w,h);val result=Bitmap.createBitmap(maxOf(1,(w*scale).toInt()),maxOf(1,(h*scale).toInt()),Bitmap.Config.ARGB_8888)
            svg.setDocumentWidth("100%");svg.setDocumentHeight("100%");svg.renderToCanvas(Canvas(result));result
        }else{
            val options=BitmapFactory.Options().apply{inJustDecodeBounds=true};BitmapFactory.decodeByteArray(bytes,0,bytes.size,options)
            require(options.outWidth>0&&options.outHeight>0){appText(R.string.ui_choose_png_webp_or_a_supported_svg_dedf15)}
            var sample=1;while(maxOf(options.outWidth,options.outHeight)/sample>2048)sample*=2
            BitmapFactory.decodeByteArray(bytes,0,bytes.size,BitmapFactory.Options().apply{inSampleSize=sample})?:error(appText(R.string.ui_could_not_decode_logo_81345f))
        }
        try{out.outputStream().use{check(bitmap.compress(Bitmap.CompressFormat.PNG,100,it))}}finally{bitmap.recycle()}
        return Uri.fromFile(out).toString()
    }
    fun importFont(context:Context,uri:Uri):String{
        val file=File(File(context.filesDir,"fonts").apply{mkdirs()},uid()+".font")
        try{
            context.contentResolver.openInputStream(uri).use{input->requireNotNull(input);val b=input.boundedBytes(MAX_ASSET+1);require(b.size<=MAX_ASSET){appText(R.string.ui_font_must_be_20_mb_or_less_dc0c01)};file.writeBytes(b)}
            Typeface.createFromFile(file);return file.path
        }catch(e:Exception){file.delete();throw e}
    }
    fun export(context:Context,document:EditDocument,name:String,out:OutputStream){
        val assets=mutableMapOf<String,String>();val files=mutableMapOf<String,File>()
        fun add(value:String):String{
            if(value.isBlank()||(!value.startsWith("file:")&&!value.startsWith("/")))return value
            return assets.getOrPut(value){
                val file=if(value.startsWith("file:"))File(Uri.parse(value).path!!)else File(value)
                require(file.canonicalPath.startsWith(context.filesDir.canonicalPath+File.separator)){appText(R.string.ui_only_assets_imported_by_this_app_can_be_packaged_929088)}
                require(file.length()<=MAX_ASSET){appText(R.string.ui_asset_is_too_large_6d8660)};val entry="assets/${files.size}.${if(value.startsWith("file:"))"png"else "font"}";files[entry]=file;"asset:$entry"
            }
        }
        val base=document.preset()
        val preset=base.copy(layers=document.layers.map{it.copy(uri=add(it.uri),font=add(it.font))},textStyle=base.textStyle.copy(font=add(document.textStyle.font)))
        ZipOutputStream(out).use{zip->
            zip.putNextEntry(ZipEntry("preset.json"));zip.write(JSONObject().put("version",1).put("name",name.take(100)).put("document",preset.json()).toString().toByteArray());zip.closeEntry()
            for((path,file)in files){zip.putNextEntry(ZipEntry(path));file.inputStream().use{it.copyTo(zip)};zip.closeEntry()}
        }
    }
    fun import(context:Context,uri:Uri):Pair<String,EditDocument>{
        val temp=File(context.cacheDir,"preset-${uid()}").apply{mkdirs()};val imported=mutableListOf<File>()
        try{
            var total=0L;var count=0
            context.contentResolver.openInputStream(uri).use{input->requireNotNull(input);ZipInputStream(input).use{zip->
                while(true){val entry=zip.nextEntry?:break;require(++count<=64){appText(R.string.ui_too_many_preset_assets_82af9a)}
                    require(!entry.isDirectory&&entry.name.length<160&&!entry.name.contains("..")&&!entry.name.startsWith("/")){appText(R.string.ui_invalid_preset_path_a596c2)}
                    require(entry.name=="preset.json"||Regex("assets/\\d+\\.(png|font)").matches(entry.name)){appText(R.string.ui_unsupported_file_in_preset_b4bb05)}
                    val file=File(temp,entry.name);file.parentFile?.mkdirs()
                    file.outputStream().use{out->val buf=ByteArray(8192);var size=0
                        while(true){val n=zip.read(buf);if(n<0)break;size+=n;total+=n;require(size<=MAX_ASSET&&total<=100L*1024*1024){appText(R.string.ui_preset_package_is_too_large_8c9895)};out.write(buf,0,n)}}
                    zip.closeEntry()
                }
            }}
            val manifest=JSONObject(File(temp,"preset.json").readText());require(manifest.getInt("version")==1){appText(R.string.ui_this_preset_version_is_not_supported_a443a3)}
            val doc=EditDocument.read(manifest.getJSONObject("document")).preset()
            fun resolve(value:String,font:Boolean):String{
                if(!value.startsWith("asset:")){
                    require(if(font)value in listOf("sans-serif","serif","monospace")else value.isBlank()){appText(R.string.ui_presets_cannot_reference_external_assets_a78667)};return value
                }
                val name=value.removePrefix("asset:");require(Regex("assets/\\d+\\.(png|font)").matches(name)){appText(R.string.ui_invalid_asset_reference_eec612)}
                val file=File(temp,name);require(file.isFile){appText(R.string.ui_missing_preset_asset_f685e2)}
                val output=File(File(context.filesDir,if(font)"fonts"else "overlays").apply{mkdirs()},uid()+if(font)".font"else ".png")
                if(font)Typeface.createFromFile(file)else{
                    val o=BitmapFactory.Options().apply{inJustDecodeBounds=true};BitmapFactory.decodeFile(file.path,o);require(o.outWidth in 1..4096&&o.outHeight in 1..4096){appText(R.string.ui_invalid_logo_dimensions_c5883b)}
                }
                file.copyTo(output);imported.add(output);return if(font)output.path else Uri.fromFile(output).toString()
            }
            val result=doc.copy(layers=doc.layers.map{it.copy(uri=resolve(it.uri,false),font=resolve(it.font,true))},textStyle=doc.textStyle.copy(font=resolve(doc.textStyle.font,true)))
            return manifest.optString("name",appText(R.string.ui_imported_preset_4d8029)) to result
        }catch(e:Exception){imported.forEach{it.delete()};throw e}finally{temp.deleteRecursively()}
    }
}

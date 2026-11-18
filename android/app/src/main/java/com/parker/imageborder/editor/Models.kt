package com.parker.imageborder.editor

import com.parker.imageborder.R

import android.graphics.Color
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

fun uid(): String = UUID.randomUUID().toString()
fun JSONObject.float(key: String, default: Float) = optDouble(key, default.toDouble()).toFloat().let { if (it.isFinite()) it else default }
fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }
fun JSONObject.strings(): Map<String, String> = keys().asSequence().associateWith { optString(it) }
fun Map<String, String>.json() = JSONObject(this)

data class PhotoAsset(
    val id: String = uid(), val uri: String, val name: String, val width: Int, val height: Int,
    val orientation: Int = 1, val colorSpace: String = "sRGB", val hdr: Boolean = false,
    val exif: Map<String, String> = emptyMap(),val metadataVersion:Int=2
) {
    fun json() = JSONObject().put("id", id).put("uri", uri).put("name", name).put("width", width).put("height", height)
        .put("orientation", orientation).put("colorSpace", colorSpace).put("hdr", hdr).put("exif", exif.json()).put("metadataVersion",metadataVersion)
    companion object { fun read(j: JSONObject) = PhotoAsset(j.getString("id"), j.getString("uri"), j.optString("name", "照片"),
        j.getInt("width"), j.getInt("height"), j.optInt("orientation", 1), j.optString("colorSpace", "sRGB"),
        j.optBoolean("hdr"), j.optJSONObject("exif")?.strings() ?: emptyMap(),j.optInt("metadataVersion",0)) }
}

data class Adjustments(
    val exposure: Float = 0f, val contrast: Float = 0f, val saturation: Float = 0f, val warmth: Float = 0f,
    val tint: Float = 0f, val highlights: Float = 0f, val shadows: Float = 0f, val sharpness: Float = 0f,
    val vignette: Float = 0f, val filter: String = "自然", val intensity: Float = 1f
) {
    val isNeutral get() = exposure==0f&&contrast==0f&&saturation==0f&&warmth==0f&&tint==0f&&highlights==0f&&shadows==0f&&sharpness==0f&&vignette==0f&&(filter=="自然"||intensity==0f)
    fun json() = JSONObject().put("exposure", exposure).put("contrast", contrast).put("saturation", saturation)
        .put("warmth", warmth).put("tint", tint).put("highlights", highlights).put("shadows", shadows)
        .put("sharpness", sharpness).put("vignette", vignette).put("filter", filter).put("intensity", intensity)
    companion object { val legacyFilters = listOf("自然", "柔和", "暖日", "冷调", "褪色胶片", "鲜明", "经典黑白", "高反差黑白")
        val filters get()=legacyFilters+FilmLooks.presets.map{it.name}
        fun read(j: JSONObject) = Adjustments(j.float("exposure",0f).coerceIn(-2f,2f),j.float("contrast",0f).coerceIn(-1f,1f),
            j.float("saturation",0f).coerceIn(-1f,1f),j.float("warmth",0f).coerceIn(-1f,1f),j.float("tint",0f).coerceIn(-1f,1f),
            j.float("highlights",0f).coerceIn(-1f,1f),j.float("shadows",0f).coerceIn(-1f,1f),j.float("sharpness",0f).coerceIn(0f,1f),
            j.float("vignette",0f).coerceIn(0f,1f),j.optString("filter","自然").takeIf { it in filters } ?: "自然",j.float("intensity",1f).coerceIn(0f,1f))
    }
}

data class FrameStyle(
    val left: Float = .05f, val top: Float = .05f, val right: Float = .05f, val bottom: Float = .05f,
    val footer: Float = 0f, val radius: Float = 0f, val stroke: Float = 0f, val shadow: Float = 0f,
    val color: Int = Color.WHITE, val autoColor: Boolean = false, val blur: Float = .45f,
    val glassOpacity: Float = .35f, val tint: Float = .2f, val lift: Float = .025f, val innerStroke:Float=0f, val metadataPadding:Float=.024f
) {
    fun json() = JSONObject().put("left",left).put("top",top).put("right",right).put("bottom",bottom).put("footer",footer)
        .put("radius",radius).put("stroke",stroke).put("shadow",shadow).put("color",color).put("autoColor",autoColor)
        .put("blur",blur).put("glassOpacity",glassOpacity).put("tint",tint).put("lift",lift).put("innerStroke",innerStroke).put("metadataPadding",metadataPadding)
    companion object { fun read(j: JSONObject) = FrameStyle(j.float("left",.055f).coerceIn(0f,.5f),j.float("top",.055f).coerceIn(0f,.5f),
        j.float("right",.055f).coerceIn(0f,.5f),j.float("bottom",.055f).coerceIn(0f,.5f),j.float("footer",0f).coerceIn(0f,.7f),
        j.float("radius",0f).coerceIn(0f,.5f),j.float("stroke",0f).coerceIn(0f,.02f),j.float("shadow",0f).coerceIn(0f,.15f),
        j.optInt("color",Color.WHITE),j.optBoolean("autoColor",false),j.float("blur",.45f).coerceIn(0f,1f),
        j.float("glassOpacity",.35f).coerceIn(0f,1f),j.float("tint",.2f).coerceIn(0f,1f),j.float("lift",.025f).coerceIn(0f,.1f),j.float("innerStroke",0f).coerceIn(0f,.02f),j.float("metadataPadding",.024f).coerceIn(0f,.15f)) }
}

data class PhotoTransform(
    val x: Float = .5f, val y: Float = .5f, val zoom: Float = 1f, val rotation: Float = 0f, val mirror: Boolean = false,
    val cover: Boolean = false, val cropLeft: Float = 0f, val cropTop: Float = 0f, val cropRight: Float = 1f, val cropBottom: Float = 1f, val locked:Boolean=false
) {
    fun json() = JSONObject().put("x",x).put("y",y).put("zoom",zoom).put("rotation",rotation).put("mirror",mirror).put("cover",cover)
        .put("cropLeft",cropLeft).put("cropTop",cropTop).put("cropRight",cropRight).put("cropBottom",cropBottom).put("locked",locked)
    companion object { fun read(j: JSONObject) = PhotoTransform(j.float("x",.5f).coerceIn(0f,1f),j.float("y",.5f).coerceIn(0f,1f),
        j.float("zoom",1f).coerceIn(.2f,4f),j.float("rotation",0f),j.optBoolean("mirror"),j.optBoolean("cover"),
        j.float("cropLeft",0f).coerceIn(0f,.9f),j.float("cropTop",0f).coerceIn(0f,.9f),j.float("cropRight",1f).coerceIn(.1f,1f),j.float("cropBottom",1f).coerceIn(.1f,1f),j.optBoolean("locked")) }
}

data class WatermarkLayer(
    val id: String = uid(), val text: String = appText(R.string.ui_my_work_d16228), val uri: String = "", val font: String = "sans-serif",
    val x: Float = 1f, val y: Float = 1f, val size: Float = .028f, val opacity: Float = .85f,
    val angle: Float = 0f, val scale: Float = 1f, val color: Int = Color.WHITE, val bold: Boolean = false,
    val shadow: Boolean = true, val outline: Boolean = false, val background: Boolean = false,
    val tiled: Boolean = false, val spacing: Float = .18f, val locked: Boolean = false, val alignment:String="center",val anchor:String="canvas"
) {
    fun json() = JSONObject().put("id",id).put("text",text).put("uri",uri).put("font",font).put("x",x).put("y",y)
        .put("size",size).put("opacity",opacity).put("angle",angle).put("scale",scale).put("color",color).put("bold",bold)
        .put("shadow",shadow).put("outline",outline).put("background",background).put("tiled",tiled).put("spacing",spacing).put("locked",locked).put("alignment",alignment).put("anchor",anchor)
    companion object { fun read(j: JSONObject) = WatermarkLayer(j.optString("id",uid()),j.optString("text").take(2000),j.optString("uri"),
        j.optString("font","sans-serif"),j.float("x",.88f).coerceIn(0f,1f),j.float("y",.9f).coerceIn(0f,1f),j.float("size",.035f).coerceIn(.001f,.5f),
        j.float("opacity",.85f).coerceIn(0f,1f),j.float("angle",0f),j.float("scale",1f).coerceIn(.1f,5f),j.optInt("color",Color.WHITE),
        j.optBoolean("bold"),j.optBoolean("shadow",true),j.optBoolean("outline"),j.optBoolean("background"),j.optBoolean("tiled"),
        j.float("spacing",.18f).coerceIn(.03f,1f),j.optBoolean("locked"),j.optString("alignment","center").takeIf{it in listOf("left","center","right")}?:"center",j.optString("anchor","canvas").takeIf{it in listOf("photo","canvas")}?:"canvas") }
}

data class TextBlockStyle(
    val font:String="sans-serif",val size:Float=.032f,val lineSpacing:Float=1.15f,val alignment:String="left",
    val color:Int=Color.WHITE,val autoColor:Boolean=true,val background:Int?=null,val bold:Boolean=false,
    val padding:Float=.018f,val gap:Float=.025f,val widthMode:String="photo",val characters:Int=24,val width:Int=1080,val paragraphSpacing:Float=.5f,val sidePadding:Float?=0f,val widthRatio:Float?=.9f,val sizeBasis:String="width"
){
    val horizontalPadding get()=sidePadding?:if(widthMode=="chars")0f else padding
    fun json()=JSONObject().put("font",font).put("size",size).put("lineSpacing",lineSpacing).put("alignment",alignment)
        .put("color",color).put("autoColor",autoColor).put("background",background?:JSONObject.NULL).put("bold",bold)
        .put("padding",padding).put("gap",gap).put("widthMode",widthMode).put("characters",characters).put("width",width).put("paragraphSpacing",paragraphSpacing).put("sidePadding",sidePadding?:JSONObject.NULL).put("sizeBasis",sizeBasis).also{if(widthRatio!=null)it.put("widthRatio",widthRatio)}
    companion object{fun read(j:JSONObject)=TextBlockStyle(j.optString("font","sans-serif"),j.float("size",.035f).coerceIn(.008f,.15f),j.float("lineSpacing",1.15f).coerceIn(1f,3f),
        j.optString("alignment","left").takeIf{it in listOf("left","center","right","justify","distribute")}?:"left",j.optInt("color",Color.WHITE),j.optBoolean("autoColor",true),
        if(j.has("background")&&!j.isNull("background"))j.optInt("background")else null,j.optBoolean("bold"),j.float("padding",.02f).coerceIn(0f,.2f),j.float("gap",.035f).coerceIn(0f,.2f),
        j.optString("widthMode","photo").takeIf{it in listOf("photo","chars","fixed")}?:"photo",j.optInt("characters",24).coerceIn(5,120),j.optInt("width",1080).coerceIn(120,12000),j.float("paragraphSpacing",.5f).coerceIn(0f,3f),if(j.has("sidePadding")&&!j.isNull("sidePadding"))j.float("sidePadding",0f).coerceIn(0f,.2f)else null,if(j.has("widthRatio")&&!j.isNull("widthRatio"))j.float("widthRatio",.9f).coerceIn(.05f,1f)else null,j.optString("sizeBasis","short"))}
}

data class ExportOptions(val mode: String = "share", val format: String = "jpeg", val quality: Int = 95,
    val maxEdge: Int = 2560, val profile: String = "srgb", val keepExif: Boolean = true, val keepGps: Boolean = false,
    val split: Int = 1, val gridSplit: Boolean = false) {
    fun json() = JSONObject().put("mode",mode).put("format",format).put("quality",quality).put("maxEdge",maxEdge)
        .put("profile",profile).put("keepExif",keepExif).put("keepGps",keepGps).put("split",split).put("gridSplit",gridSplit)
    fun forMode(next:String)=when(next){"share"->copy(mode=next,format="jpeg",quality=95,maxEdge=2560,profile="srgb");"original"->copy(mode=next,profile="source");else->copy(mode="custom")}
    companion object {
        val shareEdges=listOf(1280,1920,2560,3840)
        fun read(j: JSONObject) = ExportOptions(j.optString("mode","share"),j.optString("format","jpeg"),j.optInt("quality",95).coerceIn(80,100),
        j.optInt("maxEdge",2560).coerceIn(64,60000),j.optString("profile","srgb"),j.optBoolean("keepExif",true),j.optBoolean("keepGps"),j.optInt("split",1).coerceIn(1,9),j.optBoolean("gridSplit")).let{
            if(it.mode=="share"&&(it.format!="jpeg"||it.quality!=95||it.profile!="srgb"||it.maxEdge !in shareEdges))it.copy(mode="custom")else it
        }
    }
}

data class PhotoOverride(val transform: PhotoTransform = PhotoTransform(), val values: Map<String,String> = emptyMap(), val hidden: Set<String> = emptySet(),val autoFields:Set<String> = emptySet(),val shown:Set<String> = emptySet()) {
    fun json() = JSONObject().put("transform",transform.json()).put("values",values.json()).put("hidden",JSONArray(hidden.toList())).put("autoFields",JSONArray(autoFields.toList())).put("shown",JSONArray(shown.toList()))
    companion object { fun read(j:JSONObject)=PhotoOverride(PhotoTransform.read(j.optJSONObject("transform")?:JSONObject()),j.optJSONObject("values")?.strings()?:emptyMap(),
        j.optJSONArray("hidden")?.let { a->(0 until a.length()).map{a.getString(it)}.toSet() }?:emptySet(),j.optJSONArray("autoFields")?.let{a->(0 until a.length()).map{a.getString(it)}.toSet()}?:emptySet(),j.optJSONArray("shown")?.let{a->(0 until a.length()).map{a.getString(it)}.toSet()}?:emptySet()) }
}

/** Camera label presentation is independent of the selected decorative template. */
data class MetadataStyle(
    val position:String="template",val alignment:String="template",val arrangement:String="template",
    val gap:Float?=null,val columnWidth:Float=.42f
){
    fun json()=JSONObject().put("position",position).put("alignment",alignment).put("arrangement",arrangement)
        .put("gap",gap?:JSONObject.NULL).put("columnWidth",columnWidth)
    fun position(t:TemplateDefinition,aspect:Float=1f)=if(position!="template")position else if(t.id=="side-plate"&&aspect<=2.4f)"right"else"bottom"
    fun arrangement(t:TemplateDefinition)=if(arrangement!="template")arrangement else if(t.id=="split-plate")"split"else"stacked"
    fun alignment(t:TemplateDefinition)=if(alignment!="template")alignment else if(t.id=="center-plate")"center"else"left"
    fun gap(frame:FrameStyle,position:String)=gap?:(when(position){"top"->frame.top;"bottom"->frame.bottom;"left"->frame.left;else->frame.right}).coerceAtMost(.08f)
    companion object{
        fun read(j:JSONObject)=MetadataStyle(
            j.optString("position","template").takeIf{it in listOf("template","top","bottom","left","right")}?:"template",
            j.optString("alignment","template").takeIf{it in listOf("template","left","center","right")}?:"template",
            j.optString("arrangement","template").takeIf{it in listOf("template","stacked","split")}?:"template",
            if(j.has("gap")&&!j.isNull("gap"))j.float("gap",.05f).coerceIn(0f,.3f)else null,
            j.float("columnWidth",.42f).coerceIn(.25f,1.2f))
    }
}

data class EditDocument(
    val id: String = uid(), val assets: List<PhotoAsset> = emptyList(), val active: Int = 0, val template: String = "white",
    val ratio: String = "original", val customRatio: Float = .8f, val frame: FrameStyle = FrameStyle(),
    val transform: PhotoTransform = PhotoTransform(), val overrides: Map<String,PhotoOverride> = emptyMap(),
    val layers: List<WatermarkLayer> = emptyList(), val adjustments: Adjustments = Adjustments(),
    val metadata: Map<String,String> = emptyMap(), val hiddenFields: Set<String> = emptySet(),
    val title: String = "", val caption: String = "", val textOnly: Boolean = false,
    val export: ExportOptions = ExportOptions(), val showMetadata:Boolean=false,
    val textStyle:TextBlockStyle=TextBlockStyle(),val metadataCustomized:Boolean=false,val collageAssets:List<String> = emptyList(),val metadataStyle:MetadataStyle=MetadataStyle()
) {
    val current get() = assets.getOrNull(active)
    val currentTransform get() = current?.let { overrides[it.id]?.transform } ?: transform
    fun fields(asset:PhotoAsset?=current):Map<String,String> = (asset?.exif?:emptyMap()) + metadata.filterKeys{it !in (asset?.let{overrides[it.id]?.autoFields}?:emptySet())} + (asset?.let{overrides[it.id]?.values}?:emptyMap())
    fun hidden(asset:PhotoAsset?=current)=(hiddenFields-(asset?.let{overrides[it.id]?.shown}?:emptySet()))+(asset?.let{overrides[it.id]?.hidden}?:emptySet())
    fun hasMetadata(asset:PhotoAsset?=current)=Assets.caption(this,asset).let{it.first.isNotBlank()||it.second.isNotBlank()}
    fun overrideFor(asset:PhotoAsset)=overrides[asset.id]?:PhotoOverride(transform=transform)
    fun withMetadataField(field:String,value:String?,individual:Boolean=true,visible:Boolean?=null):EditDocument{
        val asset=current
        if(individual&&asset!=null){val old=overrideFor(asset)
            val next=if(visible!=null)old.copy(hidden=if(visible)old.hidden-field else old.hidden+field,shown=if(visible)old.shown+field else old.shown-field)
                else old.copy(values=if(value==null)old.values-field else old.values+(field to value.take(512)),autoFields=if(value==null)old.autoFields+field else old.autoFields-field)
            return copy(overrides=overrides+(asset.id to next))
        }
        val updated=overrides.mapValues{(_,old)->if(visible!=null)old.copy(hidden=old.hidden-field,shown=old.shown-field)else old.copy(values=old.values-field,autoFields=old.autoFields-field)}
        return if(visible!=null)copy(hiddenFields=if(visible)hiddenFields-field else hiddenFields+field,overrides=updated)
            else copy(metadata=if(value==null)metadata-field else metadata+(field to value.take(512)),overrides=updated)
    }
    fun sceneAssets(t:TemplateDefinition):List<PhotoAsset>{
        if(textOnly)return emptyList()
        if(t.family!=7)return listOfNotNull(current)
        if(t.variant==3)return assets
        val capacity=if(t.variant==0)2 else if(t.variant==1)3 else 4
        val chosen=collageAssets.mapNotNull{id->assets.firstOrNull{it.id==id}}.distinctBy{it.id}.take(capacity)
        val result=(chosen+assets.filterNot{a->chosen.any{it.id==a.id}}).take(capacity).toMutableList()
        if(chosen.isEmpty()&&current!=null&&result.none{it.id==current!!.id}&&result.isNotEmpty())result[0]=current!!
        return result
    }
    fun clean()=copy(active=if(assets.isEmpty())0 else active.coerceIn(assets.indices),layers=layers.filter{it.uri.isNotBlank()||it.text.isNotBlank()}.take(30),collageAssets=collageAssets.filter{id->assets.any{it.id==id}}.distinct())
    fun json() = JSONObject().put("version",1).put("id",id).put("assets",JSONArray(assets.map{it.json()})).put("active",active)
        .put("template",template).put("ratio",ratio).put("customRatio",customRatio).put("frame",frame.json()).put("transform",transform.json())
        .put("overrides",JSONObject().also{j->overrides.forEach{(k,v)->j.put(k,v.json())}}).put("layers",JSONArray(layers.map{it.json()}))
        .put("adjustments",adjustments.json()).put("metadata",metadata.json()).put("hiddenFields",JSONArray(hiddenFields.toList()))
        .put("title",title).put("caption",caption).put("textOnly",textOnly).put("export",export.json()).put("showMetadata",showMetadata)
        .put("textStyle",textStyle.json()).put("metadataCustomized",metadataCustomized).put("collageAssets",JSONArray(collageAssets)).put("metadataStyle",metadataStyle.json())
    fun relativeTypography():EditDocument {
        if(textStyle.widthMode!="fixed"||textStyle.widthRatio!=null)return this
        if(textOnly)return this
        val multi=template in listOf("diptych","triptych","grid","long")&&assets.size>1
        val variant=when(template){"triptych"->1;"grid"->2;"long"->3;else->0}
        val asset=if(multi)sceneAssets(TemplateDefinition(template,"",7,variant,"")).firstOrNull()else current
        val reference=if(asset==null)1200f else {
            val tr=if(multi)overrides[asset.id]?.transform?:PhotoTransform()else currentTransform
            val a=asset.width*(tr.cropRight-tr.cropLeft);val b=asset.height*(tr.cropBottom-tr.cropTop)
            val angle=Math.toRadians(tr.rotation.toDouble())
            val rw=(kotlin.math.abs(kotlin.math.cos(angle))*a+kotlin.math.abs(kotlin.math.sin(angle))*b).toFloat()
            val rh=(kotlin.math.abs(kotlin.math.sin(angle))*a+kotlin.math.abs(kotlin.math.cos(angle))*b).toFloat()
            val cols=if(multi)when(variant){0,2->2;1->3;else->1}else 1
            if(!multi)rw else asset.width*cols+kotlin.math.min(rw,rh)*.035f*(cols-1)
        }
        return copy(textStyle=textStyle.copy(widthRatio=(textStyle.width/reference.coerceAtLeast(1f)).coerceIn(.05f,1f)))
    }
    fun preset() = relativeTypography().copy(id=uid(),assets=emptyList(),active=0,overrides=emptyMap(),collageAssets=emptyList())
    companion object {
        fun read(j:JSONObject):EditDocument {
            require(j.optInt("version",1)==1){appText(R.string.ui_this_project_version_is_not_supported_c21cd6)}
            return EditDocument(j.optString("id",uid()),j.optJSONArray("assets")?.objects()?.take(100)?.map{PhotoAsset.read(it)}?:emptyList(),
                j.optInt("active",0).coerceAtLeast(0),j.optString("template","white"),j.optString("ratio","original"),j.float("customRatio",.8f).coerceIn(.1f,10f),
                FrameStyle.read(j.optJSONObject("frame")?:JSONObject()),PhotoTransform.read(j.optJSONObject("transform")?:JSONObject()),
                j.optJSONObject("overrides")?.let{a->a.keys().asSequence().associateWith{PhotoOverride.read(a.getJSONObject(it))}}?:emptyMap(),
                j.optJSONArray("layers")?.objects()?.take(30)?.map{WatermarkLayer.read(it)}?:emptyList(),Adjustments.read(j.optJSONObject("adjustments")?:JSONObject()),
                j.optJSONObject("metadata")?.strings()?:emptyMap(),j.optJSONArray("hiddenFields")?.let{a->(0 until a.length()).map{a.getString(it)}.toSet()}?:emptySet(),
                j.optString("title").take(2000),j.optString("caption").take(20000),j.optBoolean("textOnly"),ExportOptions.read(j.optJSONObject("export")?:JSONObject()),
                j.optBoolean("showMetadata",j.optString("template") in listOf("plate","split-plate","side-plate","center-plate","diptych","triptych","grid","long")),
                TextBlockStyle.read(j.optJSONObject("textStyle")?:JSONObject()),j.optBoolean("metadataCustomized"),j.optJSONArray("collageAssets")?.let{a->(0 until a.length()).map{a.getString(it)}}?:emptyList(),
                MetadataStyle.read(j.optJSONObject("metadataStyle")?:JSONObject()).let{style->
                    if(j.has("metadataStyle"))style else {
                        val f=FrameStyle.read(j.optJSONObject("frame")?:JSONObject())
                        style.copy(gap=if(kotlin.math.abs(f.metadataPadding-.024f)>.00001f)f.metadataPadding else null,
                            columnWidth=if(j.optString("template")=="side-plate"&&f.right>=.32f)(f.right-.05f).coerceIn(.25f,1.2f)else .42f)
                    }
                }).let{doc->if(!j.has("metadataStyle")&&doc.template=="side-plate"&&doc.frame.right>=.32f)doc.copy(frame=doc.frame.copy(right=.05f))else doc}.clean().relativeTypography()
        }
    }
}

data class TemplateDefinition(val id:String,val name:String,val family:Int,val variant:Int,val category:String) {
    fun apply(doc:EditDocument):EditDocument {
        val base=FrameStyle(autoColor=family in listOf(2,3))
        val frame=when(family){
            0->base.copy(color=when(variant){1->Color.rgb(22,23,23);2->Color.rgb(244,239,228);else->Color.WHITE},stroke=if(variant==3).002f else 0f)
            1->base
            2->base.copy(footer=if(variant==2).12f else 0f)
            3->base.copy(left=.09f,top=.09f,right=.09f,bottom=.09f,radius=.015f,shadow=.025f)
            4->base.copy(color=Color.rgb(246,242,231),bottom=if(variant==0).20f else .08f,shadow=if(variant==3).018f else .008f)
            5->base.copy(color=Color.rgb(20,20,20),top=if(variant==1).18f else .055f,bottom=if(variant==1).18f else .055f)
            else->base
        }
        return doc.copy(template=id,frame=if(doc.textOnly)frame.copy(autoColor=false)else frame,textOnly=doc.textOnly,
            showMetadata=if(doc.metadataCustomized)doc.showMetadata else doc.showMetadata||family==1||family==7,
            collageAssets=if(id==doc.template)doc.collageAssets else emptyList(),metadataStyle=MetadataStyle())
    }
}

class EditHistory {
    private val undo=ArrayDeque<EditDocument>();private val redo=ArrayDeque<EditDocument>()
    val canUndo get()=undo.isNotEmpty();val canRedo get()=redo.isNotEmpty()
    fun record(old:EditDocument){undo.addLast(old);while(undo.size>100)undo.removeFirst();redo.clear()}
    fun undo(current:EditDocument):EditDocument?=if(undo.isEmpty())null else undo.removeLast().also{redo.addLast(current)}
    fun redo(current:EditDocument):EditDocument?=if(redo.isEmpty())null else redo.removeLast().also{undo.addLast(current)}
}

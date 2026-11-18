package com.parker.imageborder.editor

import com.parker.imageborder.R

import android.content.Context
import android.graphics.*
import android.net.Uri
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import java.io.Closeable
import java.io.File
import kotlin.math.*

data class PhotoNode(val asset:PhotoAsset,val rect:RectF,val transform:PhotoTransform,val label:RectF?=null)
data class SceneLayout(val width:Float,val height:Float,val photos:List<PhotoNode>,val footer:RectF,val template:TemplateDefinition,val body:RectF?=null,val metadata:RectF?=null,val unit:Float=min(width,height)) {
    fun output(options:ExportOptions):Pair<Int,Int>{
        val scale=if(options.mode=="original")1.0 else min(1.0,options.maxEdge/max(width,height).toDouble())
        val w=max(1,(width*scale).roundToInt());val h=max(1,(height*scale).roundToInt())
        return if(options.format=="hdr")((w+1)/2*2) to ((h+1)/2*2) else w to h
    }
}

data class PreviewFrame(val bitmap:Bitmap,val layout:SceneLayout,val palette:List<Int>)

/** Physical viewport pixels, bounded independently of the full document size. */
internal fun previewViewportSize(width:Int,height:Int):Pair<Int,Int>{
    require(width>0&&height>0)
    val reduction=min(1.0,min(4096.0/max(width,height),sqrt(4_194_304.0/(width.toDouble()*height))))
    return max(1,(width*reduction).roundToInt()) to max(1,(height*reduction).roundToInt())
}

interface PhotoPixels:Closeable {
    fun draw(canvas:Canvas,asset:PhotoAsset,adjustments:Adjustments)
}

class RegionPixels(private val context:Context,private val target:ColorSpace):PhotoPixels {
    private val readers=mutableMapOf<String,BitmapRegionDecoder>()
    override fun draw(canvas:Canvas,asset:PhotoAsset,adjustments:Adjustments){
        val decoder=readers.getOrPut(asset.id){BitmapRegionDecoder.newInstance(Assets.cacheFile(context,asset.uri).path)}
        val clip=canvas.clipBounds
        val region=Rect(max(0,clip.left-4),max(0,clip.top-4),min(decoder.width,clip.right+4),min(decoder.height,clip.bottom+4))
        if(region.isEmpty)return
        val matrix=FloatArray(9);canvas.matrix.getValues(matrix)
        val scale=max(hypot(matrix[0],matrix[3]),hypot(matrix[1],matrix[4])).coerceAtLeast(.00001f)
        var sample=1;while(sample*2<=1/scale&&sample<512)sample*=2
        val raw=decoder.decodeRegion(region,BitmapFactory.Options().apply{inSampleSize=sample;inPreferredConfig=Bitmap.Config.ARGB_8888;inPreferredColorSpace=target})?:error(appText(R.string.ui_cannot_read_photo_region_33a01d))
        val edited=ImageEffects.adjust(raw,adjustments)
        canvas.drawBitmap(edited,null,RectF(region),Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
        if(edited!==raw)edited.recycle();raw.recycle()
    }
    override fun close(){readers.values.forEach{it.recycle()};readers.clear()}
}

class BitmapPixels(private val images:Map<String,Bitmap>,private val adjusted:Boolean=false):PhotoPixels {
    override fun draw(canvas:Canvas,asset:PhotoAsset,adjustments:Adjustments){
        val image=images[asset.id]?:return
        val (w,h)=if(asset.orientation in 5..8)asset.height to asset.width else asset.width to asset.height
        canvas.drawBitmap(image,null,RectF(0f,0f,w.toFloat(),h.toFloat()),Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
    }
    override fun close(){}
}

class FrameRenderer(private val context:Context,val templates:List<TemplateDefinition>) {
    private val backgrounds=object:android.util.LruCache<String,Bitmap>(16*1024){override fun sizeOf(key:String,value:Bitmap)=value.allocationByteCount/1024}
    private val logos=object:android.util.LruCache<String,Bitmap>(8*1024){override fun sizeOf(key:String,value:Bitmap)=value.allocationByteCount/1024}
    private val hdrSources=object:android.util.LruCache<String,Bitmap>(48*1024){override fun sizeOf(key:String,value:Bitmap)=value.allocationByteCount/1024}
    private val previewSources=object:android.util.LruCache<String,Bitmap>(24*1024){override fun sizeOf(key:String,value:Bitmap)=value.allocationByteCount/1024}
    private val previewEdits=object:android.util.LruCache<String,Bitmap>(24*1024){override fun sizeOf(key:String,value:Bitmap)=value.allocationByteCount/1024}
    @androidx.annotation.RequiresApi(34)
    fun hdrPreviewSource(asset:PhotoAsset,edge:Int):Bitmap {
        val key="${asset.uri}:$edge"
        return hdrSources[key]?:HdrSourceDecoder.decode(context,asset,edge).also{hdrSources.put(key,it)}
    }
    fun template(id:String)=templates.firstOrNull{it.id==id}?:templates.first()
    fun layout(doc:EditDocument):SceneLayout {
        val t=template(doc.template);val selected=doc.sceneAssets(t);val multi=t.family==7&&selected.size>1&&!doc.textOnly
        val active=if(multi)selected.firstOrNull()else doc.current
        val sourceW=active?.width?.toFloat()?:1024f;val sourceH=active?.height?.toFloat()?:768f
        val tr=if(multi)doc.overrides[active?.id]?.transform?:PhotoTransform()else doc.currentTransform
        val cropW=sourceW*(tr.cropRight-tr.cropLeft).coerceAtLeast(.05f);val cropH=sourceH*(tr.cropBottom-tr.cropTop).coerceAtLeast(.05f)
        val radians=Math.toRadians(tr.rotation.toDouble());val rotatedW=(abs(cos(radians))*cropW+abs(sin(radians))*cropH).toFloat()
        val rotatedH=(abs(sin(radians))*cropW+abs(cos(radians))*cropH).toFloat()
        val short=min(rotatedW,rotatedH);val f=doc.frame;val style=doc.metadataStyle
        val gap=short*.035f
        val cols=if(multi)when(t.variant){0->2;1->3;2->2;else->1}else 1
        val rows=if(multi)ceil(selected.size.toDouble()/cols).toInt()else 1
        val cw=if(multi)sourceW else rotatedW;var ch=if(multi)sourceH else rotatedH
        if(doc.textOnly)ch=max(400f,CaptionTypography.measure(doc,cw,short,typeface(doc.textStyle.font,doc.textStyle.bold))?.height?:0f)
        val position=style.position(t,cw/ch);val side=position in listOf("left","right")
        val labelWidth=if(side)short*style.columnWidth else cw
        val reference=if(side)short*1.5f else MetadataTypography.reference(cw,ch)
        val variant=if(style.arrangement(t)=="split")1 else 0
        val hasLabels=doc.showMetadata&&!doc.textOnly&&selected.any{doc.hasMetadata(it)}
        val labelHeight=if(hasLabels)selected.filter{doc.hasMetadata(it)}.maxOf{
            MetadataTypography.measure(doc,it,labelWidth,reference,variant,multi,style.alignment(t)).height
        }else 0f
        val labelGap=if(labelHeight>0)short*style.gap(f,position)else 0f
        val photoOffsetX=if(position=="left"&&labelHeight>0)ceil(labelWidth+labelGap)else 0f
        val photoOffsetY=if(position=="top")ceil(labelHeight+labelGap)else if(side)ceil((max(ch,labelHeight)-ch)/2)else 0f
        val cellW=max(cw+if(side&&labelHeight>0)labelWidth+labelGap else 0f,photoOffsetX+cw)
        val cellH=max(if(side)max(ch,labelHeight)else ch+labelHeight+labelGap,photoOffsetY+ch)
        val contentW=max(cellW*cols+gap*(cols-1),((cols-1)*(cellW+gap)).roundToInt()+cellW)
        val contentH=max(cellH*rows+gap*(rows-1),((rows-1)*(cellH+gap)).roundToInt()+cellH)
        val left=short*f.left;val right=short*f.right;val top=short*f.top;val bottom=short*f.bottom
        val body=if(!doc.textOnly)(CaptionTypography.measure(doc,contentW,short,typeface(doc.textStyle.font,doc.textStyle.bold))?.height?:0f)else 0f
        // Metadata owns its spacing once. The frame's outer margin is not repeated inside it.
        val informationFooter=if(t.family==1)0f else if(t.family in listOf(4,6)&&doc.title.isNotBlank())max(short*f.footer,short*.12f)else short*f.footer
        val footer=informationFooter+body
        var w=contentW+left+right;var h=contentH+top+bottom+footer
        val ratio=if(doc.ratio=="custom")doc.customRatio else doc.ratio.split(':').takeIf{it.size==2}?.let{it[0].toFloatOrNull()?.div(it[1].toFloatOrNull()?:1f)}
        if(ratio!=null&&ratio>0){if(w/h>ratio)h=w/ratio else w=h*ratio}
        w=ceil(w).toFloat();h=ceil(h).toFloat()
        val x=((w-contentW-left-right)/2+left).roundToInt().toFloat()
        val y=((h-contentH-top-bottom-footer)/2+top).roundToInt().toFloat()
        var singleLabel:RectF?=null
        val photos=selected.mapIndexed{i,asset->
            val cx=(x+(i%cols)*(cellW+gap)).roundToInt().toFloat();val cy=(y+(i/cols)*(cellH+gap)).roundToInt().toFloat()
            val px=cx+photoOffsetX
            val py=cy+photoOffsetY
            val rect=RectF(px,py,px+cw,py+ch)
            val label=if(labelHeight>0&&doc.hasMetadata(asset))when(position){
                "top"->RectF(rect.left,rect.top-labelGap-labelHeight,rect.right,rect.top-labelGap)
                "left"->RectF(rect.left-labelGap-labelWidth,cy+(cellH-labelHeight)/2,rect.left-labelGap,cy+(cellH+labelHeight)/2)
                "right"->RectF(rect.right+labelGap,cy+(cellH-labelHeight)/2,rect.right+labelGap+labelWidth,cy+(cellH+labelHeight)/2)
                else->RectF(rect.left,rect.bottom+labelGap,rect.right,rect.bottom+labelGap+labelHeight)
            }else null
            if(!multi)singleLabel=label
            PhotoNode(asset,rect,doc.overrides[asset.id]?.transform?:if(multi)PhotoTransform()else tr,if(multi)label else null)
        }
        return SceneLayout(w,h,if(doc.textOnly)emptyList()else photos,RectF(x,y+contentH,x+contentW,y+contentH+informationFooter),t,
            if(body>0)RectF(x,y+contentH+informationFooter,x+contentW,y+contentH+footer)else null,singleLabel,short)
    }
    private fun previewSource(asset:PhotoAsset,maxEdge:Int):Bitmap {
        val key="${asset.uri}:$maxEdge"
        return previewSources[key]?:run {
            val edge=max(asset.width,asset.height);var sample=1;while(edge/sample>maxEdge*2)sample*=2
            val decoded=BitmapFactory.decodeFile(Assets.cacheFile(context,asset.uri).path,BitmapFactory.Options().apply{
                inSampleSize=sample;inPreferredConfig=Bitmap.Config.ARGB_8888;inPreferredColorSpace=ColorSpace.get(ColorSpace.Named.SRGB)
            })?:error(appText(R.string.ui_cannot_read_photo_2140dd))
            if(android.os.Build.VERSION.SDK_INT>=34&&decoded.hasGainmap())decoded.gainmap=null
            val scale=min(1f,maxEdge.toFloat()/max(decoded.width,decoded.height))
            val small=if(scale<1f)Bitmap.createScaledBitmap(decoded,max(1,(decoded.width*scale).roundToInt()),max(1,(decoded.height*scale).roundToInt()),true)else decoded
            if(small!==decoded)decoded.recycle();previewSources.put(key,small);small
        }
    }
    fun resources(doc:EditDocument):Pair<Bitmap?,List<Int>> {
        val asset=doc.current?:return null to listOf(Color.DKGRAY,Color.GRAY,Color.LTGRAY,Color.WHITE,Color.BLACK)
        val scene=layout(doc);val node=scene.photos.firstOrNull{it.asset.id==asset.id}?:scene.photos.firstOrNull()?:return null to listOf(doc.frame.color)
        val key="${asset.uri}:${doc.adjustments.json()}:${node.transform.json()}:${node.rect.width()}:${node.rect.height()}"
        val bitmap=backgrounds[key]?:run {
            val ratio=min(1f,384f/max(node.rect.width(),node.rect.height()))
            val cropped=Bitmap.createBitmap(max(1,(node.rect.width()*ratio).roundToInt()),max(1,(node.rect.height()*ratio).roundToInt()),Bitmap.Config.ARGB_8888)
            val canvas=Canvas(cropped);canvas.scale(ratio,ratio);canvas.translate(-node.rect.left,-node.rect.top);canvas.concat(photoMatrix(node))
            BitmapPixels(mapOf(asset.id to previewSource(asset,720))).draw(canvas,asset,Adjustments())
            val edited=ImageEffects.adjust(cropped,doc.adjustments);if(edited!==cropped)cropped.recycle();backgrounds.put(key,edited);edited
        }
        return bitmap to ImageEffects.palette(bitmap)
    }
    fun preview(doc:EditDocument,maxEdge:Int=2048):PreviewFrame {
        val layout=layout(doc);val ratio=min(1f,maxEdge.toFloat()/max(layout.width,layout.height))
        val bitmap=Bitmap.createBitmap(max(1,(layout.width*ratio).roundToInt()),max(1,(layout.height*ratio).roundToInt()),Bitmap.Config.ARGB_8888)
        val resources=resources(doc)
        val images=layout.photos.associate{node->
            val asset=node.asset;val key="${asset.uri}:$maxEdge"
            val raw=previewSource(asset,maxEdge)
            val editKey="$key:${doc.adjustments.json()}"
            val edited=if(doc.adjustments.copy(vignette=0f).isNeutral)raw else previewEdits[editKey]?:ImageEffects.adjust(raw,doc.adjustments).also{previewEdits.put(editKey,it)}
            asset.id to edited
        }
        BitmapPixels(images).use{pixels->draw(Canvas(bitmap),doc,layout,pixels,ratio,resources)}
        return PreviewFrame(bitmap,layout,resources.second)
    }
    fun viewport(doc:EditDocument,width:Int,height:Int,scale:Float,offsetX:Float,offsetY:Float):Bitmap {
        val scene=layout(doc);val (w,h)=previewViewportSize(width,height)
        val bitmap=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888)
        val canvas=Canvas(bitmap);canvas.scale(w.toFloat()/width,h.toFloat()/height);canvas.translate(offsetX,offsetY)
        canvas.clipRect(0f,0f,scene.width*scale,scene.height*scale)
        RegionPixels(context,ColorSpace.get(ColorSpace.Named.SRGB)).use{draw(canvas,doc,scene,it,scale,resources(doc))}
        return bitmap
    }
    fun draw(canvas:Canvas,doc:EditDocument,scene:SceneLayout,pixels:PhotoPixels,scale:Float,resources:Pair<Bitmap?,List<Int>>,stripeTop:Int=0){
        canvas.save();canvas.translate(0f,-stripeTop.toFloat());canvas.scale(scale,scale)
        val w=scene.width;val h=scene.height;val short=scene.unit;val f=doc.frame;val t=scene.template
        val colors=resources.second;val main=if(f.autoColor)colors[0]else f.color
        val background=if(t.family==3&&t.variant==2)Color.rgb(19,24,24)else main
        canvas.drawColor(background)
        val p=Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        if(t.family==2&&(t.variant==1||t.variant==3)){
            val second=if(t.variant==3)complement(main)else colors[1]
            p.shader=LinearGradient(0f,0f,w,h,main,second,Shader.TileMode.CLAMP);canvas.drawRect(0f,0f,w,h,p);p.shader=null
        }
        if(t.family==3&&resources.first!=null){
            val key="blur:${doc.current?.uri}:${doc.adjustments.json()}:${doc.currentTransform.json()}:${f.blur}:${scene.photos.firstOrNull()?.rect}"
            var blur=backgrounds[key];if(blur==null){blur=ImageEffects.blur(resources.first!!,f.blur);backgrounds.put(key,blur)}
            drawCover(canvas,blur,RectF(0f,0f,w,h),p)
            p.color=if(t.variant==1)Color.WHITE else if(t.variant==2)Color.BLACK else main;p.alpha=(f.tint*255).toInt();canvas.drawRect(0f,0f,w,h,p);p.alpha=255
            val panel=RectF(short*.025f,short*.025f,w-short*.025f,h-short*.025f)
            p.color=if(t.variant==2)Color.BLACK else Color.WHITE;p.alpha=(f.glassOpacity*255).toInt()
            if(t.variant==3)p.setShadowLayer(short*f.lift,0f,short*f.lift/2,0x66000000)
            canvas.drawRoundRect(panel,short*.025f,short*.025f,p);p.clearShadowLayer();p.alpha=130;p.color=Color.WHITE;p.style=Paint.Style.STROKE;p.strokeWidth=short*.0015f
            canvas.drawRoundRect(panel,short*.025f,short*.025f,p);p.style=Paint.Style.FILL;p.alpha=255
        }
        if(t.family==4&&t.variant==1)paper(canvas,scene)
        if(doc.textOnly){
            val rect=RectF(short*f.left,short*f.top,w-short*f.right,h-short*f.bottom)
            CaptionTypography.draw(canvas,rect,doc,short,typeface(doc.textStyle.font,doc.textStyle.bold),if(doc.textStyle.autoColor)contrasting(doc.textStyle.background?:background)else doc.textStyle.color)
        }
        for(node in scene.photos){
            val rect=node.rect;val r=min(rect.width(),rect.height())*f.radius
            if(f.shadow>0){p.color=Color.WHITE;p.setShadowLayer(short*f.shadow,0f,short*f.shadow/2,0x66000000);canvas.drawRoundRect(rect,r,r,p);p.clearShadowLayer()}
            canvas.save();canvas.clipPath(Path().apply{addRoundRect(rect,r,r,Path.Direction.CW)})
            canvas.save();canvas.concat(photoMatrix(node));pixels.draw(canvas,node.asset,doc.adjustments);canvas.restore()
            if(doc.adjustments.vignette>0){p.shader=RadialGradient(rect.centerX(),rect.centerY(),max(rect.width(),rect.height())*.65f,intArrayOf(Color.TRANSPARENT,Color.argb((doc.adjustments.vignette*180).toInt(),0,0,0)),floatArrayOf(.35f,1f),Shader.TileMode.CLAMP);canvas.drawRect(rect,p);p.shader=null}
            canvas.restore()
            if(f.stroke>0||t.id=="double"||t.id=="gallery"){
                p.color=contrasting(background);p.alpha=100;p.style=Paint.Style.STROKE;p.strokeWidth=max(short*f.stroke,short*.001f)
                canvas.drawRoundRect(RectF(rect).apply{inset(-short*.01f,-short*.01f)},r,r,p)
                if(t.id=="double"||t.id=="gallery")canvas.drawRoundRect(RectF(rect).apply{inset(-short*.02f,-short*.02f)},r,r,p)
                p.style=Paint.Style.FILL;p.alpha=255
            }
            if(f.innerStroke>0){
                p.style=Paint.Style.STROKE;p.color=contrasting(background);p.alpha=150;p.strokeWidth=short*f.innerStroke
                canvas.drawRoundRect(RectF(rect).apply{inset(p.strokeWidth/2,p.strokeWidth/2)},r,r,p)
                p.style=Paint.Style.FILL;p.alpha=255
            }
            if(t.id=="inset"){
                p.shader=LinearGradient(rect.left,rect.top-short*.014f,rect.left,rect.top+short*.012f,0x66000000,Color.TRANSPARENT,Shader.TileMode.CLAMP)
                canvas.drawRect(rect.left,rect.top-short*.014f,rect.right,rect.top,p);p.shader=null
            }
            node.label?.let{area->
                drawMetadata(canvas,area,doc,scene,node.asset,compact=true,color=contrasting(background))}
        }
        if(!doc.textOnly)decorations(canvas,doc,scene,background,colors)
        scene.metadata?.let{area->
            val color=if(t.family==3&&t.variant==2)Color.WHITE else contrasting(if(t.family==3)blend(background,Color.WHITE,f.glassOpacity)else background)
            drawMetadata(canvas,area,doc,scene,color=color)
        }
        if(scene.body!=null){
            val body=RectF(scene.body)
            CaptionTypography.draw(canvas,body,doc,short,typeface(doc.textStyle.font,doc.textStyle.bold),if(doc.textStyle.autoColor)contrasting(doc.textStyle.background?:background)else doc.textStyle.color)
        }
        for(layer in doc.layers)drawLayer(canvas,layer,scene)
        canvas.restore()
    }
    fun photoMatrix(node:PhotoNode):Matrix {
        val a=node.asset;val tr=node.transform
        val crop=RectF(a.width*tr.cropLeft,a.height*tr.cropTop,a.width*tr.cropRight,a.height*tr.cropBottom)
        if(crop.width()<=0||crop.height()<=0)error(appText(R.string.ui_invalid_crop_area_963db3))
        val angle=Math.toRadians(tr.rotation.toDouble());val rw=abs(cos(angle))*crop.width()+abs(sin(angle))*crop.height();val rh=abs(sin(angle))*crop.width()+abs(cos(angle))*crop.height()
        val s=(if(tr.cover)max(node.rect.width()/rw,node.rect.height()/rh)else min(1.0,min(node.rect.width()/rw,node.rect.height()/rh))).toFloat()*tr.zoom
        val x=node.rect.left+(rw*s/2+(node.rect.width()-rw*s)*tr.x).toFloat();val y=node.rect.top+(rh*s/2+(node.rect.height()-rh*s)*tr.y).toFloat()
        val (rawW,rawH)=if(a.orientation in 5..8)a.height to a.width else a.width to a.height
        fun times(a:Matrix,b:Matrix)=Matrix().apply{setConcat(a,b)}
        return times(Matrix().apply{setTranslate(x,y)},times(Matrix().apply{setScale(s,s)},times(Matrix().apply{setRotate(tr.rotation)},times(Matrix().apply{setScale(if(tr.mirror)-1f else 1f,1f)},times(Matrix().apply{setTranslate(-crop.centerX(),-crop.centerY())},Assets.orientationMatrix(a.orientation,rawW.toFloat(),rawH.toFloat()))))))
    }
    private fun drawMetadata(canvas:Canvas,area:RectF,doc:EditDocument,scene:SceneLayout,asset:PhotoAsset?=doc.current,compact:Boolean=false,color:Int){
        val photo=scene.photos.firstOrNull()?.rect?:return
        val side=doc.metadataStyle.position(scene.template,photo.width()/photo.height()) in listOf("left","right")
        MetadataTypography.draw(canvas,area,doc,asset,if(side)scene.unit*1.5f else MetadataTypography.reference(photo.width(),photo.height()),
            variant=if(doc.metadataStyle.arrangement(scene.template)=="split")1 else 0,compact=compact,color=color,alignment=doc.metadataStyle.alignment(scene.template))
    }
    private fun decorations(c:Canvas,doc:EditDocument,s:SceneLayout,bg:Int,colors:List<Int>){
        val w=s.width;val h=s.height;val short=s.unit;val t=s.template;val f=doc.frame;val p=Paint(Paint.ANTI_ALIAS_FLAG)
        val foreground=if(t.family==3&&t.variant==2)Color.WHITE else if(t.family==3)contrasting(blend(bg,Color.WHITE,f.glassOpacity))else contrasting(bg)
        val foot=RectF(s.footer).apply{inset(short*.01f,short*.015f)}
        if(t.family==2&&t.variant==2){
            val bar=short*.038f;val y=s.footer.centerY()+bar/2
            colors.forEachIndexed{i,color->p.color=color;val x=w*.1f+i*w*.16f;c.drawRect(x,y-bar,x+w*.14f,y,p)
                drawParagraph(c,"#%06X".format(color and 0xffffff),RectF(x,y,x+w*.14f,s.footer.bottom),short*.012f,contrasting(bg),false,1,true)}
        }else if(t.family==5){
            p.color=Color.rgb(215,205,177)
            if(t.variant==0||t.variant==2){val step=short*.08f;var y=short*.025f;var number=1
                while(y<h-short*.03f){for(x in listOf(short*.014f,w-short*.034f))c.drawRoundRect(RectF(x,y,x+short*.02f,y+short*.035f),short*.004f,short*.004f,p)
                    if(t.variant==2)drawParagraph(c,(number++).toString().padStart(2,'0'),RectF(short*.04f,y,short*.08f,y+short*.04f),short*.018f,p.color,false,1)
                    y+=step}}
            if(t.variant==3){val r=s.photos.firstOrNull()?.rect?:RectF(w*.1f,h*.1f,w*.9f,h*.9f);p.style=Paint.Style.STROKE;p.strokeWidth=short*.002f
                val len=short*.035f;for(x in listOf(r.left+len,r.right-len))for(y in listOf(r.top+len,r.bottom-len)){
                    val dx=if(x<r.centerX())len else -len;val dy=if(y<r.centerY())len else -len;c.drawLine(x,y,x+dx,y,p);c.drawLine(x,y,x,y+dy,p)}
                p.style=Paint.Style.FILL;p.color=Color.RED;c.drawCircle(r.left+len,r.top+len*.6f,short*.004f,p)
                drawParagraph(c,(doc.fields()-doc.hidden())["DateTimeOriginal"]?.let{Assets.display("DateTimeOriginal",it)}?:doc.title,RectF(r.left+len,r.bottom-short*.055f,r.right-len,r.bottom),short*.021f,Color.WHITE,false,1)}
        }else if(t.family==6){
            when(t.variant){
                0->{p.color=0x40808080;p.strokeWidth=short*.001f;c.drawLine(w*.65f,foot.top,w*.65f,foot.bottom,p)
                    drawParagraph(c,doc.title,RectF(foot.left,foot.top,w*.62f,foot.bottom),short*.035f,foreground,true,3)
                    drawParagraph(c,"",RectF(w*.68f,foot.top,foot.right,foot.bottom),short*.022f,foreground,false,5)}
                1->{val photo=s.photos.firstOrNull()?.rect;val r=photo?:RectF(w*.06f,h*.06f,w*.94f,h*.4f)
                    drawParagraph(c,doc.title,RectF(r.left,r.top+short*.025f,r.right,r.top+short*.18f),short*.085f,Color.WHITE,true,2)
                    drawParagraph(c,"",foot,short*.026f,foreground,false,4)}
                2->{p.color=0x30808080;p.strokeWidth=short*.001f;var y=foot.top+short*.035f;while(y<foot.bottom){c.drawLine(foot.left,y,foot.right,y,p);y+=short*.04f}
                    drawParagraph(c,listOf(doc.title,"").filter{it.isNotBlank()}.joinToString("\n"),foot,short*.027f,foreground,false,12)}
                3->drawParagraph(c,listOf(doc.title,"").filter{it.isNotBlank()}.joinToString("\n"),foot,short*.031f,foreground,false,60)
            }
        }else if(t.family==4){drawParagraph(c,listOf(doc.title).filter{it.isNotBlank()}.joinToString("\n"),foot,short*.03f,foreground,t.variant==2,4,t.variant!=0)}
    }
    private fun paper(c:Canvas,s:SceneLayout){
        val p=Paint().apply{color=0x12806a46;strokeWidth=max(1f,min(s.width,s.height)*.0006f)}
        val step=max(4f,min(s.width,s.height)*.008f);val clip=c.clipBounds;var y=(floor(clip.top/step)*step).coerceAtLeast(0f)
        while(y<min(s.height,clip.bottom.toFloat())){var x=0f;while(x<s.width){val n=((x.toInt()*73856093)xor(y.toInt()*19349663))and 31
            c.drawPoint(x+n/32f*step,y+n/48f*step,p);x+=step};y+=step}
    }
    data class LayerShape(val paint:TextPaint,val text:StaticLayout?,val bitmap:Bitmap?,val width:Float,val height:Float,val fit:Float,val bounds:RectF,val reference:RectF,val textScale:Float=1f)
    fun layerShape(l:WatermarkLayer,s:SceneLayout):LayerShape? {
        if(l.uri.isBlank()&&l.text.isBlank())return null
        val short=s.unit;val size=short*l.size*l.scale
        val reference=if(l.anchor=="photo"&&s.photos.isNotEmpty())RectF(s.photos.first().rect).apply{inset(short*.018f,short*.018f)}else RectF(0f,0f,s.width,s.height)
        val bitmap=if(l.uri.isNotBlank())logos[l.uri]?:runCatching{context.contentResolver.openInputStream(Uri.parse(l.uri)).use{BitmapFactory.decodeStream(it)}}.getOrNull()?.also{logos.put(l.uri,it)}else null
        require(l.uri.isBlank()||bitmap!=null){appText(R.string.ui_cannot_read_logo_import_it_again_4e1428)}
        val paint=TextPaint(Paint.ANTI_ALIAS_FLAG).apply{color=l.color;alpha=((if(bitmap==null)Color.alpha(l.color)else 255)*l.opacity).roundToInt();textSize=size;typeface=typeface(l.font,l.bold)}
        val textScale=short/1200f
        val textPaint=TextPaint(paint).apply{textSize=size/textScale}
        val textWidth=min(reference.width()/textScale*.96f,Layout.getDesiredWidth(l.text,textPaint)).coerceAtLeast(1f)
        val text=if(bitmap==null)StaticLayout.Builder.obtain(l.text,0,l.text.length,textPaint,ceil(textWidth).toInt())
            .setAlignment(when(l.alignment){"left"->Layout.Alignment.ALIGN_NORMAL;"right"->Layout.Alignment.ALIGN_OPPOSITE;else->Layout.Alignment.ALIGN_CENTER}).setIncludePad(false).build()else null
        val width=if(bitmap!=null)size*8 else text!!.width*textScale
        val height=if(bitmap!=null)width*bitmap.height/bitmap.width else text!!.height*textScale
        val theta=Math.toRadians(l.angle.toDouble());val bw=(abs(cos(theta))*width+abs(sin(theta))*height).toFloat();val bh=(abs(sin(theta))*width+abs(cos(theta))*height).toFloat()
        val fit=min(1f,min(reference.width()*.98f/bw.coerceAtLeast(1f),reference.height()*.98f/bh.coerceAtLeast(1f)))
        val left=reference.left+(reference.width()-bw*fit)*l.x;val top=reference.top+(reference.height()-bh*fit)*l.y
        return LayerShape(paint,text,bitmap,width,height,fit,RectF(left,top,left+bw*fit,top+bh*fit),reference,textScale)
    }
    fun drawLayer(c:Canvas,l:WatermarkLayer,s:SceneLayout){
        val shape=layerShape(l,s)?:return;val p=shape.paint;val size=s.unit*l.size*l.scale
        val markW=shape.width;val markH=shape.height
        fun stamp(x:Float,y:Float){c.save();c.translate(x,y);c.rotate(l.angle);c.scale(shape.fit,shape.fit)
            if(l.background)c.drawRoundRect(RectF(-markW/2-size*.3f,-markH/2-size*.2f,markW/2+size*.3f,markH/2+size*.2f),size*.15f,size*.15f,Paint().apply{color=Color.BLACK;alpha=(l.opacity*140).roundToInt()})
            if(l.shadow)p.setShadowLayer(size*.12f,0f,size*.06f,Color.BLACK)
            if(shape.bitmap!=null)c.drawBitmap(shape.bitmap,null,RectF(-markW/2,-markH/2,markW/2,markH/2),p)
            else shape.text?.let{text->c.translate(-markW/2,-markH/2);c.scale(shape.textScale,shape.textScale)
                val textPaint=text.paint;textPaint.color=l.color;textPaint.alpha=p.alpha
                if(l.shadow)textPaint.setShadowLayer(size*.12f/shape.textScale,0f,size*.06f/shape.textScale,Color.BLACK)
                if(l.outline){val alpha=textPaint.alpha;textPaint.style=Paint.Style.STROKE;textPaint.strokeWidth=size*.09f/shape.textScale;textPaint.color=Color.BLACK;textPaint.alpha=alpha;text.draw(c);textPaint.style=Paint.Style.FILL;textPaint.color=l.color;textPaint.alpha=alpha}
                text.draw(c)};c.restore()}
        if(l.tiled){val d=hypot(s.width,s.height);val stepX=max(markW*shape.fit+s.unit*l.spacing,d/32);val stepY=max(markH*shape.fit+s.unit*l.spacing,d/32)
            val theta=Math.toRadians(l.angle.toDouble());val cs=cos(theta).toFloat();val sn=sin(theta).toFloat()
            for(row in -ceil(d/stepY).toInt()/2-1..ceil(d/stepY).toInt()/2+1)for(col in -ceil(d/stepX).toInt()/2-1..ceil(d/stepX).toInt()/2+1){
                val x=(col+if(row%2==0)0f else .5f)*stepX;val y=row*stepY;stamp(s.width/2+x*cs-y*sn,s.height/2+x*sn+y*cs)}
        }else stamp(shape.bounds.centerX(),shape.bounds.centerY())
    }
    fun typeface(font:String,bold:Boolean):Typeface=if(font.startsWith("/"))runCatching{val face=Typeface.createFromFile(File(font));if(bold)Typeface.create(face,Typeface.BOLD)else face}.getOrDefault(Typeface.DEFAULT)else Typeface.create(font,if(bold)Typeface.BOLD else Typeface.NORMAL)
    fun textHeight(text:String,width:Float,size:Float):Float=StaticLayout.Builder.obtain(text,0,text.length,TextPaint(Paint.ANTI_ALIAS_FLAG).apply{textSize=size},width.toInt().coerceAtLeast(1)).setIncludePad(false).build().height.toFloat()
    private fun drawParagraph(c:Canvas,text:String,rect:RectF,size:Float,color:Int,bold:Boolean,maxLines:Int,center:Boolean=false){
        if(text.isBlank()||rect.width()<=1||rect.height()<=1)return
        val scale=rect.width()/1200f
        val p=TextPaint(Paint.ANTI_ALIAS_FLAG).apply{this.color=color;textSize=size/scale;typeface=Typeface.create("sans-serif",if(bold)Typeface.BOLD else Typeface.NORMAL)}
        fun make()=StaticLayout.Builder.obtain(text,0,text.length,p,1200).setAlignment(if(center)Layout.Alignment.ALIGN_CENTER else Layout.Alignment.ALIGN_NORMAL)
            .setIncludePad(false).setMaxLines(maxLines).setEllipsize(android.text.TextUtils.TruncateAt.END).build()
        var layout=make();var tries=0
        while((layout.height*scale>rect.height()||(0 until layout.lineCount).any{layout.getEllipsisCount(it)>0})&&tries++<12){p.textSize*=.88f;layout=make()}
        c.save();c.clipRect(rect);c.translate(rect.left,rect.top+max(0f,(rect.height()-layout.height*scale)/2));c.scale(scale,scale);layout.draw(c);c.restore()
    }

    private fun drawCover(c:Canvas,b:Bitmap,r:RectF,p:Paint){val s=max(r.width()/b.width,r.height()/b.height);val w=b.width*s;val h=b.height*s
        c.save();c.clipRect(r);c.drawBitmap(b,null,RectF(r.centerX()-w/2,r.centerY()-h/2,r.centerX()+w/2,r.centerY()+h/2),p);c.restore()}
    fun contrasting(color:Int):Int{
        fun linear(v:Int):Double{val x=v/255.0;return if(x<=.04045)x/12.92 else ((x+.055)/1.055).pow(2.4)}
        val luminance=.2126*linear(Color.red(color))+.7152*linear(Color.green(color))+.0722*linear(Color.blue(color))
        return if((luminance+.05)/.05>=1.05/(luminance+.05))Color.BLACK else Color.WHITE
    }
    private fun complement(c:Int):Int{val hsv=FloatArray(3);Color.colorToHSV(c,hsv);hsv[0]=(hsv[0]+180)%360;return Color.HSVToColor(hsv)}
    private fun blend(a:Int,b:Int,t:Float)=Color.rgb((Color.red(a)*(1-t)+Color.red(b)*t).toInt(),(Color.green(a)*(1-t)+Color.green(b)*t).toInt(),(Color.blue(a)*(1-t)+Color.blue(b)*t).toInt())
}

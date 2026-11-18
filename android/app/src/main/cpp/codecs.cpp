#include <jni.h>
#include <android/bitmap.h>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <csetjmp>
#include <fstream>
#include <vector>
#include <memory>
#include <stdexcept>
#include <algorithm>
#include <cmath>
#include <jpeglib.h>
#include <png.h>
#include "ultrahdr_api.h"

namespace {
void fail(JNIEnv* e,const char* message){e->ThrowNew(e->FindClass("java/io/IOException"),message);}
struct JpegError { jpeg_error_mgr pub; jmp_buf jump; char text[JMSG_LENGTH_MAX]; };
void jpeg_error(j_common_ptr c){auto* err=reinterpret_cast<JpegError*>(c->err);(*c->err->format_message)(c,err->text);longjmp(err->jump,1);}
struct Stream {
    FILE* file=nullptr; bool png=false;bool created=false;bool finished=false;int width=0,height=0,rows=0;
    jpeg_compress_struct jpeg{};JpegError error{};png_structp p=nullptr;png_infop info=nullptr;
    ~Stream(){if(created&&!png)jpeg_destroy_compress(&jpeg);if(p)png_destroy_write_struct(&p,info?&info:nullptr);if(file)fclose(file);}
};
std::vector<unsigned char> bytes(JNIEnv* e,jbyteArray data){if(!data)return {};std::vector<unsigned char> out(e->GetArrayLength(data));e->GetByteArrayRegion(data,0,out.size(),reinterpret_cast<jbyte*>(out.data()));return out;}
std::vector<unsigned char> read_file(const char* path){std::ifstream f(path,std::ios::binary);if(!f)throw std::runtime_error("无法读取原图");return {std::istreambuf_iterator<char>(f),std::istreambuf_iterator<char>()};}
bool check(JNIEnv* e,uhdr_error_info_t err){if(err.error_code!=UHDR_CODEC_OK){fail(e,err.has_detail?err.detail:"Ultra HDR 编解码失败");return false;}return true;}
struct Pixels {JNIEnv* env;jobject bitmap;AndroidBitmapInfo info{};void* data=nullptr;
    Pixels(JNIEnv* e,jobject b):env(e),bitmap(b){if(AndroidBitmap_getInfo(e,b,&info)<0||AndroidBitmap_lockPixels(e,b,&data)<0)throw std::runtime_error("无法访问图片像素");}
    ~Pixels(){if(data)AndroidBitmap_unlockPixels(env,bitmap);}
};
}

extern "C" JNIEXPORT jlong JNICALL Java_com_parker_imageborder_editor_NativeCodec_start(JNIEnv* e,jobject,jstring path,jint width,jint height,jboolean png,jint quality,jbyteArray profile){
    std::unique_ptr<Stream> s(new Stream());s->width=width;s->height=height;s->png=png;
    if(width<1||height<1||width>60000||height>60000){fail(e,"输出尺寸超限");return 0;}
    const char* file=e->GetStringUTFChars(path,nullptr);s->file=fopen(file,"wb");e->ReleaseStringUTFChars(path,file);
    if(!s->file){fail(e,"无法创建导出文件，检查可用空间");return 0;}
    auto icc=bytes(e,profile);
    if(png){
        s->p=png_create_write_struct(PNG_LIBPNG_VER_STRING,nullptr,nullptr,nullptr);if(!s->p){fail(e,"PNG 编码器分配失败");return 0;}
        s->info=png_create_info_struct(s->p);if(!s->info){fail(e,"PNG 编码器分配失败");return 0;}
        if(setjmp(png_jmpbuf(s->p))){fail(e,"PNG 写入失败，检查可用空间");return 0;}
        png_init_io(s->p,s->file);png_set_IHDR(s->p,s->info,width,height,8,PNG_COLOR_TYPE_RGBA,PNG_INTERLACE_NONE,PNG_COMPRESSION_TYPE_DEFAULT,PNG_FILTER_TYPE_DEFAULT);
        if(!icc.empty())png_set_iCCP(s->p,s->info,"Image profile",PNG_COMPRESSION_TYPE_BASE,icc.data(),icc.size());else png_set_sRGB(s->p,s->info,PNG_sRGB_INTENT_PERCEPTUAL);
        png_write_info(s->p,s->info);
    }else{
        s->jpeg.err=jpeg_std_error(&s->error.pub);s->error.pub.error_exit=jpeg_error;
        if(setjmp(s->error.jump)){fail(e,s->error.text);return 0;}
        jpeg_create_compress(&s->jpeg);s->created=true;jpeg_stdio_dest(&s->jpeg,s->file);s->jpeg.image_width=width;s->jpeg.image_height=height;s->jpeg.input_components=3;s->jpeg.in_color_space=JCS_RGB;
        jpeg_set_defaults(&s->jpeg);jpeg_set_quality(&s->jpeg,quality,TRUE);
        // Text and thin frame edges benefit from 4:4:4 chroma rather than the JPEG 4:2:0 default.
        for(int c=0;c<3;c++){s->jpeg.comp_info[c].h_samp_factor=1;s->jpeg.comp_info[c].v_samp_factor=1;}
        jpeg_start_compress(&s->jpeg,TRUE);if(!icc.empty())jpeg_write_icc_profile(&s->jpeg,icc.data(),icc.size());
    }
    return reinterpret_cast<jlong>(s.release());
}
void write_rows(JNIEnv* e,jlong handle,jobject bitmap,jint left,jint top,jint rows){
    auto* s=reinterpret_cast<Stream*>(handle);if(!s){fail(e,"编码任务已关闭");return;}
    try{Pixels pixel(e,bitmap);if(pixel.info.format!=ANDROID_BITMAP_FORMAT_RGBA_8888||left<0||top<0||left+s->width>pixel.info.width||top+rows>pixel.info.height||s->rows+rows>s->height)throw std::runtime_error("图片条带尺寸不匹配");
        std::vector<unsigned char> line(s->width*(s->png?4:3));
        if(s->png){if(setjmp(png_jmpbuf(s->p))){fail(e,"PNG 写入失败");return;}}
        else if(setjmp(s->error.jump)){fail(e,s->error.text);return;}
        for(int y=0;y<rows;y++){
            auto* src=static_cast<unsigned char*>(pixel.data)+(y+top)*pixel.info.stride+left*4;
            for(int x=0;x<s->width;x++){
                unsigned a=src[x*4+3];
                if(s->png){for(int c=0;c<3;c++)line[x*4+c]=a?std::min(255u,(src[x*4+c]*255u+a/2)/a):0;line[x*4+3]=a;}
                else for(int c=0;c<3;c++)line[x*3+c]=std::min(255,src[x*4+c]+255-static_cast<int>(a));
            }
            if(s->png)png_write_row(s->p,line.data());else{JSAMPROW row=line.data();jpeg_write_scanlines(&s->jpeg,&row,1);}s->rows++;
        }
    }catch(const std::exception& err){fail(e,err.what());}
}
extern "C" JNIEXPORT void JNICALL Java_com_parker_imageborder_editor_NativeCodec_write(JNIEnv* e,jobject,jlong handle,jobject bitmap,jint rows){write_rows(e,handle,bitmap,0,0,rows);}
extern "C" JNIEXPORT void JNICALL Java_com_parker_imageborder_editor_NativeCodec_writeRegion(JNIEnv* e,jobject,jlong handle,jobject bitmap,jint left,jint top,jint rows){write_rows(e,handle,bitmap,left,top,rows);}
extern "C" JNIEXPORT void JNICALL Java_com_parker_imageborder_editor_NativeCodec_finish(JNIEnv* e,jobject,jlong handle,jboolean commit){
    std::unique_ptr<Stream> s(reinterpret_cast<Stream*>(handle));if(!s||!commit)return;
    if(s->rows!=s->height){fail(e,"导出未完成，图片不予保存");return;}
    if(s->png){if(setjmp(png_jmpbuf(s->p))){fail(e,"PNG 完成写入失败");return;}png_write_end(s->p,s->info);}
    else{if(setjmp(s->error.jump)){fail(e,s->error.text);return;}jpeg_finish_compress(&s->jpeg);}
    if(fflush(s->file)!=0){fail(e,"保存失败，可能没有足够空间");return;}s->finished=true;
}
extern "C" JNIEXPORT jobject JNICALL Java_com_parker_imageborder_editor_NativeCodec_decodeHdr(JNIEnv* e,jobject,jstring path,jint maxEdge){
    const char* name=e->GetStringUTFChars(path,nullptr);std::vector<unsigned char> data;
    try{data=read_file(name);}catch(const std::exception& err){e->ReleaseStringUTFChars(path,name);fail(e,err.what());return nullptr;}e->ReleaseStringUTFChars(path,name);
    auto* decoder=uhdr_create_decoder();if(!decoder){fail(e,"HDR 解码器分配失败");return nullptr;}
    struct Release{uhdr_codec_private_t* p;~Release(){uhdr_release_decoder(p);}}release{decoder};
    uhdr_compressed_image_t input{data.data(),data.size(),data.size(),UHDR_CG_UNSPECIFIED,UHDR_CT_UNSPECIFIED,UHDR_CR_UNSPECIFIED};
    if(!check(e,uhdr_dec_set_image(decoder,&input))||!check(e,uhdr_dec_set_out_img_format(decoder,UHDR_IMG_FMT_64bppRGBAHalfFloat))||!check(e,uhdr_dec_set_out_color_transfer(decoder,UHDR_CT_LINEAR))||!check(e,uhdr_dec_probe(decoder)))return nullptr;
    int w=uhdr_dec_get_image_width(decoder),h=uhdr_dec_get_image_height(decoder);
    if(maxEdge>0&&std::max(w,h)>maxEdge){float scale=static_cast<float>(maxEdge)/std::max(w,h);if(!check(e,uhdr_add_effect_resize(decoder,std::max(1,static_cast<int>(w*scale)),std::max(1,static_cast<int>(h*scale)))))return nullptr;}
    if(!check(e,uhdr_decode(decoder)))return nullptr;
    auto* decoded=uhdr_get_decoded_image(decoder);if(!decoded){fail(e,"HDR 解码无输出");return nullptr;}
    jclass cls=e->FindClass("com/parker/imageborder/editor/NativeCodec");jmethodID make=e->GetStaticMethodID(cls,"hdrBitmap","(III)Landroid/graphics/Bitmap;");
    jobject bitmap=e->CallStaticObjectMethod(cls,make,decoded->w,decoded->h,static_cast<int>(decoded->cg));if(e->ExceptionCheck()||!bitmap)return nullptr;
    try{Pixels output(e,bitmap);for(unsigned y=0;y<decoded->h;y++)memcpy(static_cast<unsigned char*>(output.data)+y*output.info.stride,static_cast<unsigned char*>(decoded->planes[0])+y*decoded->stride[0]*8,decoded->w*8);}catch(const std::exception& err){fail(e,err.what());return nullptr;}
    return bitmap;
}
extern "C" JNIEXPORT void JNICALL Java_com_parker_imageborder_editor_NativeCodec_encodeHdr(JNIEnv* e,jobject,jobject base,jobject hdr,jstring path,jint quality,jint gamut,jbyteArray exif){
    auto* encoder=uhdr_create_encoder();if(!encoder){fail(e,"HDR 编码器分配失败");return;}
    struct Release{uhdr_codec_private_t* p;~Release(){uhdr_release_encoder(p);}}release{encoder};
    try{Pixels sdr(e,base),high(e,hdr);
        if(sdr.info.width!=high.info.width||sdr.info.height!=high.info.height||sdr.info.format!=ANDROID_BITMAP_FORMAT_RGBA_8888||high.info.format!=ANDROID_BITMAP_FORMAT_RGBA_F16)throw std::runtime_error("HDR 场景尺寸或格式不匹配");
        uhdr_raw_image_t s{};s.fmt=UHDR_IMG_FMT_32bppRGBA8888;s.cg=UHDR_CG_DISPLAY_P3;s.ct=UHDR_CT_SRGB;s.range=UHDR_CR_FULL_RANGE;s.w=sdr.info.width;s.h=sdr.info.height;s.planes[0]=sdr.data;s.stride[0]=sdr.info.stride/4;
        uhdr_raw_image_t h{};h.fmt=UHDR_IMG_FMT_64bppRGBAHalfFloat;h.cg=static_cast<uhdr_color_gamut_t>(gamut);h.ct=UHDR_CT_LINEAR;h.range=UHDR_CR_FULL_RANGE;h.w=high.info.width;h.h=high.info.height;h.planes[0]=high.data;h.stride[0]=high.info.stride/8;
        if(!check(e,uhdr_enc_set_raw_image(encoder,&s,UHDR_SDR_IMG))||!check(e,uhdr_enc_set_raw_image(encoder,&h,UHDR_HDR_IMG))||!check(e,uhdr_enc_set_quality(encoder,quality,UHDR_BASE_IMG)))return;
        auto metadata=bytes(e,exif);if(!metadata.empty()){uhdr_mem_block_t block{metadata.data(),metadata.size(),metadata.size()};if(!check(e,uhdr_enc_set_exif_data(encoder,&block)))return;}
        if(!check(e,uhdr_encode(encoder)))return;auto* encoded=uhdr_get_encoded_stream(encoder);if(!encoded)throw std::runtime_error("HDR 编码无输出");
        const char* name=e->GetStringUTFChars(path,nullptr);FILE* file=fopen(name,"wb");e->ReleaseStringUTFChars(path,name);if(!file)throw std::runtime_error("无法写入 HDR 文件");
        size_t written=fwrite(encoded->data,1,encoded->data_sz,file);int result=fclose(file);if(written!=encoded->data_sz||result!=0)throw std::runtime_error("HDR 写入失败，检查可用空间");
    }catch(const std::exception& err){fail(e,err.what());}
}

// Operate in the bitmap's own encoded RGB primaries. The exposure LUT contains
// the matching EOTF/OETF; no sRGB getPixels conversion is introduced here.
extern "C" JNIEXPORT void JNICALL Java_com_parker_imageborder_editor_NativeCodec_adjustPixels(JNIEnv* e,jobject,jobject bitmap,jfloatArray parameters,jfloatArray exposure,jint filter){
    if(e->GetArrayLength(parameters)!=8||e->GetArrayLength(exposure)!=256){fail(e,"调色参数不完整");return;}
    float a[8],lut[256];e->GetFloatArrayRegion(parameters,0,8,a);e->GetFloatArrayRegion(exposure,0,256,lut);
    try{
        Pixels pixels(e,bitmap);if(pixels.info.format!=ANDROID_BITMAP_FORMAT_RGBA_8888)throw std::runtime_error("调色需要 RGB 图片");
        const int w=pixels.info.width,h=pixels.info.height;auto* data=static_cast<unsigned char*>(pixels.data);
        std::vector<unsigned char> unsharp;if(a[6]>0)unsharp.resize(static_cast<size_t>(w)*h*3);
        auto quantize=[](float v){return std::max(0,std::min(255,static_cast<int>(std::round(v*255.f))));};
        for(int y=0;y<h;y++)for(int x=0;x<w;x++){
            auto* p=data+y*pixels.info.stride+x*4;const unsigned alpha=p[3];
            auto straight=[alpha](int v){return alpha?std::min(255u,v*255u/alpha):0u;};
            float r=lut[straight(p[0])],g=lut[straight(p[1])],b=lut[straight(p[2])];
            float l=.2126f*r+.7152f*g+.0722f*b,low=1.f-l;
            float lift=a[5]*low*low*.35f-a[4]*l*l*.25f;r+=lift;g+=lift;b+=lift;
            r=(r-.5f)*(1.f+a[0])+.5f;g=(g-.5f)*(1.f+a[0])+.5f;b=(b-.5f)*(1.f+a[0])+.5f;
            l=.2126f*r+.7152f*g+.0722f*b;r=l+(r-l)*(1.f+a[1]);g=l+(g-l)*(1.f+a[1]);b=l+(b-l)*(1.f+a[1]);
            r+=a[2]*.12f+a[3]*.04f;g-=a[3]*.08f;b-=a[2]*.12f-a[3]*.04f;
            float fr=r,fg=g,fb=b;
            switch(filter){
                case 1:fr=.08f+.85f*r;fg=.08f+.85f*g;fb=.08f+.85f*b;break;
                case 2:fr=r*1.08f+.025f;fg=g*1.02f;fb=b*.89f;break;
                case 3:fr=r*.9f;fg=g*1.02f;fb=b*1.09f;break;
                case 4:fr=.09f+r*.88f;fg=.07f+g*.84f;fb=.09f+b*.78f;break;
                case 5:fr=l+(r-l)*1.3f;fg=l+(g-l)*1.3f;fb=l+(b-l)*1.3f;break;
                case 6:fr=fg=fb=l;break;
                case 7:fr=fg=fb=(l-.5f)*1.4f+.5f;break;
            }
            const int rgb[3]={quantize(r+(fr-r)*a[7]),quantize(g+(fg-g)*a[7]),quantize(b+(fb-b)*a[7])};
            for(int c=0;c<3;c++){if(!unsharp.empty())unsharp[(static_cast<size_t>(y)*w+x)*3+c]=rgb[c];p[c]=rgb[c]*alpha/255;}
        }
        if(!unsharp.empty()&&w>2&&h>2){
            for(int y=1;y<h-1;y++)for(int x=1;x<w-1;x++){
                auto* p=data+y*pixels.info.stride+x*4;const size_t i=(static_cast<size_t>(y)*w+x)*3;
                for(int c=0;c<3;c++){
                    float avg=(unsharp[i-3+c]+unsharp[i+3+c]+unsharp[i-w*3+c]+unsharp[i+w*3+c])*.25f;
                    int value=std::max(0,std::min(255,static_cast<int>(std::round(unsharp[i+c]+(unsharp[i+c]-avg)*a[6]*.35f))));
                    p[c]=value*p[3]/255;
                }
            }
        }
    }catch(const std::exception& err){fail(e,err.what());}
}

extern "C" JNIEXPORT void JNICALL Java_com_parker_imageborder_editor_NativeCodec_applyLook(JNIEnv* e,jobject,jobject bitmap,jfloatArray table,jfloat strength){
    constexpr int edge=33;
    if(e->GetArrayLength(table)!=edge*edge*edge*3){fail(e,"滤镜查找表无效");return;}
    jfloat* lut=e->GetFloatArrayElements(table,nullptr);if(!lut)return;
    try{
        Pixels pixels(e,bitmap);if(pixels.info.format!=ANDROID_BITMAP_FORMAT_RGBA_8888)throw std::runtime_error("滤镜需要 RGB 图片");
        auto* data=static_cast<unsigned char*>(pixels.data);strength=std::clamp(strength,0.f,1.f);
        for(unsigned y=0;y<pixels.info.height;y++)for(unsigned x=0;x<pixels.info.width;x++){
            auto* p=data+y*pixels.info.stride+x*4;unsigned alpha=p[3];if(!alpha)continue;
            float input[3],fraction[3];int base[3];
            for(int c=0;c<3;c++){input[c]=std::min(1.f,p[c]/static_cast<float>(alpha));float v=input[c]*(edge-1);base[c]=std::min(edge-2,static_cast<int>(v));fraction[c]=v-base[c];}
            for(int c=0;c<3;c++){
                float value=0;
                for(int r=0;r<2;r++)for(int g=0;g<2;g++)for(int b=0;b<2;b++){
                    float weight=(r?fraction[0]:1-fraction[0])*(g?fraction[1]:1-fraction[1])*(b?fraction[2]:1-fraction[2]);
                    value+=lut[(((base[0]+r)*edge+base[1]+g)*edge+base[2]+b)*3+c]*weight;
                }
                int channel=std::clamp(static_cast<int>(std::round((input[c]+(value-input[c])*strength)*255.f)),0,255);
                p[c]=channel*alpha/255;
            }
        }
    }catch(const std::exception& error){fail(e,error.what());}
    e->ReleaseFloatArrayElements(table,lut,JNI_ABORT);
}

// Android Gainmap math at full HDR headroom, using one shared base-image raster.
extern "C" JNIEXPORT void JNICALL Java_com_parker_imageborder_editor_NativeCodec_reconstructHdr(JNIEnv* e,jobject,jobject base,jobject gainmap,jobject output,jfloatArray metadata,jfloatArray transfer,jfloatArray matrices){
    if(e->GetArrayLength(metadata)!=15||e->GetArrayLength(transfer)!=256||e->GetArrayLength(matrices)!=18){fail(e,"HDR 参数不完整");return;}
    float p[15],linear[256],m[18];e->GetFloatArrayRegion(metadata,0,15,p);e->GetFloatArrayRegion(transfer,0,256,linear);e->GetFloatArrayRegion(matrices,0,18,m);
    constexpr int steps=4096;float gains[3][steps];
    for(int c=0;c<3;c++)for(int i=0;i<steps;i++)gains[c][i]=std::exp(std::log(p[c])+(std::log(p[c+3])-std::log(p[c]))*std::pow(i/static_cast<float>(steps-1),p[c+6]));
    try{
        Pixels sdr(e,base),map(e,gainmap),dst(e,output);
        if(sdr.info.format!=ANDROID_BITMAP_FORMAT_RGBA_8888||dst.info.format!=ANDROID_BITMAP_FORMAT_RGBA_F16||dst.info.width!=sdr.info.width||dst.info.height!=sdr.info.height)throw std::runtime_error("HDR 图片格式或尺寸不匹配");
        if(map.info.format!=ANDROID_BITMAP_FORMAT_A_8&&map.info.format!=ANDROID_BITMAP_FORMAT_RGBA_8888)throw std::runtime_error("HDR 增益图格式暂不支持");
        auto* source=static_cast<unsigned char*>(sdr.data);auto* g=static_cast<unsigned char*>(map.data);auto* out=static_cast<unsigned char*>(dst.data);
        auto channel=[&](int x,int y,int c){return map.info.format==ANDROID_BITMAP_FORMAT_A_8?g[y*map.info.stride+x]/255.f:g[y*map.info.stride+x*4+c]/255.f;};
        for(unsigned y=0;y<sdr.info.height;y++){
            float gy=std::clamp((y+.5f)*map.info.height/sdr.info.height-.5f,0.f,map.info.height-1.f);int y0=static_cast<int>(gy),y1=std::min(y0+1,static_cast<int>(map.info.height)-1);float fy=gy-y0;
            for(unsigned x=0;x<sdr.info.width;x++){
                auto* src=source+y*sdr.info.stride+x*4;auto* pixel=reinterpret_cast<__fp16*>(out+y*dst.info.stride+x*8);
                float alpha=src[3]/255.f;float b[3],v[3];
                for(int c=0;c<3;c++){int index=src[3]?std::min(255,src[c]*255/static_cast<int>(src[3])):0;b[c]=linear[index];}
                for(int c=0;c<3;c++)v[c]=m[c]*b[0]+m[c+3]*b[1]+m[c+6]*b[2];
                float gx=std::clamp((x+.5f)*map.info.width/sdr.info.width-.5f,0.f,map.info.width-1.f);int x0=static_cast<int>(gx),x1=std::min(x0+1,static_cast<int>(map.info.width)-1);float fx=gx-x0;
                for(int c=0;c<3;c++){
                    float a=channel(x0,y0,c)*(1-fx)+channel(x1,y0,c)*fx;
                    float d=channel(x0,y1,c)*(1-fx)+channel(x1,y1,c)*fx;
                    float index=std::clamp((a*(1-fy)+d*fy)*(steps-1),0.f,steps-1.f);int lo=std::min(steps-2,static_cast<int>(index));float weight=index-lo;
                    float gain=gains[c][lo]*(1-weight)+gains[c][lo+1]*weight;b[c]=(v[c]+p[c+9])*gain-p[c+12];
                }
                for(int c=0;c<3;c++)pixel[c]=static_cast<__fp16>(std::clamp((m[c+9]*b[0]+m[c+12]*b[1]+m[c+15]*b[2])*alpha,0.f,65504.f));
                pixel[3]=static_cast<__fp16>(alpha);
            }
        }
    }catch(const std::exception& error){fail(e,error.what());}
}

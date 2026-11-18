package com.parker.imageborder;

import android.Manifest;
import android.content.ClipData;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.graphics.BitmapFactory;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import androidx.core.content.FileProvider;
import com.getcapacitor.JSObject;
import com.getcapacitor.PermissionState;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Arrays;

@CapacitorPlugin(
    name = "NativeImages",
    permissions = @Permission(alias = "savePhotos", strings = { Manifest.permission.WRITE_EXTERNAL_STORAGE })
)
public class NativeImagesPlugin extends Plugin {
    private static final byte[] PNG_SIGNATURE = { (byte) 137, 80, 78, 71, 13, 10, 26, 10 };

    @PluginMethod
    public void saveImage(PluginCall call) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q && getPermissionState("savePhotos") != PermissionState.GRANTED) {
            requestPermissionForAlias("savePhotos", call, "savePermissionResult");
            return;
        }
        saveToGallery(call);
    }

    @PermissionCallback
    private void savePermissionResult(PluginCall call) {
        if (getPermissionState("savePhotos") == PermissionState.GRANTED) {
            saveToGallery(call);
        } else {
            call.reject("未获得保存图片的权限，请在手机设置中允许后重试。");
        }
    }

    private String imageMimeType(PluginCall call) throws IOException {
        String mimeType = call.getString("mimeType", "image/png");
        if (!"image/png".equals(mimeType) && !"image/jpeg".equals(mimeType)) {
            throw new IOException("仅支持 PNG 和 JPG 图片。");
        }
        return mimeType;
    }

    private byte[] decodeImage(PluginCall call, String mimeType) throws IOException {
        String data = call.getString("data");
        if (data == null || data.length() > 64 * 1024 * 1024) {
            throw new IOException("图片数据过大或为空，请减小图片尺寸后重试。");
        }
        byte[] bytes;
        try {
            bytes = Base64.decode(data, Base64.DEFAULT);
        } catch (IllegalArgumentException e) {
            throw new IOException("图片数据无效。", e);
        }
        boolean png = bytes.length >= 8 && Arrays.equals(Arrays.copyOf(bytes, 8), PNG_SIGNATURE);
        boolean jpeg = bytes.length >= 3 && bytes[0] == (byte) 0xFF && bytes[1] == (byte) 0xD8 && bytes[2] == (byte) 0xFF;
        if (("image/png".equals(mimeType) && !png) || ("image/jpeg".equals(mimeType) && !jpeg)) {
            throw new IOException("图片格式无效。");
        }
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
        if (!mimeType.equals(options.outMimeType)) throw new IOException("图片格式与文件类型不匹配。");
        if (options.outWidth <= 0 || options.outHeight <= 0 || options.outWidth > 16384 || options.outHeight > 16384
            || (long) options.outWidth * options.outHeight > 12000000L) {
            throw new IOException("图片尺寸过大或无效。");
        }
        return bytes;
    }

    private String imageName(PluginCall call, String mimeType) {
        String name = call.getString("name", "图片-" + System.currentTimeMillis());
        name = name.replaceAll("[^\\p{L}\\p{N}_.-]", "_");
        name = name.replaceFirst("(?i)\\.(png|jpe?g)$", "");
        if (name.length() > 100) name = name.substring(0, 100);
        return name + ("image/jpeg".equals(mimeType) ? ".jpg" : ".png");
    }

    private void saveToGallery(PluginCall call) {
        getBridge().execute(() -> {
            Uri pendingUri = null;
            File legacyFile = null;
            ContentResolver resolver = getContext().getContentResolver();
            try {
                String mimeType = imageMimeType(call);
                byte[] bytes = decodeImage(call, mimeType);
                String name = imageName(call, mimeType);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ContentValues values = new ContentValues();
                    values.put(MediaStore.Images.Media.DISPLAY_NAME, name);
                    values.put(MediaStore.Images.Media.MIME_TYPE, mimeType);
                    values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/文字拼图");
                    values.put(MediaStore.Images.Media.IS_PENDING, 1);
                    pendingUri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
                    if (pendingUri == null) throw new IOException("无法创建相册图片。");
                    try (OutputStream stream = resolver.openOutputStream(pendingUri)) {
                        if (stream == null) throw new IOException("无法写入相册。");
                        stream.write(bytes);
                    }
                    values.clear();
                    values.put(MediaStore.Images.Media.IS_PENDING, 0);
                    if (resolver.update(pendingUri, values, null, null) < 1) throw new IOException("无法完成图片保存。");
                    JSObject result = new JSObject();
                    result.put("uri", pendingUri.toString());
                    call.resolve(result);
                } else {
                    File directory = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "文字拼图");
                    if (!directory.exists() && !directory.mkdirs()) throw new IOException("无法创建相册文件夹。");
                    legacyFile = new File(directory, name);
                    try (OutputStream stream = new FileOutputStream(legacyFile)) {
                        stream.write(bytes);
                    }
                    MediaScannerConnection.scanFile(getContext(), new String[] { legacyFile.getAbsolutePath() }, new String[] { mimeType }, null);
                    call.resolve();
                }
            } catch (Exception e) {
                if (pendingUri != null) {
                    try { resolver.delete(pendingUri, null, null); } catch (Exception ignored) { }
                }
                if (legacyFile != null) legacyFile.delete();
                call.reject("保存失败：" + e.getMessage(), e);
            }
        });
    }

    @PluginMethod
    public void shareImage(PluginCall call) {
        getBridge().execute(() -> {
            try {
                String mimeType = imageMimeType(call);
                byte[] bytes = decodeImage(call, mimeType);
                File directory = new File(getContext().getCacheDir(), "shared-images");
                if (!directory.exists() && !directory.mkdirs()) throw new IOException("无法准备分享图片。");
                File[] oldFiles = directory.listFiles();
                if (oldFiles != null) {
                    for (File file : oldFiles) {
                        if (System.currentTimeMillis() - file.lastModified() > 86400000L) file.delete();
                    }
                }
                File file = new File(directory, imageName(call, mimeType));
                try (OutputStream stream = new FileOutputStream(file)) {
                    stream.write(bytes);
                }
                Uri uri = FileProvider.getUriForFile(getContext(), getContext().getPackageName() + ".fileprovider", file);
                Intent intent = new Intent(Intent.ACTION_SEND);
                intent.setType(mimeType);
                intent.putExtra(Intent.EXTRA_STREAM, uri);
                intent.setClipData(ClipData.newRawUri("图片", uri));
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                getBridge().executeOnMainThread(() -> {
                    try {
                        getActivity().startActivity(Intent.createChooser(intent, "分享图片"));
                        call.resolve();
                    } catch (Exception e) {
                        call.reject("无法打开系统分享面板。", e);
                    }
                });
            } catch (Exception e) {
                call.reject("分享失败：" + e.getMessage(), e);
            }
        });
    }
}

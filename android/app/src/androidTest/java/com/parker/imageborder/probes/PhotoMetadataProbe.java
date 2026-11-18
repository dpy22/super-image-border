package com.parker.imageborder.probes;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.media.ExifInterface;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;

/** Framework-only read-only probe for the minified APK. No Kotlin/AndroidX runtime dependency. */
public final class PhotoMetadataProbe extends Instrumentation {
    private String action = "verify";
    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); if(arguments != null) action = arguments.getString("action", "verify"); start(); }
    @Override public void onStart() {
        Bundle output = new Bundle();
        try {
            Context context = getTargetContext();
            if("restore-chinese".equals(action)){
                context.getSystemService(android.app.LocaleManager.class).setApplicationLocales(android.os.LocaleList.forLanguageTags("zh-Hans"));
                output.putString("stream","Restored the Chinese app interface observed at the start of this audit. System language unchanged.\n");finish(Activity.RESULT_OK,output);return;
            }
            if (Arrays.asList("capture-current","capture-detail","capture-frame","capture-text","capture-adjust","capture-number","capture-metadata","capture-metadata-layout").contains(action)) { captureCurrent(context,output);finish(Activity.RESULT_OK,output);return; }
            if ("create-fixtures".equals(action)) { createFixtures(context, output); finish(Activity.RESULT_OK, output); return; }
            if ("clean-fixtures".equals(action)) { cleanFixtures(context); output.putString("stream", "Temporary gallery pictures removed.\n"); finish(Activity.RESULT_OK, output); return; }

            if ("brand-summary".equals(action)) { brandSummary(context,output);finish(Activity.RESULT_OK,output);return; }
            if ("capture-export".equals(action)) { captureExport(context,output);finish(Activity.RESULT_OK,output);return; }
            if ("locale-summary".equals(action)) {
                String override="system";
                if(android.os.Build.VERSION.SDK_INT>=33) {
                    String tags=context.getSystemService(android.app.LocaleManager.class).getApplicationLocales().toLanguageTags();
                    if(!tags.isEmpty())override=tags;
                }
                int label=context.getResources().getIdentifier("settings_title","string",context.getPackageName());
                require(label!=0,"Settings resource missing");
                output.putString("stream","App language override="+override+"; effective="+context.getResources().getConfiguration().getLocales().get(0).toLanguageTag()+"; settings label="+context.getString(label)+"\n");
                finish(Activity.RESULT_OK,output);return;
            }
            File databaseFile = context.getDatabasePath("photo-editor.db");
            require(databaseFile.isFile(), "No saved document");
            if ("library-summary".equals(action)) {
                int drafts=0, completed=0, unfinished=0;
                try (SQLiteDatabase database=SQLiteDatabase.openDatabase(databaseFile.getPath(),null,SQLiteDatabase.OPEN_READONLY);
                     Cursor cursor=database.rawQuery("SELECT kind,payload FROM editor_records WHERE kind IN ('draft','job')",null)) {
                    while(cursor.moveToNext()) {
                        if ("draft".equals(cursor.getString(0))) drafts++;
                        else { String status;
                            try { status=new JSONObject(cursor.getString(1)).optString("status"); } catch(Throwable ignored) { status="unknown"; }
                            if ("done".equals(status)) completed++; else unfinished++;
                        }
                    }
                }
                require(drafts<=5,"Draft limit exceeded");require(completed<=5,"Completed export limit exceeded");
                output.putString("stream","Draft records="+drafts+"; completed export records="+completed+"; protected unfinished records="+unfinished+"\nPASS: library record limits.\n");
                finish(Activity.RESULT_OK,output);return;
            }

            JSONObject document;
            try (SQLiteDatabase database = SQLiteDatabase.openDatabase(databaseFile.getPath(), null, SQLiteDatabase.OPEN_READONLY);
                 Cursor cursor = database.rawQuery("SELECT payload FROM editor_records WHERE kind = ? ORDER BY updated DESC LIMIT 1", new String[]{"draft"})) {
                require(cursor.moveToFirst(), "No saved draft");
                document = new JSONObject(cursor.getString(0));
            }
            JSONArray assets = document.getJSONArray("assets");
            JSONObject asset = assets.getJSONObject(document.optInt("active"));
            JSONObject stored = asset.getJSONObject("exif");
            Uri uri = Uri.parse(asset.getString("uri"));
            boolean direct = MediaStore.AUTHORITY.equals(uri.getAuthority()) && uri.getPathSegments().contains("images") && uri.getPathSegments().contains("media");
            boolean original = MediaStore.getRequireOriginal(uri);
            ExifInterface source;
            try (ParcelFileDescriptor descriptor = context.getContentResolver().openFileDescriptor(uri, "r")) {
                require(descriptor != null, "Source unavailable");
                source = new ExifInterface(descriptor.getFileDescriptor());
            }
            if("focal-summary".equals(action)){
                output.putString("stream","Stored focal="+stored.optString("FocalLength")+"; original focal="+source.getAttribute("FocalLength")+"; focal millimetres="+source.getAttributeDouble("FocalLength",Double.NaN)+"; stored 35mm="+stored.optString("FocalLengthIn35mmFilm")+"\n");
                finish(Activity.RESULT_OK,output);return;
            }
            List<String> keys = Arrays.asList("Make", "Model", "LensModel", "FNumber", "ExposureTime", "FocalLength", "PhotographicSensitivity", "DateTimeOriginal", "OffsetTimeOriginal");
            List<String> storedFields = new ArrayList<>(), rawFields = new ArrayList<>();
            for (String key : keys) {
                if (!stored.optString(key).trim().isEmpty()) storedFields.add(key);
                String value = source.getAttribute(key);
                if (value != null && !value.trim().isEmpty()) rawFields.add(key);
            }
            String summary = "\nNative MediaStore source=" + direct + "; original requested=" + original
                + "\nDimensions=" + asset.getInt("width") + "x" + asset.getInt("height")
                + "\nStored fields=" + storedFields + "\nSource fields=" + rawFields
                + "\nStored safe tag count=" + stored.length() + "\n";
            Bundle progress = new Bundle(); progress.putString("stream", summary); sendStatus(0, progress);
            require(direct, "Source did not come from native gallery");
            require(original, "Original bytes were not requested");
            for (String key : Arrays.asList("Make", "Model", "FNumber", "ExposureTime", "FocalLength")) {
                require(storedFields.contains(key), "Stored field absent: " + key);
                require(rawFields.contains(key), "Original field absent: " + key);
            }
            for (String key : Arrays.asList("FNumber", "ExposureTime", "FocalLength")) {
                double raw = source.getAttributeDouble(key, Double.NaN), value = rational(stored.getString(key));
                require(Double.isFinite(raw) && Double.isFinite(value) && Math.abs(raw - value) < .000001, "Stored/source mismatch: " + key);
            }
            Iterator<String> iterator = stored.keys();
            while (iterator.hasNext()) { String key = iterator.next(); require(!key.startsWith("GPS") && !key.toLowerCase().contains("serial"), "Private metadata entered model"); }
            output.putString("stream", "PASS: selected real-photo metadata matches original; GPS and serial fields excluded.\n");
            finish(Activity.RESULT_OK, output);
        } catch (Throwable failure) {
            // Do not export exception messages that may contain user file URIs or document data.
            output.putString("stream", "FAIL: " + (failure instanceof AssertionError ? failure.getMessage() : failure.getClass().getSimpleName()) + "\n");
            finish(Activity.RESULT_CANCELED, output);
        }
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private static double rational(String value) {
        String[] parts = value.split("/");
        return parts.length == 2 ? Double.parseDouble(parts[0]) / Double.parseDouble(parts[1]) : Double.parseDouble(value);
    }
    private static File fixtureRecord(Context context) { return new File(context.getCacheDir(), "gallery-probe-fixtures.json"); }
    private static void createFixtures(Context context, Bundle output) throws Exception {
        cleanFixtures(context);
        String album = "文字拼图验证-" + System.currentTimeMillis();
        JSONArray created = new JSONArray();
        try {
            int[] colors = {Color.rgb(70,135,160), Color.rgb(205,125,85), Color.rgb(85,155,95), Color.rgb(150,105,170)};
            for (int index = 0; index < colors.length; index++) {
                ContentValues values = new ContentValues();
                values.put(MediaStore.Images.Media.DISPLAY_NAME, "选图验证-" + (index+1) + ".jpg");
                values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
                values.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/" + album + "/");
                values.put(MediaStore.Images.Media.IS_PENDING, 1);
                Uri uri = context.getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
                require(uri != null, "Fixture creation failed");created.put(uri.toString());
                Bitmap bitmap = Bitmap.createBitmap(128,128,Bitmap.Config.ARGB_8888);bitmap.eraseColor(colors[index]);
                try (OutputStream stream = context.getContentResolver().openOutputStream(uri)) { require(stream != null,"Fixture output missing");bitmap.compress(Bitmap.CompressFormat.JPEG,95,stream); }
                bitmap.recycle();values.clear();values.put(MediaStore.Images.Media.IS_PENDING,0);context.getContentResolver().update(uri,values,null,null);
            }
            Files.write(fixtureRecord(context).toPath(), created.toString().getBytes(StandardCharsets.UTF_8));
            output.putString("stream", "Fixture album=" + album + "\nTemporary fixture count=" + created.length() + "\n");
        } catch (Throwable failure) {
            for (int i=0;i<created.length();i++) context.getContentResolver().delete(Uri.parse(created.getString(i)),null,null);
            throw failure;
        }
    }
    private static void cleanFixtures(Context context) throws Exception {
        File record=fixtureRecord(context);if(!record.isFile())return;
        JSONArray items=new JSONArray(new String(Files.readAllBytes(record.toPath()),StandardCharsets.UTF_8));
        for(int i=0;i<items.length();i++) {
            Uri uri=Uri.parse(items.getString(i));
            try(Cursor cursor=context.getContentResolver().query(uri,new String[]{MediaStore.Images.Media.DISPLAY_NAME,MediaStore.Images.Media.OWNER_PACKAGE_NAME},null,null,null)){
                if(cursor!=null&&cursor.moveToFirst()&&cursor.getString(0).startsWith("选图验证-")&&context.getPackageName().equals(cursor.getString(1)))context.getContentResolver().delete(uri,null,null);
            }
        }
        record.delete();
    }
    private android.view.accessibility.AccessibilityNodeInfo ownControl(Context context,String label) {
        android.view.accessibility.AccessibilityNodeInfo root=getUiAutomation().getRootInActiveWindow();
        if(root==null||!context.getPackageName().contentEquals(root.getPackageName()==null?"":root.getPackageName()))return null;
        return findControl(root,label);
    }
    private android.view.accessibility.AccessibilityNodeInfo findControl(android.view.accessibility.AccessibilityNodeInfo node,String label) {
        if(label.contentEquals(node.getText()==null?"":node.getText())||label.contentEquals(node.getContentDescription()==null?"":node.getContentDescription()))return node;
        for(int i=0;i<node.getChildCount();i++){android.view.accessibility.AccessibilityNodeInfo child=node.getChild(i);if(child!=null){android.view.accessibility.AccessibilityNodeInfo result=findControl(child,label);if(result!=null)return result;}}
        return null;
    }
    private android.view.accessibility.AccessibilityNodeInfo waitControl(Context context,String label) {
        long end=android.os.SystemClock.elapsedRealtime()+10000;
        do{android.view.accessibility.AccessibilityNodeInfo node=ownControl(context,label);if(node!=null)return node;android.os.SystemClock.sleep(100);}while(android.os.SystemClock.elapsedRealtime()<end);
        throw new IllegalStateException("Expected app control did not appear: "+label);
    }
    private void clickOwn(android.view.accessibility.AccessibilityNodeInfo node){
        while(node!=null&&!node.isClickable())node=node.getParent();
        require(node!=null&&node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK),"App action unavailable");
    }
    private void captureExport(Context context,Bundle output)throws Exception {
        require(android.os.Build.VERSION.SDK_INT>=34,"Capture requires Android 14+");
        try(InputStream shell=new ParcelFileDescriptor.AutoCloseInputStream(getUiAutomation().executeShellCommand("am start -n com.parker.imageborder/.MainActivity"))){while(shell.read()!=-1){}}
        String export=context.getString(context.getResources().getIdentifier("ui_export_188896","string",context.getPackageName()));
        String close=context.getString(context.getResources().getIdentifier("ui_close_export_3122cb","string",context.getPackageName()));
        String save=context.getString(context.getResources().getIdentifier("ui_save_current_image_1d33ed","string",context.getPackageName()));
        clickOwn(waitControl(context,export));
        try{
            waitControl(context,close);android.view.accessibility.AccessibilityNodeInfo button=waitControl(context,save);
            android.graphics.Rect bounds=new android.graphics.Rect();button.getBoundsInScreen(bounds);
            require(!bounds.isEmpty()&&bounds.left>=0&&bounds.right<=context.getResources().getDisplayMetrics().widthPixels,"Save control exceeds window");
            android.os.SystemClock.sleep(800);
            java.util.concurrent.CountDownLatch latch=new java.util.concurrent.CountDownLatch(1);
            android.graphics.Bitmap[] image={null};int[] status={-1};
            runOnMainSync(()->{
                android.view.View focused=null;
                for(android.view.View view:android.view.inspector.WindowInspector.getGlobalWindowViews())if(view.isShown()&&view.hasWindowFocus()&&view.getContext().getPackageName().equals(context.getPackageName()))focused=view;
                require(focused!=null,"No focused app window");
                android.view.PixelCopy.request(android.view.PixelCopy.Request.Builder.ofWindow(focused).build(),context.getMainExecutor(),result->{status[0]=result.getStatus();if(status[0]==android.view.PixelCopy.SUCCESS)image[0]=result.getBitmap();latch.countDown();});
            });
            require(latch.await(5,java.util.concurrent.TimeUnit.SECONDS)&&status[0]==android.view.PixelCopy.SUCCESS,"Dialog window capture failed");
            try{File folder=context.getExternalFilesDir("layout-audit");require(folder!=null,"App capture folder unavailable");folder.mkdirs();try(FileOutputStream stream=new FileOutputStream(new File(folder,"08-export.png"))){image[0].compress(Bitmap.CompressFormat.PNG,100,stream);}}finally{if(image[0]!=null)image[0].recycle();}
            output.putString("stream","PASS: minified editor opens export; localized Save action is visible within window; focused app dialog captured. No image was exported.\n");
        }finally{android.view.accessibility.AccessibilityNodeInfo control=ownControl(context,close);if(control!=null)clickOwn(control);}
    }

    private void brandSummary(Context context,Bundle output)throws Exception {
        android.content.pm.PackageManager pm=context.getPackageManager();
        android.content.pm.ApplicationInfo app=pm.getApplicationInfo(context.getPackageName(),0);
        require("灵感边框".contentEquals(pm.getApplicationLabel(app)),"App brand label mismatch");
        android.graphics.drawable.Drawable source=pm.getApplicationIcon(app);
        require(source instanceof android.graphics.drawable.AdaptiveIconDrawable,"Launcher icon is not adaptive");
        android.graphics.drawable.AdaptiveIconDrawable icon=(android.graphics.drawable.AdaptiveIconDrawable)source;
        if(android.os.Build.VERSION.SDK_INT>=33)require(icon.getMonochrome()!=null,"Themed icon layer missing");
        File folder=context.getExternalFilesDir("layout-audit");require(folder!=null,"App capture folder unavailable");folder.mkdirs();
        Bitmap bitmap=Bitmap.createBitmap(216,216,Bitmap.Config.ARGB_8888);icon.setBounds(0,0,216,216);icon.draw(new android.graphics.Canvas(bitmap));
        try(FileOutputStream stream=new FileOutputStream(new File(folder,"launcher-icon.png"))){bitmap.compress(Bitmap.CompressFormat.PNG,100,stream);}finally{bitmap.recycle();}
        if(android.os.Build.VERSION.SDK_INT>=33){
            android.graphics.drawable.Drawable monochrome=icon.getMonochrome().mutate();monochrome.setTint(0xff365348);
            android.graphics.drawable.AdaptiveIconDrawable themed=new android.graphics.drawable.AdaptiveIconDrawable(new android.graphics.drawable.ColorDrawable(0xffdedde5),monochrome);
            Bitmap mono=Bitmap.createBitmap(216,216,Bitmap.Config.ARGB_8888);themed.setBounds(0,0,216,216);themed.draw(new android.graphics.Canvas(mono));
            try(FileOutputStream stream=new FileOutputStream(new File(folder,"launcher-themed.png"))){mono.compress(Bitmap.CompressFormat.PNG,100,stream);}finally{mono.recycle();}
        }
        output.putString("stream","PASS: app label=灵感边框; adaptive color launcher icon and monochrome theme layer present; platform-rendered previews saved. Package and app data unchanged.\n");
    }

    private void captureCurrent(Context context,Bundle output)throws Exception {
        try(InputStream shell=new ParcelFileDescriptor.AutoCloseInputStream(getUiAutomation().executeShellCommand("am start -n com.parker.imageborder/.MainActivity"))){while(shell.read()!=-1){}}
        android.os.SystemClock.sleep(2500);
        String tool=action.substring("capture-".length());
        if("number".equals(tool))tool="frame";
        if("metadata-layout".equals(tool))tool="metadata";
        if(Arrays.asList("frame","text","adjust","metadata").contains(tool)){
            android.view.accessibility.AccessibilityNodeInfo root=getUiAutomation().getRootInActiveWindow();
            require(root!=null&&context.getPackageName().contentEquals(root.getPackageName()),"App is not focused");
            android.view.accessibility.AccessibilityNodeInfo tab=findId(root,"editor-tool:"+tool);require(tab!=null,"Tool tab unavailable");clickOwn(tab);
            android.os.SystemClock.sleep(700);
            if("text".equals(tool)){
                root=getUiAutomation().getRootInActiveWindow();android.view.accessibility.AccessibilityNodeInfo panel=findId(root,"tool-options");
                if(panel!=null)for(int i=0;i<3;i++){panel.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);android.os.SystemClock.sleep(350);}
            }
        }
        if("capture-metadata-layout".equals(action)){
            android.view.accessibility.AccessibilityNodeInfo toggle=findId(getUiAutomation().getRootInActiveWindow(),"metadata-layout-toggle");
            require(toggle!=null,"Metadata layout toggle missing");clickOwn(toggle);android.os.SystemClock.sleep(700);
        }
        boolean number="capture-number".equals(action);
        if(number){
            android.view.accessibility.AccessibilityNodeInfo node=findId(getUiAutomation().getRootInActiveWindow(),"number:四边留白");
            require(node!=null,"Precise value control unavailable");clickOwn(node);android.os.SystemClock.sleep(1200);
        }
        boolean zoom="capture-detail".equals(action);
        if(zoom){
            String label=context.getString(context.getResources().getIdentifier("ui_editing_preview_260fc3","string",context.getPackageName()));
            android.graphics.Rect bounds=new android.graphics.Rect();waitControl(context,label).getBoundsInScreen(bounds);
            require(!bounds.isEmpty(),"Preview has no bounds");
            doubleTap(bounds.centerX(),bounds.centerY());waitControl(context,"200%");android.os.SystemClock.sleep(1200);
        }
        File folder=context.getExternalFilesDir("layout-audit");require(folder!=null,"Capture folder unavailable");folder.mkdirs();
        java.util.concurrent.CountDownLatch latch=new java.util.concurrent.CountDownLatch(1);
        Bitmap[] image={null};int[] status={-1};
        runOnMainSync(()->{
            android.view.View focused=null;
            for(android.view.View view:android.view.inspector.WindowInspector.getGlobalWindowViews())if(view.isShown()&&view.hasWindowFocus()&&view.getContext().getPackageName().equals(context.getPackageName()))focused=view;
            require(focused!=null,"No focused app window");
            android.view.PixelCopy.request(android.view.PixelCopy.Request.Builder.ofWindow(focused).build(),context.getMainExecutor(),result->{status[0]=result.getStatus();if(status[0]==android.view.PixelCopy.SUCCESS)image[0]=result.getBitmap();latch.countDown();});
        });
        require(latch.await(5,java.util.concurrent.TimeUnit.SECONDS)&&status[0]==android.view.PixelCopy.SUCCESS,"App capture failed");
        String filename="capture-metadata-layout".equals(action)?"metadata-layout.png":number?"number.png":Arrays.asList("frame","text","adjust","metadata").contains(tool)?tool+".png":zoom?"detail.png":"current.png";
        try(FileOutputStream stream=new FileOutputStream(new File(folder,filename))){image[0].compress(Bitmap.CompressFormat.PNG,100,stream);}finally{image[0].recycle();}
        if(zoom){String fit=context.getString(context.getResources().getIdentifier("ui_fit_to_screen_47ba68","string",context.getPackageName()));clickOwn(waitControl(context,fit));}
        if(number){String cancel=context.getString(context.getResources().getIdentifier("ui_cancel_4d0b46","string",context.getPackageName()));clickOwn(waitControl(context,cancel));android.os.SystemClock.sleep(700);}
        try(SQLiteDatabase db=SQLiteDatabase.openDatabase(context.getDatabasePath("photo-editor.db").getPath(),null,SQLiteDatabase.OPEN_READONLY);
            Cursor cursor=db.rawQuery("SELECT payload FROM editor_records WHERE kind='draft' ORDER BY updated DESC LIMIT 1",null)){
            require(cursor.moveToFirst(),"No draft");
            JSONObject doc=new JSONObject(cursor.getString(0));
            // Keep the private document on the device; export only typography diagnostics.
            JSONObject info=new JSONObject().put("textStyle",doc.optJSONObject("textStyle")).put("firstParagraph",doc.optString("caption").split("\\n",-1)[0]);
            Files.write(new File(folder,"typography.json").toPath(),info.toString().getBytes(StandardCharsets.UTF_8));
            output.putString("stream","PASS: captured focused app window and local typography diagnostics; document hash="+Integer.toHexString(cursor.getString(0).hashCode())+"\n");
        }
    }
    private android.view.accessibility.AccessibilityNodeInfo findId(android.view.accessibility.AccessibilityNodeInfo node,String id){
        if(node==null)return null;if(id.equals(node.getViewIdResourceName()))return node;
        for(int i=0;i<node.getChildCount();i++){android.view.accessibility.AccessibilityNodeInfo result=findId(node.getChild(i),id);if(result!=null)return result;}return null;
    }
    private void doubleTap(float x,float y){
        for(int tap=0;tap<2;tap++){
            long time=android.os.SystemClock.uptimeMillis();
            android.view.MotionEvent down=android.view.MotionEvent.obtain(time,time,android.view.MotionEvent.ACTION_DOWN,x,y,0);
            android.view.MotionEvent up=android.view.MotionEvent.obtain(time,time+35,android.view.MotionEvent.ACTION_UP,x,y,0);
            try{require(getUiAutomation().injectInputEvent(down,true)&&getUiAutomation().injectInputEvent(up,true),"Preview gesture unavailable");}finally{down.recycle();up.recycle();}
            android.os.SystemClock.sleep(90);
        }
    }

}

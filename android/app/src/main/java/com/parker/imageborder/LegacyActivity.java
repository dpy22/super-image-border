package com.parker.imageborder;

import android.os.Bundle;
import com.getcapacitor.BridgeActivity;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.view.View;

/** Retains the working 1.1 tools and the original WebView origin for preference migration. */
public class LegacyActivity extends BridgeActivity {
    @Override public void onCreate(Bundle state) {
        registerPlugin(NativeImagesPlugin.class);
        super.onCreate(state);
        if (getIntent().getBooleanExtra("migrate", false)) {
            getBridge().getWebView().setVisibility(View.INVISIBLE);
            poll(0);
        }
    }
    private void poll(int attempt) {
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            getBridge().getWebView().evaluateJavascript("document.readyState === 'complete' ? (localStorage.getItem('image-tool-watermark-v1') || '') : 'loading'", value -> {
                if ("\"loading\"".equals(value) && attempt < 12) { poll(attempt + 1); return; }
                setResult(RESULT_OK, new Intent().putExtra("settings", value));
                finish();
            });
        }, 250);
    }
}

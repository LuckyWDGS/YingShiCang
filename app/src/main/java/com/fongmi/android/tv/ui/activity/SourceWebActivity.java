package com.fongmi.android.tv.ui.activity;

import android.app.Activity;
import android.os.Bundle;
import android.view.ViewGroup;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.fongmi.android.tv.server.Server;

/** Local configuration stays in the app so switching to a browser cannot suspend its server. */
public final class SourceWebActivity extends Activity {
    private WebView web;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        Server.get().start();
        String address = Server.get().getAddress(true) + "/";
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setBackgroundColor(0xFF080808);
        TextView back = new TextView(this);
        back.setText("‹  返回添加线路");
        back.setTextColor(0xFFF2F2F2);
        back.setTextSize(16);
        int padding = Math.round(getResources().getDisplayMetrics().density * 14);
        back.setPadding(padding, padding, padding, padding);
        back.setFocusable(true);
        back.setOnClickListener(v -> finish());
        layout.addView(back);
        web = new WebView(this);
        web.setBackgroundColor(0xFF080808);
        web.getSettings().setJavaScriptEnabled(true);
        web.getSettings().setAllowFileAccess(false);
        web.getSettings().setAllowContentAccess(false);
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return !url.startsWith(address);
            }
        });
        layout.addView(web, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        setContentView(layout);
        web.loadUrl(address);
    }
    @Override protected void onDestroy() {
        if (web != null) { web.stopLoading(); web.destroy(); }
        super.onDestroy();
    }
}

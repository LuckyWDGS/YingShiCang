package com.fongmi.android.tv.ui.activity;

import android.os.Bundle;
import android.graphics.Color;
import android.view.View;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import com.fongmi.android.tv.App;
import com.fongmi.android.tv.BuildConfig;
import com.fongmi.android.tv.Updater;
import com.fongmi.android.tv.diagnostics.*;
import com.fongmi.android.tv.maintenance.*;
import com.fongmi.android.tv.maintenance.MaintenanceTasks;
import java.util.*;
import java.util.concurrent.Future;

/** Shared D-pad and touch accessible diagnostics using actual device state. */
public class MaintenanceActivity extends AppCompatActivity {
    private LinearLayout content;
    private TextView result, usage, uploadStatus;
    private EditText updateUrl, mediaUrl;
    private Future<?> task;
    private MediaProbe probe;
    private LogUploader uploader;
    private int operation;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        if ("leanback".equals(BuildConfig.FLAVOR)) setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true); scroll.setBackgroundColor(Color.rgb(16,23,32));
        content = new LinearLayout(this); content.setOrientation(LinearLayout.VERTICAL);
        int padding = dp(24); content.setPadding(padding,padding,padding,padding); scroll.addView(content);
        setContentView(scroll);
        label("日志上传与诊断", 28);
        label("影视仓+ " + BuildConfig.VERSION_NAME + " · " + BuildConfig.FLAVOR, 15);
        usage = label("", 16); refreshUsage();
        label("日志上传", 22);
        label("遇到问题时上传诊断日志和设备信息（型号、系统、解码能力），不含账号、播放地址或片名。",14);
        label("本机设备 ID：" + DeviceId.get(this) + "（反馈问题时一并告诉开发者）",16);
        // The receiver is built into the release; users never type or change it.
        button("上传日志", v -> startUpload());
        uploadStatus = label("仅在服务器确认保存后清除该批日志，上传期间的新日志保留；未配置服务时不会上传。应用崩溃后，下次启动会自动上传一次。",14);
        label("播放诊断", 22);
        Switch quality = new Switch(this);
        quality.setText("最高画质优先（默认开启，关闭后自动适应网速）");
        quality.setTextColor(Color.WHITE);
        quality.setChecked(com.fongmi.android.tv.setting.PlayerSetting.isHighestQualityPreferred());
        quality.setOnCheckedChangeListener((button, checked) -> {
            com.fongmi.android.tv.setting.PlayerSetting.putHighestQualityPreferred(checked);
            show("画质策略已保存，下次打开播放器生效；固定码率视频无法自动降档");
        });
        content.addView(quality);
        PlaybackHealth.Summary summary = PlaybackHealth.lastSummary();
        label(summary == null ? "暂无播放样本。实际播放后可查看就绪与缓冲记录。"
                : "就绪耗时：" + summary.startupMs() + " ms · 播放：" + summary.playedMs()/1000 + " s · 重缓冲：" + summary.bufferedMs()/1000 + " s · " + summary.buffers() + " 次\n错误：" + summary.error(), 16);
        mediaUrl = input("可选：输入 HTTP/HTTPS 媒体地址", "");
        button("测试上次播放的线路", v -> startProbe(true));
        button("测试输入的媒体地址", v -> startProbe(false));
        label("快速测试最多约 9MB / 30 秒。仅显示短时媒体吞吐，不代表解码成功或长期稳定；请退出播放器后测试。",14);
        label("应用更新", 22);
        label("默认检查 GitHub Releases 最新正式版，自动选择适合设备的安装包。",14);
        updateUrl = input("可选：自定义 HTTPS 更新清单（留空使用 GitHub）", ServiceSettings.customManifestUrl());
        button("保存更新地址", v -> saveUpdateUrl());
        button("检查更新", v -> Updater.create().force().start(this));
        button("取消当前测速／上传", v -> { cancel(); show("操作已取消；未确认的日志保留"); });
        result = label("就绪",16);
        button("返回", v -> finish());
    }
    private int dp(int n) { return Math.round(n * getResources().getDisplayMetrics().density); }
    private TextView label(String text, int size) {
        TextView view = new TextView(this); view.setText(text); view.setTextSize(size);
        view.setTextColor(Color.rgb(228,239,242)); view.setPadding(0,dp(10),0,dp(8)); content.addView(view); return view;
    }
    private EditText input(String hint, String text) {
        EditText view = new EditText(this); view.setHint(hint); view.setText(text); view.setTextColor(Color.WHITE);
        view.setHintTextColor(Color.LTGRAY); view.setSingleLine(true); view.setInputType(17); content.addView(view); return view;
    }
    private void button(String text, View.OnClickListener action) {
        Button view = new Button(this); view.setText(text); view.setAllCaps(false);
        view.setTextColor(Color.WHITE);
        view.setBackgroundTintList(new android.content.res.ColorStateList(
                new int[][]{new int[]{android.R.attr.state_focused},new int[]{}},
                new int[]{Color.rgb(30,104,91),Color.rgb(37,51,66)}));
        view.setFocusable(true); view.setOnClickListener(action); content.addView(view);
    }
    private void show(String message) {
        if (!isFinishing() && !isDestroyed()) { result.setText(message); refreshUsage(); }
    }
    private void showUpload(String message) {
        show(message);
        if (!isFinishing() && !isDestroyed()) uploadStatus.setText(message);
    }
    private void refreshUsage() { usage.setText(String.format(Locale.ROOT,"日志占用 %.3f MB / 10 MB · 满后自动淘汰最旧记录",DiagnosticLog.size(this)/1_000_000.0)); }
    private void saveUpdateUrl() {
        String value = updateUrl.getText().toString().trim();
        try {
            if (!value.isEmpty()) MaintenanceHttp.validate(value);
            ServiceSettings.manifestUrl(value);
            show(value.isEmpty() ? "已恢复 GitHub Releases 更新" : "已保存服务地址");
        } catch (Exception error) { show(error.getMessage()); }
    }
    private void cancel() {
        operation++;
        if (probe != null) probe.cancel();
        if (uploader != null) uploader.cancel();
        if (task != null) task.cancel(true);
    }
    private void startProbe(boolean previous) {
        if (PlaybackHealth.active()) { show("请先退出播放器，避免测速争抢播放带宽"); return; }
        PlaybackHealth.Stream stream = previous ? PlaybackHealth.lastStream() : new PlaybackHealth.Stream(mediaUrl.getText().toString().trim(),Map.of());
        if (stream == null || stream.url().isEmpty()) { show("暂无线路，请先播放视频或输入真实媒体地址"); return; }
        cancel(); int token = operation; probe = new MediaProbe(); MediaProbe current = probe; show("正在采样真实媒体数据…");
        task = MaintenanceTasks.submit(() -> {
            try {
                MediaProbe.Result data = current.run(stream.url(),stream.headers());
                App.post(() -> { if (token==operation) show(String.format(Locale.ROOT,"%s：%.2f Mbps（%.2f MB/s）\n读取 %.2f MB，用时 %d ms，响应 %d ms\n仅短时吞吐样本，解码与长期稳定性未验证",data.kind(),data.mbps(),data.mbps()/8,data.bytes()/1_000_000.0,data.elapsedMs(),data.firstResponseMs())); });
            } catch (Exception e) { App.post(() -> { if (token==operation) show("测试未完成："+e.getMessage()); }); }
        });
    }
    private void startUpload() {
        String endpoint = ServiceSettings.logsUrl();
        if (endpoint.isEmpty()) { showUpload("此版本未内置日志服务地址，无法上传。"); return; }
        cancel(); int token = operation; uploader = new LogUploader(); LogUploader current = uploader; showUpload("正在上传日志，等待服务器保存回执…");
        android.content.Context app = getApplicationContext();
        task = MaintenanceTasks.submit(() -> {
            try { String receipt=current.upload(DiagnosticLog.store(),endpoint,DeviceReport.encode(app),DeviceId.get(app)); App.post(() -> { if(token==operation)showUpload(receipt); }); }
            catch(Exception e) { App.post(() -> { if(token==operation)showUpload("未完成上传／清理："+e.getMessage()); }); }
        });
    }
    @Override protected void onDestroy() { cancel(); super.onDestroy(); }
}

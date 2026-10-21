package com.fongmi.android.tv.ui.dialog;

import android.app.Dialog;
import android.os.Bundle;
import android.content.Intent;
import android.view.View;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatDialog;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.FragmentActivity;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.Config;
import com.fongmi.android.tv.databinding.DialogSourceConfigBinding;
import com.fongmi.android.tv.impl.Callback;
import com.fongmi.android.tv.server.Server;
import com.fongmi.android.tv.source.SourceManager;
import com.fongmi.android.tv.utils.QRCode;
import com.fongmi.android.tv.utils.ResUtil;
import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

/** Compact add surface: a newly added line becomes active right away; Lines still switches between saved ones. */
public class SourceConfigDialog extends DialogFragment {
    private DialogSourceConfigBinding binding;
    private boolean saving;
    private String shownAddress;
    private final android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable refreshAddress = new Runnable() {
        @Override public void run() { updateAddress(); handler.postDelayed(this, 2000); }
    };
    public static void showOn(FragmentActivity activity) {
        if (activity.getSupportFragmentManager().findFragmentByTag("source-config") == null)
            new SourceConfigDialog().show(activity.getSupportFragmentManager(), "source-config");
    }
    @NonNull @Override public Dialog onCreateDialog(Bundle state) {
        AppCompatDialog dialog = new AppCompatDialog(requireContext());
        binding = DialogSourceConfigBinding.inflate(getLayoutInflater());
        dialog.setContentView(binding.getRoot());
        Server.get().start();
        updateAddress();
        binding.openLocal.setOnClickListener(v -> startActivity(new Intent(requireContext(), com.fongmi.android.tv.ui.activity.SourceWebActivity.class)));
        binding.close.setOnClickListener(v -> dismiss());
        binding.save.setOnClickListener(v -> save());
        binding.url.setOnEditorActionListener((v,id,event) -> { save(); return true; });
        return dialog;
    }
    private void updateAddress() {
        if (binding == null) return;
        String address = Server.get().getLanAddress();
        if (address.equals(shownAddress)) return;
        shownAddress = address;
        binding.address.setText(address.isEmpty() ? "未连接局域网，可点右侧本机打开" : address);
        binding.code.setVisibility(address.isEmpty() ? View.INVISIBLE : View.VISIBLE);
        if (!address.isEmpty()) binding.code.setImageBitmap(QRCode.getStandardBitmap(address, 348));
    }
    private void save() {
        if (saving) return;
        if (SourceManager.busy()) { status("线路切换中，请稍候",true); return; }
        final String address;
        try { address = com.fongmi.android.tv.source.SourceInput.url(binding.url.getText().toString()); }
        catch (IllegalArgumentException error) { status(error.getMessage(),true); return; }
        saving = true; binding.save.setEnabled(false); binding.save.setText("添加中…");
        status("正在识别线路名称…",false);
        com.fongmi.android.tv.utils.Task.execute(() -> {
            try {
                Config item = SourceManager.save(null,address);
                com.fongmi.android.tv.App.post(() -> activate(item));
            } catch (Exception error) {
                com.fongmi.android.tv.App.post(() -> { saving = false; if(isAdded()) { status("添加失败，请重试",true); idle(); } });
            }
        });
    }
    // A newly added line is used right away; a failed switch rolls back and leaves it in 线路.
    private void activate(Config item) {
        if (!isAdded() || binding == null) { saving = false; return; }
        if (item.getUrl().equals(VodConfig.getUrl())) { saving = false; status("正在使用「" + item.getDesc() + "」",false); idle(); return; }
        status("已添加，正在切换到「" + item.getDesc() + "」…",false);
        SourceManager.activate(item,new Callback(){
            @Override public void success(){ saving = false; if(isAdded()){ status("已切换至「"+VodConfig.getDesc()+"」",false); idle(); handler.postDelayed(() -> { if(isAdded()) dismissAllowingStateLoss(); }, 1200); } }
            @Override public void error(String message){ saving = false; if(isAdded()){ status("已添加，但切换失败："+message,true); idle(); } }
        });
    }
    private void idle() {
        if (binding != null) { binding.save.setEnabled(true); binding.save.setText("添加"); }
    }
    private void status(String text, boolean error) {
        if (binding == null) return;
        binding.status.setTextColor(error ? 0xFFF2B4AE : ResUtil.getColor(com.fongmi.android.tv.R.color.tv_theme_text_secondary));
        binding.status.setText(text);
    }
    @Subscribe(threadMode=ThreadMode.MAIN) public void onSourceSaved(SourceManager.Saved event) {
        // This dialog reports its own save+switch progress; only show saves made elsewhere (e.g. the LAN page).
        if (!saving) status("已添加「" + event.name() + "」",false);
    }
    @Override public void onStart() {
        super.onStart();
        getDialog().getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        getDialog().getWindow().setDimAmount(0.72f);
        getDialog().getWindow().setLayout((int)(ResUtil.getScreenWidth()*.64f),-2);
        EventBus.getDefault().register(this);
        handler.post(refreshAddress);
    }
    @Override public void onStop() { handler.removeCallbacks(refreshAddress); EventBus.getDefault().unregister(this);super.onStop(); }
    @Override public void onDestroyView() { binding = null; super.onDestroyView(); }
}

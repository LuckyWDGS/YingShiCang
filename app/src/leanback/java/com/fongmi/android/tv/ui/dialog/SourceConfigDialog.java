package com.fongmi.android.tv.ui.dialog;

import android.app.Dialog;
import android.os.Bundle;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatDialog;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.FragmentActivity;
import com.fongmi.android.tv.databinding.DialogSourceConfigBinding;
import com.fongmi.android.tv.server.Server;
import com.fongmi.android.tv.source.SourceManager;
import com.fongmi.android.tv.source.SourcePairing;
import com.fongmi.android.tv.utils.QRCode;
import com.fongmi.android.tv.utils.ResUtil;
import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

/** Compact add-only surface; choosing the active configuration belongs to Lines. */
public class SourceConfigDialog extends DialogFragment {
    private DialogSourceConfigBinding binding;
    private boolean saving;
    public static void showOn(FragmentActivity activity) {
        if (activity.getSupportFragmentManager().findFragmentByTag("source-config") == null)
            new SourceConfigDialog().show(activity.getSupportFragmentManager(), "source-config");
    }
    @NonNull @Override public Dialog onCreateDialog(Bundle state) {
        AppCompatDialog dialog = new AppCompatDialog(requireContext());
        binding = DialogSourceConfigBinding.inflate(getLayoutInflater());
        dialog.setContentView(binding.getRoot());
        Server.get().start();
        SourcePairing.INSTANCE.open();
        String address = Server.get().getAddress() + "/sources.html";
        binding.address.setText(address);
        binding.pairCode.setText("配对码\n" + SourcePairing.INSTANCE.code());
        binding.code.setImageBitmap(QRCode.getStandardBitmap(address + "#token=" + SourcePairing.INSTANCE.token(), 348));
        binding.close.setOnClickListener(v -> dismiss());
        binding.save.setOnClickListener(v -> save());
        binding.url.setOnEditorActionListener((v,id,event) -> { save(); return true; });
        return dialog;
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
            try { SourceManager.save(null,address); }
            catch (Exception error) { com.fongmi.android.tv.App.post(() -> { if(isAdded()) status("添加失败，请重试",true); }); }
            finally { com.fongmi.android.tv.App.post(() -> {
                saving = false;
                if (isAdded()) { binding.save.setEnabled(true);binding.save.setText("添加"); }
            }); }
        });
    }
    private void status(String text, boolean error) {
        binding.status.setTextColor(error ? 0xFFF2B4AE : 0xFFA2DFCD);
        binding.status.setText(text);
    }
    @Subscribe(threadMode=ThreadMode.MAIN) public void onSourceSaved(SourceManager.Saved event) {
        status("已添加「" + event.name() + "」",false);
    }
    @Override public void onStart() {
        super.onStart();
        getDialog().getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        getDialog().getWindow().setLayout((int)(ResUtil.getScreenWidth()*.64f),-2);
        EventBus.getDefault().register(this);
    }
    @Override public void onStop() { EventBus.getDefault().unregister(this);super.onStop(); }
}

package com.fongmi.android.tv.ui.dialog;

import android.content.Intent;
import android.net.Uri;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.Danmaku;
import com.fongmi.android.tv.databinding.DialogDanmakuBinding;
import com.fongmi.android.tv.player.PlayerManager;
import com.fongmi.android.tv.setting.DanmakuSetting;
import com.fongmi.android.tv.ui.adapter.DanmakuAdapter;
import com.fongmi.android.tv.ui.custom.SpaceItemDecoration;
import com.fongmi.android.tv.utils.FileChooser;
import com.fongmi.android.tv.utils.FileUtil;

public final class DanmakuDialog extends BaseBottomSheetDialog implements DanmakuAdapter.OnClickListener {

    private final DanmakuAdapter adapter;
    private DialogDanmakuBinding binding;
    private PlayerManager player;

    public DanmakuDialog() {
        this.adapter = new DanmakuAdapter(this);
    }

    public static DanmakuDialog create() {
        return new DanmakuDialog();
    }

    public DanmakuDialog player(PlayerManager player) {
        this.player = player;
        return this;
    }

    public void show(FragmentActivity activity) {
        for (Fragment f : activity.getSupportFragmentManager().getFragments()) if (f instanceof DanmakuDialog) return;
        show(activity.getSupportFragmentManager(), null);
    }

    @Override
    protected ViewBinding getBinding(@NonNull LayoutInflater inflater, @Nullable ViewGroup container) {
        return binding = DialogDanmakuBinding.inflate(inflater, container, false);
    }

    @Override
    protected void initView() {
        binding.recycler.setItemAnimator(null);
        binding.recycler.setHasFixedSize(true);
        binding.recycler.setAdapter(adapter.addAll(player.getDanmakus()));
        binding.recycler.addItemDecoration(new SpaceItemDecoration(1, 16));
        binding.recycler.post(() -> binding.recycler.scrollToPosition(adapter.getSelected()));
        binding.recycler.setVisibility(adapter.getItemCount() == 0 ? View.GONE : View.VISIBLE);
        binding.search.setVisibility(View.VISIBLE);
        binding.emptyHint.setVisibility(adapter.getItemCount() == 0 ? View.VISIBLE : View.GONE);
        binding.enabledSwitch.setChecked(DanmakuSetting.isLoad() && DanmakuSetting.isShow());
        binding.enabledSwitch.setOnCheckedChangeListener((button, checked) -> player.enableDanmaku(checked));
    }

    @Override
    protected void initEvent() {
        binding.search.setOnClickListener(this::onSearch);
        binding.choose.setOnClickListener(this::onChoose);
        binding.setting.setOnClickListener(this::onSetting);
    }

    private void onSearch(View view) {
        FragmentActivity activity = requireActivity();
        dismissNow();
        DanmakuSearchDialog.create().player(player).show(activity);
    }

    private void onChoose(View view) {
        FileChooser.from(launcher).show(new String[]{"text/*", "application/xml", "application/json", "application/octet-stream"});
        player.pause();
    }

    private void onSetting(View view) {
        FragmentActivity activity = requireActivity();
        dismissNow();
        DanmakuSettingDialog.create().player(player).show(activity);
    }

    @Override
    public void onItemClick(Danmaku item) {
        player.enableDanmaku(true);
        player.toggleDanmaku(item);
        dismiss();
    }

    private final ActivityResultLauncher<Intent> launcher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> FileChooser.getUri(result, this::setDanmaku));

    private void setDanmaku(Uri uri) {
        if (!isAdded()) return;
        player.enableDanmaku(true);
        player.setDanmaku(Danmaku.from(FileUtil.getDisplayName(uri), uri.toString()));
        dismiss();
    }
}

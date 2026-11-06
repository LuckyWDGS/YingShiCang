package com.fongmi.android.tv.ui.activity;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.BuildConfig;
import com.fongmi.android.tv.cloudlogin.DriveLogins;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.Updater;
import com.fongmi.android.tv.api.config.LiveConfig;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.api.config.WallConfig;
import com.fongmi.android.tv.bean.Config;
import com.fongmi.android.tv.bean.Live;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.databinding.ActivitySettingBinding;
import com.fongmi.android.tv.db.BackupManager;
import com.fongmi.android.tv.event.ConfigEvent;
import com.fongmi.android.tv.event.RefreshEvent;
import com.fongmi.android.tv.impl.Callback;
import com.fongmi.android.tv.impl.ConfigListener;
import com.fongmi.android.tv.impl.LiveListener;
import com.fongmi.android.tv.impl.SiteListener;
import com.fongmi.android.tv.maintenance.UpdateNotice;
import com.fongmi.android.tv.setting.PlayerSetting;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.ui.base.BaseActivity;
import com.fongmi.android.tv.ui.dialog.ConfigDialog;
import com.fongmi.android.tv.ui.dialog.DohDialog;
import com.fongmi.android.tv.ui.dialog.HistoryDialog;
import com.fongmi.android.tv.ui.dialog.LiveDialog;
import com.fongmi.android.tv.ui.dialog.RestoreDialog;
import com.fongmi.android.tv.ui.dialog.SiteDialog;
import com.fongmi.android.tv.ui.dialog.SourceLineDialog;
import com.fongmi.android.tv.utils.FileUtil;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.PermissionUtil;
import com.fongmi.android.tv.utils.ResUtil;
import com.github.catvod.bean.Doh;
import com.github.catvod.net.OkHttp;

import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.util.ArrayList;
import java.util.List;

public class SettingActivity extends BaseActivity implements ConfigListener, SiteListener, LiveListener, DohDialog.Listener {

    private ActivitySettingBinding mBinding;
    private String[] size;
    private int section;
    private View[] sectionNav, sectionGroups;
    private View[][] sectionRows;

    private static final int[] SECTION_TITLES = {R.string.setting_home_sources, R.string.setting_home_playback,
            R.string.setting_home_appearance, R.string.setting_home_storage, R.string.setting_home_about};
    private static final int[] SECTION_HINTS = {R.string.setting_home_sources_hint, R.string.setting_home_playback_hint,
            R.string.setting_home_appearance_hint, R.string.setting_home_storage_hint, R.string.setting_home_about_hint};

    public static void start(Activity activity) {
        activity.startActivity(new Intent(activity, SettingActivity.class));
    }

    private int getDohIndex() {
        return Math.max(0, VodConfig.get().getDoh().indexOf(Doh.objectFrom(Setting.getDoh())));
    }

    private String[] getDohList() {
        List<String> list = new ArrayList<>();
        for (Doh item : VodConfig.get().getDoh()) list.add(item.getName());
        return list.toArray(new String[0]);
    }

    @Override
    protected ViewBinding getBinding() {
        return mBinding = ActivitySettingBinding.inflate(getLayoutInflater());
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        initSections(savedInstanceState);
        fitShortScreen();
        setUpdateText();
        setCacheText();
        setOtherText();
        setConfigText();
    }

    private void fitShortScreen() {
        // The TV UI also runs on wide phones; keep all five groups and the key hint visible.
        if (ResUtil.getScreenHeight(this) >= ResUtil.dp2px(500)) return;
        int padding = ResUtil.dp2px(20);
        mBinding.getRoot().setPadding(padding, padding, padding, padding);
        mBinding.settingsTitle.setTextSize(26);
        ViewGroup.MarginLayoutParams subtitle = (ViewGroup.MarginLayoutParams) mBinding.settingsSubtitle.getLayoutParams();
        subtitle.bottomMargin = ResUtil.dp2px(16);
        mBinding.settingsSubtitle.setLayoutParams(subtitle);
        for (View nav : sectionNav) {
            ViewGroup.MarginLayoutParams params = (ViewGroup.MarginLayoutParams) nav.getLayoutParams();
            params.height = ResUtil.dp2px(40);
            params.bottomMargin = ResUtil.dp2px(6);
            nav.setLayoutParams(params);
        }
    }

    private void initSections(Bundle saved) {
        sectionNav = new View[]{mBinding.navSources, mBinding.navPlayback, mBinding.navAppearance, mBinding.navStorage, mBinding.navAbout};
        sectionGroups = new View[]{mBinding.groupSources, mBinding.groupPlayback, mBinding.groupAppearance, mBinding.groupStorage, mBinding.groupAbout};
        sectionRows = new View[][]{
                {mBinding.vod, mBinding.lines, mBinding.vodHome, mBinding.live, mBinding.liveHome, mBinding.drive},
                {mBinding.player, mBinding.danmaku, mBinding.chapterMarker, mBinding.follow, mBinding.incognito},
                {mBinding.size, mBinding.wall, mBinding.wallDefault, mBinding.wallRefresh},
                {mBinding.doh, mBinding.cache, mBinding.backup, mBinding.restore},
                {mBinding.update, mBinding.version}};
        for (int i = 0; i < sectionNav.length; i++) {
            final int group = i;
            View nav = sectionNav[i];
            nav.setOnFocusChangeListener((view, focused) -> { if (focused) showSection(group); });
            nav.setOnClickListener(view -> {
                view.requestFocusFromTouch();
                showSection(group);
                sectionRows[group][0].requestFocus();
            });
            nav.setNextFocusRightId(sectionRows[i][0].getId());
            nav.setNextFocusLeftId(nav.getId());
            nav.setNextFocusUpId(sectionNav[Math.max(0, i - 1)].getId());
            nav.setNextFocusDownId(sectionNav[Math.min(sectionNav.length - 1, i + 1)].getId());
            for (int row = 0; row < sectionRows[i].length; row++) {
                View item = sectionRows[i][row];
                item.setNextFocusLeftId(nav.getId());
                item.setNextFocusRightId(item.getId());
                item.setNextFocusUpId(sectionRows[i][Math.max(0, row - 1)].getId());
                item.setNextFocusDownId(sectionRows[i][Math.min(sectionRows[i].length - 1, row + 1)].getId());
            }
        }
        showSection(saved == null ? 0 : Math.max(0, Math.min(sectionNav.length - 1, saved.getInt("settings_section"))));
        sectionRows[section][0].requestFocus();
    }

    private void showSection(int index) {
        boolean changed = section != index;
        section = index;
        for (int i = 0; i < sectionGroups.length; i++) {
            sectionGroups[i].setVisibility(i == section ? View.VISIBLE : View.GONE);
            sectionNav[i].setSelected(i == section);
        }
        mBinding.sectionTitle.setText(SECTION_TITLES[index]);
        mBinding.sectionHint.setText(SECTION_HINTS[index]);
        if (changed) mBinding.sectionScroll.scrollTo(0, 0);
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        state.putInt("settings_section", section);
        super.onSaveInstanceState(state);
    }

    private void value(TextView view, String text, int empty) {
        view.setText(text == null || text.isBlank() ? getString(empty) : text);
    }

    private void setConfigText() {
        value(mBinding.vodUrl, VodConfig.getDesc(), R.string.setting_home_unset);
        value(mBinding.liveUrl, LiveConfig.getDesc(), R.string.setting_home_unset);
        value(mBinding.wallUrl, WallConfig.getDesc(), R.string.setting_home_unset);
        value(mBinding.vodHomeText, VodConfig.get().getHome().getName(), R.string.setting_home_unselected);
        value(mBinding.liveHomeText, LiveConfig.get().getHome().getName(), R.string.setting_home_unselected);
        value(mBinding.linesText, VodConfig.get().getConfig().getName(), R.string.setting_home_unselected);
    }

    private void setOtherText() {
        String[] doh = getDohList();
        value(mBinding.dohText, doh.length == 0 ? "" : doh[Math.min(getDohIndex(), doh.length - 1)], R.string.setting_home_unset);
        mBinding.incognitoText.setText(Setting.getSwitch(Setting.isIncognito()));
        mBinding.followText.setText(Setting.getSwitch(Setting.isFollow()));
        mBinding.chapterMarkerText.setText(Setting.getSwitch(PlayerSetting.isChapterMarker()));
        size = ResUtil.getStringArray(R.array.select_size);
        mBinding.sizeText.setText(size[Math.max(0, Math.min(size.length - 1, PlayerSetting.getSize()))]);
    }

    private void setUpdateText() {
        String version = UpdateNotice.available();
        mBinding.updateDot.setVisibility(version.isEmpty() ? View.GONE : View.VISIBLE);
        mBinding.versionText.setText(version.isEmpty() ? BuildConfig.VERSION_NAME : "新版本 " + version);
    }

    @Override
    protected void onResume() {
        super.onResume();
        UpdateNotice.observe(this::setUpdateText);
        setUpdateText();
        setDriveText();
        setOtherText();
    }

    private void setDriveText() {
        mBinding.driveText.setText(DriveLogins.summary(DriveLogins.statuses()));
    }

    @Override
    protected void onPause() {
        UpdateNotice.observe(null);
        super.onPause();
    }

    private void setCacheText() {
        mBinding.cacheText.setText(R.string.setting_home_loading);
        FileUtil.getCacheSize(new Callback() {
            @Override
            public void success(String result) {
                mBinding.cacheText.setText(result);
            }
        });
    }

    @Override
    protected void initEvent() {
        mBinding.vod.setOnClickListener(this::onVod);
        mBinding.lines.setOnClickListener(this::onLines);
        mBinding.doh.setOnClickListener(this::setDoh);
        mBinding.live.setOnClickListener(this::onLive);
        mBinding.wall.setOnClickListener(this::onWall);
        mBinding.size.setOnClickListener(this::setSize);
        mBinding.cache.setOnClickListener(this::onCache);
        mBinding.backup.setOnClickListener(this::onBackup);
        mBinding.player.setOnClickListener(this::onPlayer);
        mBinding.danmaku.setOnClickListener(this::onDanmaku);
        mBinding.restore.setOnClickListener(this::onRestore);
        mBinding.version.setOnClickListener(this::onVersion);
        mBinding.update.setOnClickListener(this::onUpdate);
        mBinding.follow.setOnClickListener(this::setFollow);
        mBinding.vod.setOnLongClickListener(this::onVodEdit);
        mBinding.vodHome.setOnClickListener(this::onVodHome);
        mBinding.live.setOnLongClickListener(this::onLiveEdit);
        mBinding.liveHome.setOnClickListener(this::onLiveHome);
        mBinding.wall.setOnLongClickListener(this::onWallEdit);
        mBinding.incognito.setOnClickListener(this::setIncognito);
        mBinding.chapterMarker.setOnClickListener(this::setChapterMarker);
        mBinding.wallDefault.setOnClickListener(this::setWallDefault);
        mBinding.wallRefresh.setOnClickListener(this::setWallRefresh);
        mBinding.wallRefresh.setOnLongClickListener(this::onWallHistory);
    }

    @Override
    public void setConfig(Config config) {
        if (config.getUrl().startsWith("file")) {
            PermissionUtil.requestFile(this, allGranted -> load(config));
        } else {
            load(config);
        }
    }

    private void load(Config config) {
        switch (config.getType()) {
            case 0:
                VodConfig.load(config, getCallback());
                break;
            case 1:
                LiveConfig.load(config, getCallback());
                break;
            case 2:
                Setting.putWall(0);
                WallConfig.load(config, getCallback());
                break;
        }
    }

    private Callback getCallback() {
        return new Callback() {
            @Override
            public void start() {
                Notify.progress(getActivity());
            }

            @Override
            public void success() {
                Notify.dismiss();
                setCacheText();
            }

            @Override
            public void error(String msg) {
                Notify.dismiss();
                Notify.show(msg);
            }
        };
    }

    @Override
    public void setSite(Site item) {
        VodConfig.get().setHome(item);
        mBinding.vodHomeText.setText(item.getName());
    }

    @Override
    public void setLive(Live item) {
        LiveConfig.get().setHome(item);
        mBinding.liveHomeText.setText(item.getName());
    }

    private void onVod(View view) {
        ConfigDialog.create().vod().show(this);
    }

    private void onLive(View view) {
        ConfigDialog.create().live().show(this);
    }

    private void onLines(View view) {
        SourceLineDialog.showOn(this);
    }

    private void onWall(View view) {
        ConfigDialog.create().wall().show(this);
    }

    private boolean onVodEdit(View view) {
        ConfigDialog.create().vod().edit().show(this);
        return true;
    }

    private boolean onLiveEdit(View view) {
        ConfigDialog.create().live().edit().show(this);
        return true;
    }

    private boolean onWallEdit(View view) {
        ConfigDialog.create().wall().edit().show(this);
        return true;
    }

    private void onVodHome(View view) {
        SiteDialog.create().action().show(this);
    }

    private void onLiveHome(View view) {
        LiveDialog.create().action().show(this);
    }

    private void onPlayer(View view) {
        SettingPlayerActivity.start(this);
    }

    private void onDanmaku(View view) {
        SettingDanmakuActivity.start(this);
    }

    private void onVersion(View view) {
        startActivity(new Intent(this, MaintenanceActivity.class));
    }

    private void onUpdate(View view) {
        Updater.create().force().start(this);
    }

    private void setWallDefault(View view) {
        Setting.putWall(Setting.getWall() == 4 ? 1 : Setting.getWall() + 1);
        Setting.putWallType(0);
        ConfigEvent.wall();
    }

    private void setWallRefresh(View view) {
        Setting.putWall(0);
        WallConfig.get().load(getCallback());
    }

    private boolean onWallHistory(View view) {
        HistoryDialog.create().wall().show(this);
        return true;
    }

    private void setChapterMarker(View view) {
        PlayerSetting.putChapterMarker(!PlayerSetting.isChapterMarker());
        mBinding.chapterMarkerText.setText(Setting.getSwitch(PlayerSetting.isChapterMarker()));
    }

    private void setFollow(View view) {
        Setting.putFollow(!Setting.isFollow());
        mBinding.followText.setText(Setting.getSwitch(Setting.isFollow()));
    }

    private void setIncognito(View view) {
        Setting.putIncognito(!Setting.isIncognito());
        mBinding.incognitoText.setText(Setting.getSwitch(Setting.isIncognito()));
    }

    private void setSize(View view) {
        int index = (PlayerSetting.getSize() + 1) % size.length;
        mBinding.sizeText.setText(size[index]);
        PlayerSetting.putSize(index);
        RefreshEvent.size();
    }

    private void setDoh(View view) {
        DohDialog.create().index(getDohIndex()).show(this);
    }

    @Override
    public void setDoh(Doh doh) {
        OkHttp.dns().setDoh(doh);
        Setting.putDoh(doh.toString());
        mBinding.dohText.setText(doh.getName());
    }

    private void onCache(View view) {
        FileUtil.clearCache(new Callback() {
            @Override
            public void success() {
                setCacheText();
            }
        });
    }

    private void onBackup(View view) {
        PermissionUtil.requestFile(this, allGranted -> BackupManager.backup(new Callback() {
            @Override
            public void success() {
                Notify.show(R.string.backup_success);
            }

            @Override
            public void error() {
                Notify.show(R.string.backup_fail);
            }
        }));
    }

    private void onRestore(View view) {
        PermissionUtil.requestFile(this, allGranted -> RestoreDialog.create().callback(new Callback() {
            @Override
            public void success() {
                Notify.show(R.string.restore_success);
                setOtherText();
                initConfig();
            }

            @Override
            public void error() {
                Notify.show(R.string.restore_fail);
            }
        }).show(this));
    }

    private void initConfig() {
        VodConfig.get().init().load(getCallback());
        LiveConfig.get().init().load();
        WallConfig.get().init().load();
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onConfigEvent(ConfigEvent event) {
        if (event.type() != ConfigEvent.Type.COMMON) return;
        setConfigText();
    }

}

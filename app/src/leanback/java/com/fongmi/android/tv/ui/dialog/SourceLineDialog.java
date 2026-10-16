package com.fongmi.android.tv.ui.dialog;

import android.app.Dialog;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatDialog;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.FragmentActivity;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.Config;
import com.fongmi.android.tv.impl.Callback;
import com.fongmi.android.tv.source.SourceManager;
import com.fongmi.android.tv.utils.ResUtil;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

public class SourceLineDialog extends DialogFragment {
    private LinearLayout rows;
    private ScrollView scroll;
    private TextView status, count, add, close;
    private boolean busy;
    public static void showOn(FragmentActivity activity) {
        if (activity.getSupportFragmentManager().findFragmentByTag("source-lines") == null)
            new SourceLineDialog().show(activity.getSupportFragmentManager(), "source-lines");
    }
    private int dp(int value) { return ResUtil.dp2px(value); }
    private TextView text(String title, int size, int color) {
        TextView view = new TextView(requireContext());view.setText(title);view.setTextSize(size);view.setTextColor(color);return view;
    }
    private TextView button(String title, int size) {
        TextView view=text(title,size,ResUtil.getColor(com.fongmi.android.tv.R.color.tv_theme_text));view.setGravity(Gravity.CENTER);view.setFocusable(true);view.setFocusableInTouchMode(false);view.setBackgroundResource(R.drawable.source_field);return view;
    }
    @NonNull @Override public Dialog onCreateDialog(Bundle state) {
        AppCompatDialog dialog=new AppCompatDialog(requireContext());
        LinearLayout root=new LinearLayout(requireContext());root.setOrientation(LinearLayout.VERTICAL);root.setPadding(dp(28),dp(24),dp(28),dp(28));root.setBackgroundResource(R.drawable.source_panel);
        LinearLayout header=new LinearLayout(requireContext());header.setGravity(Gravity.CENTER_VERTICAL);root.addView(header,new LinearLayout.LayoutParams(-1,dp(34)));
        header.addView(text("线路",22,ResUtil.getColor(com.fongmi.android.tv.R.color.tv_theme_text)));
        count=text("",12,ResUtil.getColor(com.fongmi.android.tv.R.color.tv_theme_text_secondary));LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(0,-2,1);cp.leftMargin=dp(10);header.addView(count,cp);
        add=button("＋ 添加",14);header.addView(add,new LinearLayout.LayoutParams(dp(76),dp(32)));add.setOnClickListener(v->{FragmentActivity host=(FragmentActivity)requireActivity();dismiss();SourceConfigDialog.showOn(host);});
        close=button("×",24);close.setContentDescription("关闭");LinearLayout.LayoutParams xp=new LinearLayout.LayoutParams(dp(32),dp(32));xp.leftMargin=dp(10);header.addView(close,xp);close.setOnClickListener(v->dismiss());
        scroll=new ScrollView(requireContext());scroll.setFillViewport(false);scroll.setClipToPadding(false);scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        LinearLayout.LayoutParams sp=new LinearLayout.LayoutParams(-1,-2);sp.topMargin=dp(16);root.addView(scroll,sp);
        rows=new LinearLayout(requireContext());rows.setOrientation(LinearLayout.VERTICAL);scroll.addView(rows);
        status=text("",12,ResUtil.getColor(com.fongmi.android.tv.R.color.tv_theme_text_secondary));status.setMaxLines(2);status.setEllipsize(android.text.TextUtils.TruncateAt.END);status.setVisibility(View.GONE);status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        LinearLayout.LayoutParams ep=new LinearLayout.LayoutParams(-1,-2);ep.topMargin=dp(10);root.addView(status,ep);
        dialog.setContentView(root);render();return dialog;
    }
    private void render() {
        View focus=rows.findFocus();Object focusTag=focus==null?null:focus.getTag();int offset=scroll.getScrollY();
        rows.removeAllViews();
        java.util.List<Config> items=Config.getAll(0);count.setText(items.size()+" 条");
        scroll.getLayoutParams().height=dp(Math.min(264,Math.max(60,items.size()*68-8)));scroll.requestLayout();
        if(items.isEmpty()) {
            TextView empty=button("还没有线路，点击添加",15);rows.addView(empty,new LinearLayout.LayoutParams(-1,dp(60)));empty.setOnClickListener(v->add.performClick());return;
        }
        for(Config item:items) {
            boolean current=item.getUrl().equals(VodConfig.getUrl());
            LinearLayout row=new LinearLayout(requireContext());row.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams rp=new LinearLayout.LayoutParams(-1,dp(60));rp.topMargin=rows.getChildCount()==0?0:dp(8);rows.addView(row,rp);
            LinearLayout select=new LinearLayout(requireContext());select.setGravity(Gravity.CENTER_VERTICAL);select.setPadding(dp(14),0,dp(14),0);select.setBackgroundResource(R.drawable.source_line_row);select.setSelected(current);select.setFocusable(true);select.setFocusableInTouchMode(false);select.setEnabled(!busy);select.setTag("line:"+item.getId());select.setContentDescription("切换线路 "+item.getDesc());row.addView(select,new LinearLayout.LayoutParams(0,-1,1));
            ImageView icon=new ImageView(requireContext());icon.setImageResource(R.drawable.ic_home_lines);icon.setColorFilter(current?ResUtil.getColor(com.fongmi.android.tv.R.color.tv_theme_primary):ResUtil.getColor(com.fongmi.android.tv.R.color.tv_theme_text_secondary));icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);select.addView(icon,new LinearLayout.LayoutParams(dp(22),dp(22)));
            TextView label=text(item.getDesc(),15,ResUtil.getColor(com.fongmi.android.tv.R.color.tv_theme_text));label.setSingleLine(true);label.setEllipsize(android.text.TextUtils.TruncateAt.END);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,-2,1);lp.leftMargin=dp(12);select.addView(label,lp);
            if(current){TextView badge=text("使用中",11,ResUtil.getColor(com.fongmi.android.tv.R.color.tv_theme_primary));badge.setGravity(Gravity.CENTER);android.graphics.drawable.GradientDrawable bg=new android.graphics.drawable.GradientDrawable();bg.setColor(ResUtil.getColor(com.fongmi.android.tv.R.color.tv_theme_selected));bg.setCornerRadius(dp(5));badge.setBackground(bg);LinearLayout.LayoutParams bp=new LinearLayout.LayoutParams(dp(48),dp(24));bp.leftMargin=dp(10);select.addView(badge,bp);}
            select.setOnClickListener(v->activate(item));
            ImageView delete=new ImageView(requireContext());delete.setImageResource(R.drawable.ic_source_trash);delete.setColorFilter(ResUtil.getColor(com.fongmi.android.tv.R.color.tv_theme_text));delete.setPadding(dp(10),dp(10),dp(10),dp(10));delete.setBackgroundResource(R.drawable.source_field);delete.setFocusable(true);delete.setFocusableInTouchMode(false);delete.setTag("delete:"+item.getId());
            LinearLayout.LayoutParams del=new LinearLayout.LayoutParams(dp(40),dp(40));del.leftMargin=dp(10);row.addView(delete,del);delete.setEnabled(!busy&&!current);delete.setAlpha(current?.28f:1);delete.setContentDescription(current?"当前线路，请先切换再删除":"删除线路 "+item.getDesc());
            delete.setOnClickListener(v->new MaterialAlertDialogBuilder(requireContext()).setTitle("删除「"+item.getDesc()+"」？").setMessage("会同时删除此线路的播放历史和收藏；其他线路不受影响。")
                    .setNegativeButton("取消",null).setPositiveButton("删除",(d,w)->{item.delete();render();message("已删除线路");add.requestFocus();}).show());
            if(select.getTag().equals(focusTag))select.requestFocus();if(delete.getTag().equals(focusTag)&&delete.isEnabled())delete.requestFocus();
        }
        scroll.post(()->scroll.scrollTo(0,offset));
    }
    private void message(String text){status.setText(text);status.setVisibility(View.VISIBLE);}
    private void controls(boolean value){busy=value;setCancelable(!value);add.setEnabled(!value);close.setEnabled(!value);}
    private void activate(Config item){
        if(busy||SourceManager.busy())return;
        if(item.getUrl().equals(VodConfig.getUrl())){message("正在使用「"+item.getDesc()+"」");return;}
        controls(true);render();message("切换中…");
        SourceManager.activate(item,new Callback(){
            @Override public void success(){if(isAdded()){controls(false);render();message("已切换至「"+VodConfig.getDesc()+"」");add.requestFocus();}}
            @Override public void error(String message){if(isAdded()){controls(false);render();message(message);add.requestFocus();}}
        });
    }
    @Subscribe(threadMode=ThreadMode.MAIN) public void saved(SourceManager.Saved event){if(!busy)render();}
    @Override public void onStart(){super.onStart();getDialog().getWindow().setBackgroundDrawableResource(android.R.color.transparent);getDialog().getWindow().setDimAmount(0.72f);getDialog().getWindow().setLayout((int)(ResUtil.getScreenWidth()*.54f),-2);EventBus.getDefault().register(this);}
    @Override public void onStop(){EventBus.getDefault().unregister(this);super.onStop();}
}

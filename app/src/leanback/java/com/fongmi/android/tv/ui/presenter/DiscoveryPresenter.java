package com.fongmi.android.tv.ui.presenter;

import android.view.LayoutInflater;
import android.view.ViewGroup;
import androidx.annotation.NonNull;
import androidx.leanback.widget.Presenter;
import com.bumptech.glide.Glide;
import com.fongmi.android.tv.bean.Vod;
import com.fongmi.android.tv.databinding.AdapterDiscoveryBinding;
import com.fongmi.android.tv.utils.ImgUtil;
import com.fongmi.android.tv.utils.ResUtil;
import com.fongmi.android.tv.utils.PosterLabel;

/** The home discovery grid has its own geometry; playback/source cards are independent. */
public class DiscoveryPresenter extends Presenter {
    private final VodPresenter.OnClickListener listener;
    private final float heightRatio;

    public DiscoveryPresenter(VodPresenter.OnClickListener listener) {
        this(listener, 1.32f);
    }

    public DiscoveryPresenter(VodPresenter.OnClickListener listener, float heightRatio) {
        this.listener = listener;
        this.heightRatio = heightRatio;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent) {
        AdapterDiscoveryBinding binding = AdapterDiscoveryBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false);
        binding.getRoot().setHeightRatio(heightRatio);
        return new Holder(binding);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder viewHolder, Object object) {
        Vod item = (Vod) object;
        Holder holder = (Holder) viewHolder;
        holder.binding.name.setText(item.getName());
        boolean discovery = item.getAction().startsWith("search:");
        holder.binding.rating.setText(item.getSiteName());
        holder.binding.rating.setVisibility(discovery || item.getSiteName().isEmpty() ? android.view.View.GONE : android.view.View.VISIBLE);
        holder.binding.rating.setBackgroundResource(com.fongmi.android.tv.R.drawable.discovery_rating);
        holder.binding.rating.setTextColor(0xFFCED9DD);
        holder.binding.rating.setTextSize(11);
        holder.binding.rating.setTypeface(null, android.graphics.Typeface.NORMAL);
        String information = PosterLabel.text(item.getRemarks(), item.getScore());
        holder.binding.remark.setText(information);
        holder.binding.remark.setVisibility(information.isEmpty() ? android.view.View.GONE : android.view.View.VISIBLE);
        holder.view.setContentDescription(item.getName() + (information.isEmpty() ? "" : "，" + information) + (discovery ? "" : "，" + item.getSiteName()));
        holder.view.setOnClickListener(view -> listener.onItemClick(item));
        if (discovery) ImgUtil.loadPoster(item.getPic(), holder.binding.image);
        else ImgUtil.load(item.getName(), item.getPic(), holder.binding.image);
    }

    @Override
    public void onUnbindViewHolder(@NonNull ViewHolder viewHolder) {
        Glide.with(viewHolder.view).clear(((Holder) viewHolder).binding.image);
        viewHolder.view.setOnClickListener(null);
    }

    private static class Holder extends ViewHolder {
        final AdapterDiscoveryBinding binding;
        Holder(AdapterDiscoveryBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}

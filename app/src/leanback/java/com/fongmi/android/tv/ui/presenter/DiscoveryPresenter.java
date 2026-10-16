package com.fongmi.android.tv.ui.presenter;

import android.view.LayoutInflater;
import android.view.ViewGroup;
import androidx.annotation.NonNull;
import androidx.leanback.widget.Presenter;
import com.bumptech.glide.Glide;
import com.fongmi.android.tv.bean.Vod;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.databinding.AdapterDiscoveryBinding;
import com.fongmi.android.tv.utils.ImgUtil;
import com.fongmi.android.tv.utils.PosterLabel;
import java.util.function.BooleanSupplier;

/** Shared history-style poster/footer card with explicit metadata semantics for each surface. */
public class DiscoveryPresenter extends Presenter {
    private final VodPresenter.OnClickListener listener;
    private final boolean showEpisodes;
    private final BooleanSupplier showSource;
    private final BooleanSupplier showTotalEpisodes;

    public DiscoveryPresenter(VodPresenter.OnClickListener listener) {
        this(listener, false);
    }

    public DiscoveryPresenter(VodPresenter.OnClickListener listener, boolean showEpisodes) {
        this(listener, showEpisodes, () -> true, () -> false);
    }

    public DiscoveryPresenter(VodPresenter.OnClickListener listener, boolean showEpisodes, BooleanSupplier showSource, BooleanSupplier showTotalEpisodes) {
        this.listener = listener;
        this.showEpisodes = showEpisodes;
        this.showSource = showSource;
        this.showTotalEpisodes = showTotalEpisodes;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent) {
        AdapterDiscoveryBinding binding = AdapterDiscoveryBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false);
        if (!showEpisodes) {
            binding.remark.setBackgroundResource(R.drawable.poster_rating);
            binding.remark.setTextSize(16);
        }
        return new Holder(binding);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder viewHolder, Object object) {
        Vod item = (Vod) object;
        Holder holder = (Holder) viewHolder;
        holder.binding.name.setText(item.getName());
        boolean discovery = item.getAction().startsWith("search:");
        String source = !showSource.getAsBoolean() ? "" : discovery ? "豆瓣推荐"
                : PosterLabel.source(item.getSiteName(), item.getSiteKey());
        holder.binding.rating.setText(source);
        holder.binding.rating.setVisibility(source.isEmpty() ? android.view.View.GONE : android.view.View.VISIBLE);
        boolean totalOnly = showTotalEpisodes.getAsBoolean();
        String information = totalOnly ? PosterLabel.totalEpisodes(item.getRemarks())
                : showEpisodes ? PosterLabel.episodes(item.getRemarks()) : PosterLabel.rating(item.getRemarks(), item.getScore());
        if (!totalOnly && !showEpisodes && !information.isEmpty()) information += "分";
        holder.binding.remark.setText(information);
        holder.binding.remark.setVisibility(information.isEmpty() ? android.view.View.GONE : android.view.View.VISIBLE);
        holder.view.setContentDescription(item.getName() + (information.isEmpty() ? "" : "，" + information) + (source.isEmpty() ? "" : "，" + source));
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

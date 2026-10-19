package com.fongmi.android.tv.ui.adapter;

import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.fongmi.android.tv.bean.Episode;
import com.fongmi.android.tv.databinding.AdapterEpisodeBinding;
import com.fongmi.android.tv.ui.custom.EpisodeGridView;
import com.fongmi.android.tv.utils.ResUtil;

import java.util.ArrayList;
import java.util.List;

public class EpisodeAdapter extends RecyclerView.Adapter<EpisodeAdapter.ViewHolder> {

    private final OnClickListener mListener;
    private final List<Episode> mItems;
    private final int maxWidth;
    private int columnCount = EpisodeGridView.DEFAULT_COLUMN_COUNT;
    private int nextFocusDown;
    private int nextFocusUp;

    public EpisodeAdapter(OnClickListener listener) {
        mListener = listener;
        mItems = new ArrayList<>();
        maxWidth = ResUtil.getScreenWidth() - ResUtil.dp2px(48);
    }

    public void addAll(List<Episode> items) {
        mItems.clear();
        mItems.addAll(items);
        notifyDataSetChanged();
    }

    public void clear() {
        mItems.clear();
        notifyDataSetChanged();
    }

    public int getPosition() {
        for (int i = 0; i < mItems.size(); i++) if (mItems.get(i).isSelected()) return i;
        return 0;
    }

    public Episode getActivated() {
        return mItems.isEmpty() ? new Episode() : mItems.get(getPosition());
    }

    public Episode getNext() {
        int current = getPosition();
        int max = getItemCount() - 1;
        current = ++current > max ? max : current;
        return mItems.get(current);
    }

    public Episode getPrev() {
        int current = getPosition();
        current = --current < 0 ? 0 : current;
        return mItems.get(current);
    }

    public void setNextFocusDown(int nextFocusDown) {
        this.nextFocusDown = nextFocusDown;
        notifyDataSetChanged();
    }

    public void setNextFocusUp(int nextFocusUp) {
        this.nextFocusUp = nextFocusUp;
        notifyDataSetChanged();
    }

    public void setColumnCount(int columnCount) {
        this.columnCount = Math.max(2, Math.min(EpisodeGridView.DEFAULT_COLUMN_COUNT, columnCount));
    }

    /**
     * Pick the densest grid that keeps the real source label usable on a TV.
     *
     * <p>File-backed sources often put the size in {@code desc}.  That is not a
     * reason to force every item into two very wide columns: the title still has
     * a marquee on focus/hover, and four columns expose twice as many episodes in
     * the same viewport.  Only genuinely long labels use three or two columns.</p>
     */
    public static int columnsFor(List<Episode> items) {
        int longest = 0;
        for (Episode item : items) {
            if (item == null) continue;
            String desc = item.getDesc();
            String name = item.getName();
            // Include the separator that is visually implied when both fields
            // are present.  This avoids choosing six columns for a two-line label.
            int length = desc.length() + name.length() + (desc.isEmpty() || name.isEmpty() ? 0 : 1);
            longest = Math.max(longest, length);
        }
        if (longest > 38) return 2;
        if (longest > 24) return 3;
        if (longest > 10) return 4;
        return EpisodeGridView.DEFAULT_COLUMN_COUNT;
    }

    @Override
    public int getItemCount() {
        return mItems.size();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new ViewHolder(AdapterEpisodeBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        holder.stopMarquee();
        Episode item = mItems.get(position);
        String desc = item.getDesc();
        String name = item.getName();
        if (desc.isEmpty()) {
            holder.binding.size.setVisibility(android.view.View.GONE);
            holder.binding.name.setText(name);
        } else if (name.isEmpty()) {
            holder.binding.size.setVisibility(android.view.View.GONE);
            holder.binding.name.setText(desc);
        } else {
            holder.binding.size.setVisibility(android.view.View.VISIBLE);
            holder.binding.size.setText(desc);
            holder.binding.name.setText(name);
        }
        holder.binding.name.setMaxWidth(maxWidth);
        holder.binding.getRoot().setNextFocusUpId(position < columnCount ? nextFocusUp : android.view.View.NO_ID);
        holder.binding.getRoot().setNextFocusDownId(position / columnCount == (mItems.size() - 1) / columnCount ? nextFocusDown : android.view.View.NO_ID);
        holder.binding.getRoot().setSelected(item.isSelected());
        holder.binding.getRoot().setOnClickListener(v -> mListener.onItemClick(item));
        // Playing selection propagates to children; only focus/hover may scroll text.
        holder.updateMarquee();
    }

    @Override
    public void onViewAttachedToWindow(@NonNull ViewHolder holder) {
        super.onViewAttachedToWindow(holder);
        holder.updateMarquee();
    }

    @Override
    public void onViewDetachedFromWindow(@NonNull ViewHolder holder) {
        holder.stopMarquee();
        super.onViewDetachedFromWindow(holder);
    }

    @Override
    public void onViewRecycled(@NonNull ViewHolder holder) {
        holder.stopMarquee();
        super.onViewRecycled(holder);
    }

    public interface OnClickListener {

        void onItemClick(Episode item);
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {

        private final AdapterEpisodeBinding binding;
        private boolean hovered;

        ViewHolder(@NonNull AdapterEpisodeBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
            itemView.setOnFocusChangeListener((view, focused) -> updateMarquee());
            itemView.setOnHoverListener((view, event) -> {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_HOVER_ENTER:
                    case MotionEvent.ACTION_HOVER_MOVE:
                        if (!hovered) {
                            hovered = true;
                            updateMarquee();
                        }
                        break;
                    case MotionEvent.ACTION_HOVER_EXIT:
                        hovered = false;
                        updateMarquee();
                        break;
                }
                return false;
            });
        }

        private void updateMarquee() {
            boolean active = itemView.hasFocus() || hovered;
            setMarquee(binding.name, active);
            setMarquee(binding.size, active);
        }

        private void stopMarquee() {
            hovered = false;
            setMarquee(binding.name, false);
            setMarquee(binding.size, false);
        }

        private void setMarquee(TextView text, boolean active) {
            if (active) {
                text.setEllipsize(TextUtils.TruncateAt.MARQUEE);
                text.setSelected(true);
            } else {
                text.setSelected(false);
                text.setEllipsize(TextUtils.TruncateAt.END);
                text.scrollTo(0, 0);
            }
        }
    }
}

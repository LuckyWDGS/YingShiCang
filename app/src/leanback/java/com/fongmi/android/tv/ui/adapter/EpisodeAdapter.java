package com.fongmi.android.tv.ui.adapter;

import android.text.TextUtils;
import android.view.LayoutInflater;
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

    // Focus links touch only the first/last row, so they rebind partially.
    private static final Object PAYLOAD_FOCUS = new Object();

    private final OnClickListener mListener;
    private final List<Episode> mItems;
    private final int maxWidth;
    private int columnCount = EpisodeGridView.DEFAULT_COLUMN_COUNT;
    private int nextFocusDown;
    private int nextFocusUp;
    private int selectedIndex = RecyclerView.NO_POSITION;

    public EpisodeAdapter(OnClickListener listener) {
        mListener = listener;
        mItems = new ArrayList<>();
        maxWidth = ResUtil.getScreenWidth() - ResUtil.dp2px(48);
    }

    public void addAll(List<Episode> items) {
        mItems.clear();
        mItems.addAll(items);
        selectedIndex = findSelected();
        notifyDataSetChanged();
    }

    public void clear() {
        mItems.clear();
        selectedIndex = RecyclerView.NO_POSITION;
        notifyDataSetChanged();
    }

    /**
     * Fully rebinds the previously and newly playing cards after the shared Episode flags change.
     * A selection-only partial rebind left the playing card's label scrolled out of view (blank cell).
     */
    public void refreshSelection() {
        int current = findSelected();
        if (selectedIndex != current && selectedIndex != RecyclerView.NO_POSITION && selectedIndex < mItems.size()) notifyItemChanged(selectedIndex);
        if (current != RecyclerView.NO_POSITION) notifyItemChanged(current);
        selectedIndex = current;
    }

    private int findSelected() {
        for (int i = 0; i < mItems.size(); i++) if (mItems.get(i).isSelected()) return i;
        return RecyclerView.NO_POSITION;
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
        if (this.nextFocusDown == nextFocusDown) return;
        this.nextFocusDown = nextFocusDown;
        if (mItems.isEmpty()) return;
        int lastRow = (mItems.size() - 1) / columnCount * columnCount;
        notifyItemRangeChanged(lastRow, mItems.size() - lastRow, PAYLOAD_FOCUS);
    }

    public void setNextFocusUp(int nextFocusUp) {
        if (this.nextFocusUp == nextFocusUp) return;
        this.nextFocusUp = nextFocusUp;
        notifyItemRangeChanged(0, Math.min(columnCount, mItems.size()), PAYLOAD_FOCUS);
    }

    public void setColumnCount(int columnCount) {
        this.columnCount = Math.max(2, Math.min(EpisodeGridView.DEFAULT_COLUMN_COUNT, columnCount));
    }

    /**
     * Pick the densest grid that keeps the real source label usable on a TV.
     *
     * <p>File-backed sources often put the size in {@code desc}.  That is not a
     * reason to force every item into two very wide columns: the title still has
     * a marquee while focused, and four columns expose twice as many episodes in
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
        // setMaxWidth relays the card out even when unchanged; binds run for every row a fast scroll reveals.
        if (holder.binding.name.getMaxWidth() != maxWidth) holder.binding.name.setMaxWidth(maxWidth);
        bindFocus(holder, position);
        holder.binding.getRoot().setOnClickListener(v -> mListener.onItemClick(item));
        bindSelection(holder, item);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position, @NonNull List<Object> payloads) {
        if (payloads.isEmpty()) {
            onBindViewHolder(holder, position);
            return;
        }
        for (Object payload : payloads) {
            if (payload == PAYLOAD_FOCUS) bindFocus(holder, position);
        }
    }

    private void bindFocus(ViewHolder holder, int position) {
        holder.binding.getRoot().setNextFocusUpId(position < columnCount ? nextFocusUp : android.view.View.NO_ID);
        holder.binding.getRoot().setNextFocusDownId(position / columnCount == (mItems.size() - 1) / columnCount ? nextFocusDown : android.view.View.NO_ID);
    }

    private void bindSelection(ViewHolder holder, Episode item) {
        holder.binding.getRoot().setSelected(item.isSelected());
        // Selection propagates to the children; updateMarquee then decides which text scrolls.
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

        private static final int PLAYING_PASSES = 3;

        private final AdapterEpisodeBinding binding;
        ViewHolder(@NonNull AdapterEpisodeBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
            itemView.setOnFocusChangeListener((view, focused) -> updateMarquee());
        }

        private void updateMarquee() {
            // The focused card and the playing card scroll a label too long to fit; touch
            // users never focus a card, so the playing one is how they read it. Recycled and
            // off-screen cards stop (detach/recycle reset it).
            // Focus scrolls until it moves; the playing card alone scrolls a few passes, then rests
            // (an endless marquee keeps redrawing beside the preview video on low-end boxes).
            boolean focused = itemView.hasFocus();
            boolean active = focused || itemView.isSelected();
            int repeat = focused ? -1 : PLAYING_PASSES;
            setMarquee(binding.name, active, repeat);
            setMarquee(binding.size, active, repeat);
        }

        private void stopMarquee() {
            setMarquee(binding.name, false, 0);
            setMarquee(binding.size, false, 0);
        }

        /**
         * A single-line label scrolls horizontally: its text sits in the middle of a line a million
         * pixels wide and the view's scroll brings it into sight. Resetting that scroll left cards
         * revealed while the list scrolled up blank until focused (user 2026-10-24, measured on
         * LDPlayer: scrollX 0 against text at x 524176). A resting label does not scroll at all, so
         * its line is the card's width and scroll 0 is right.
         */
        private void setMarquee(TextView text, boolean active, int repeat) {
            if (active) {
                // A changed pass limit only applies to a restarted marquee.
                if (text.getMarqueeRepeatLimit() != repeat) {
                    text.setSelected(false);
                    text.setMarqueeRepeatLimit(repeat);
                }
                text.setHorizontallyScrolling(true);
                text.setEllipsize(TextUtils.TruncateAt.MARQUEE);
                text.setSelected(true);
            } else {
                text.setSelected(false);
                text.setHorizontallyScrolling(false);
                text.setEllipsize(TextUtils.TruncateAt.END);
                text.scrollTo(0, 0);
            }
        }
    }
}

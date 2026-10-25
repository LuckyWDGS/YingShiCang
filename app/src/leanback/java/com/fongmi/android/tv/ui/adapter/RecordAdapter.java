package com.fongmi.android.tv.ui.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.databinding.AdapterSearchRecordBinding;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.ui.custom.SpatialFocus;
import com.google.gson.reflect.TypeToken;

import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;

public class RecordAdapter extends RecyclerView.Adapter<RecordAdapter.ViewHolder> {

    private final OnClickListener listener;
    private final List<String> mItems;
    // Display order: the saved recency order, or the arranger's packing of it.
    private List<String> shown;
    private UnaryOperator<List<String>> arranger;

    public RecordAdapter(OnClickListener listener) {
        this.listener = listener;
        this.mItems = getItems();
        this.shown = new ArrayList<>(mItems);
        this.listener.onDataChanged(mItems.size());
        setHasStableIds(true);
    }

    /** Lays the saved records out in another order (e.g. packed rows); the saved order is unchanged. */
    public void setArranger(UnaryOperator<List<String>> arranger) {
        this.arranger = arranger;
        refresh();
    }

    private void refresh() {
        shown = arranger == null ? new ArrayList<>(mItems) : arranger.apply(List.copyOf(mItems));
        notifyDataSetChanged();
    }

    public interface OnClickListener {

        void onItemClick(String text);

        void onDataChanged(int size);
    }

    private List<String> getItems() {
        if (Setting.getKeyword().isEmpty()) return new ArrayList<>();
        return App.gson().fromJson(Setting.getKeyword(), TypeToken.getParameterized(List.class, String.class).getType());
    }

    private void checkToAdd(String item) {
        mItems.remove(item);
        mItems.add(0, item);
        if (mItems.size() > 9) mItems.remove(9);
    }

    public void add(String item) {
        remember(item);
        show();
    }

    /**
     * Saves a search without reordering the visible chips: moving the clicked record to the front
     * while it still had focus dropped focus onto 清空 before the results opened (user 2026-10-24).
     * The caller shows the new order once the screen is hidden.
     */
    public void remember(String item) {
        checkToAdd(item);
        Setting.putKeyword(App.gson().toJson(mItems));
    }

    public void show() {
        if (shown.equals(arranger == null ? mItems : arranger.apply(List.copyOf(mItems)))) return;
        refresh();
        listener.onDataChanged(getItemCount());
    }

    public void clear() {
        mItems.clear();
        Setting.putKeyword("[]");
        refresh();
        listener.onDataChanged(0);
    }

    @Override
    public long getItemId(int position) {
        return shown.get(position).hashCode();
    }

    @Override
    public int getItemCount() {
        return shown.size();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new ViewHolder(AdapterSearchRecordBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        String text = shown.get(position);
        holder.binding.text.setText(text);
        holder.binding.text.setOnClickListener(v -> listener.onItemClick(text));
    }

    public class ViewHolder extends RecyclerView.ViewHolder implements View.OnLongClickListener {

        private final AdapterSearchRecordBinding binding;

        public ViewHolder(@NonNull AdapterSearchRecordBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
            itemView.setOnLongClickListener(this);
            SpatialFocus.attach(itemView);
        }

        @Override
        public boolean onLongClick(View v) {
            int position = getBindingAdapterPosition();
            if (position == RecyclerView.NO_POSITION) return false;
            RecyclerView parent = (RecyclerView) itemView.getParent();
            mItems.remove(shown.get(position));
            refresh();
            listener.onDataChanged(getItemCount());
            Setting.putKeyword(App.gson().toJson(mItems));
            if (parent != null && !shown.isEmpty()) parent.post(() -> {
                RecyclerView.ViewHolder next = parent.findViewHolderForAdapterPosition(Math.min(position, shown.size() - 1));
                if (next != null) next.itemView.requestFocus();
            });
            return true;
        }
    }
}

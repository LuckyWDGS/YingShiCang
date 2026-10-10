package com.fongmi.android.tv.ui.adapter;

import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.AsyncDifferConfig;

import com.fongmi.android.tv.bean.Word;
import com.fongmi.android.tv.databinding.AdapterSearchWordBinding;
import com.fongmi.android.tv.ui.custom.SpatialFocus;

public class WordAdapter extends BaseDiffAdapter<Word.Data, WordAdapter.ViewHolder> {

    private final OnClickListener listener;

    public WordAdapter(OnClickListener listener) {
        this(listener, false);
    }

    public WordAdapter(OnClickListener listener, boolean immediate) {
        super(diffConfig(immediate));
        this.listener = listener;
    }

    private static AsyncDifferConfig<Word.Data> diffConfig(boolean immediate) {
        AsyncDifferConfig.Builder<Word.Data> builder = new AsyncDifferConfig.Builder<>(new BaseItemCallback<Word.Data>());
        // The suggestion list is bounded to 24 titles and is submitted on the UI thread.
        // Commit it in the same input turn so an old background diff cannot replace retained rows.
        if (immediate) builder.setBackgroundThreadExecutor(Runnable::run).setMainThreadExecutor(Runnable::run);
        return builder.build();
    }

    public interface OnClickListener {

        void onItemClick(String text);
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new ViewHolder(AdapterSearchWordBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        Word.Data item = getItem(position);
        holder.binding.text.setText(item.getTitle());
        holder.binding.text.setOnClickListener(v -> listener.onItemClick(item.getTitle()));
    }

    public class ViewHolder extends RecyclerView.ViewHolder {

        private final AdapterSearchWordBinding binding;

        public ViewHolder(@NonNull AdapterSearchWordBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
            SpatialFocus.attach(itemView);
        }
    }
}

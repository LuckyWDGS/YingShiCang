package com.fongmi.android.tv.ui.custom;

import android.text.Editable;
import android.widget.EditText;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.databinding.ActivitySearchBinding;
import com.fongmi.android.tv.ui.adapter.KeyboardAdapter;

public class CustomKeyboard implements KeyboardAdapter.OnClickListener {

    private final ActivitySearchBinding binding;
    private final Callback callback;
    private KeyboardAdapter adapter;

    public static void init(Callback callback, ActivitySearchBinding binding) {
        new CustomKeyboard(callback, binding).initView();
    }

    public CustomKeyboard(Callback callback, ActivitySearchBinding binding) {
        this.callback = callback;
        this.binding = binding;
    }

    private void initView() {
        binding.keyboard.setItemAnimator(null);
        binding.keyboard.setHasFixedSize(false);
        binding.keyboard.setAdapter(adapter = new KeyboardAdapter(this));
    }

    @Override
    public void onTextClick(String text) {
        Editable editable = binding.keyword.getText();
        if (editable == null || text == null || text.isEmpty()) return;
        int[] selection = getSelection(binding.keyword, editable);
        int remainingLength = editable.length() - (selection[1] - selection[0]);
        editable.replace(selection[0], selection[1], text);
        int insertedLength = editable.length() - remainingLength;
        binding.keyword.setSelection(Math.min(editable.length(), selection[0] + insertedLength));
    }

    @Override
    public void onIconClick(int resId) {
        if (resId == R.drawable.ic_setting_home) callback.showDialog();
        else if (resId == R.drawable.ic_keyboard_remote) callback.onRemote();
        else if (resId == R.drawable.ic_keyboard_search) callback.onSearch();
        else if (resId == R.drawable.ic_keyboard_left) onMoveLeft();
        else if (resId == R.drawable.ic_keyboard_right) onMoveRight();
        else if (resId == R.drawable.ic_keyboard_back) onBackspace();
        else if (resId == R.drawable.ic_keyboard) adapter.toggle();
    }

    private void onMoveLeft() {
        Editable editable = binding.keyword.getText();
        if (editable == null) return;
        int[] selection = getSelection(binding.keyword, editable);
        int cursor = selection[0];
        if (selection[0] == selection[1] && cursor > 0 && !isInsideCodePoint(editable, binding.keyword.getSelectionStart())) cursor = Character.offsetByCodePoints(editable, cursor, -1);
        binding.keyword.setSelection(cursor);
    }

    private void onMoveRight() {
        Editable editable = binding.keyword.getText();
        if (editable == null) return;
        int[] selection = getSelection(binding.keyword, editable);
        int cursor = selection[1];
        if (selection[0] == selection[1] && cursor < editable.length()) cursor = Character.offsetByCodePoints(editable, cursor, 1);
        binding.keyword.setSelection(cursor);
    }

    private void onBackspace() {
        backspace(binding.keyword);
    }

    public static void backspace(EditText input) {
        Editable editable = input.getText();
        if (editable == null) return;
        int[] selection = getSelection(input, editable);
        int start = selection[0];
        if (start == selection[1]) {
            if (isInsideCodePoint(editable, input.getSelectionStart())) selection[1] = Character.offsetByCodePoints(editable, start, 1);
            else {
                if (start == 0) return;
                start = Character.offsetByCodePoints(editable, start, -1);
            }
        }
        editable.delete(start, selection[1]);
        input.setSelection(start);
    }

    private static int[] getSelection(EditText input, Editable editable) {
        int first = Math.max(0, Math.min(editable.length(), input.getSelectionStart()));
        int second = Math.max(0, Math.min(editable.length(), input.getSelectionEnd()));
        int start = Math.min(first, second);
        int end = Math.max(first, second);
        if (isInsideCodePoint(editable, start)) start--;
        if (first == second) end = start;
        else if (isInsideCodePoint(editable, end)) end++;
        return new int[]{start, end};
    }

    private static boolean isInsideCodePoint(CharSequence text, int offset) {
        return offset > 0 && offset < text.length() && Character.isHighSurrogate(text.charAt(offset - 1)) && Character.isLowSurrogate(text.charAt(offset));
    }

    @Override
    public boolean onLongClick(int resId) {
        if (resId != R.drawable.ic_keyboard_back) return false;
        Editable editable = binding.keyword.getText();
        if (editable != null) editable.clear();
        return true;
    }

    public interface Callback {

        void showDialog();

        void onRemote();

        void onSearch();
    }
}

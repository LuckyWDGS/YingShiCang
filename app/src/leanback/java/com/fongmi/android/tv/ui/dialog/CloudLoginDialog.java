package com.fongmi.android.tv.ui.dialog;

import android.content.DialogInterface;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.fragment.app.FragmentManager;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.databinding.DialogCloudLoginBinding;
import com.fongmi.android.tv.databinding.ItemCloudLoginBinding;
import com.fongmi.android.tv.utils.ResUtil;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/** View-only, current-session surface. Provider networking and login ownership stay outside. */
public final class CloudLoginDialog extends BaseAlertDialog {
    private static final String TAG = "cloud-login";
    private static final String PROVIDERS = "provider-names";
    private static final int PROVIDER_COUNT = 3;

    public enum State { LOADING, READY, SCANNED, EXPIRED, ERROR }

    public interface Listener {
        void onRetry(int index);
        void onClosed();
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicBoolean closed = new AtomicBoolean();
    private final ItemCloudLoginBinding[] cards = new ItemCloudLoginBinding[PROVIDER_COUNT];
    private final State[] states = { State.LOADING, State.LOADING, State.LOADING };
    private final Bitmap[] codes = new Bitmap[PROVIDER_COUNT];
    private final String[] statuses = new String[PROVIDER_COUNT];
    private DialogCloudLoginBinding binding;
    private Listener listener;
    private List<String> providerNames;
    private int lastFocusedCard = 0;

    /** Call on the main thread. A rejected/duplicate show returns null and starts no UI session. */
    @Nullable
    public static CloudLoginDialog show(@NonNull FragmentActivity activity,
            @NonNull List<String> providerNames, @NonNull Listener listener) {
        if (Looper.myLooper() != Looper.getMainLooper())
            throw new IllegalStateException("Cloud login must be opened on the main thread");
        if (providerNames.size() != PROVIDER_COUNT || listener == null)
            throw new IllegalArgumentException("Cloud login requires three providers and a listener");
        for (String name : providerNames) {
            if (name == null || name.trim().isEmpty())
                throw new IllegalArgumentException("Cloud login provider names must not be empty");
        }
        FragmentManager manager = activity.getSupportFragmentManager();
        if (activity.isFinishing() || activity.isDestroyed() || manager.isStateSaved()) return null;
        Fragment existing = manager.findFragmentByTag(TAG);
        if (existing != null) return null;
        CloudLoginDialog dialog = new CloudLoginDialog();
        Bundle args = new Bundle();
        args.putStringArrayList(PROVIDERS, new ArrayList<>(providerNames));
        dialog.setArguments(args);
        dialog.listener = listener;
        dialog.showNow(manager, TAG);
        return dialog;
    }

    @Nullable
    public static CloudLoginDialog show(@NonNull FragmentActivity activity,
            @NonNull String[] providerNames, @NonNull Listener listener) {
        return show(activity, Arrays.asList(providerNames), listener);
    }

    @Override
    protected ViewBinding getBinding() {
        return binding = DialogCloudLoginBinding.inflate(getLayoutInflater());
    }

    @Override
    protected MaterialAlertDialogBuilder getBuilder() {
        return builder().setView(getBinding().getRoot())
                .setBackgroundInsetStart(0).setBackgroundInsetEnd(0)
                .setBackgroundInsetTop(0).setBackgroundInsetBottom(0);
    }

    @Override
    protected void initView() {
        providerNames = getArguments() == null ? null : getArguments().getStringArrayList(PROVIDERS);
        if (providerNames == null || providerNames.size() != PROVIDER_COUNT || listener == null) {
            closed.set(true);
            return;
        }
        for (int index = 0; index < PROVIDER_COUNT; index++) {
            ItemCloudLoginBinding card = ItemCloudLoginBinding.inflate(getLayoutInflater(), binding.cards, false);
            cards[index] = card;
            String provider = displayProviderName(providerNames.get(index));
            card.badge.setText(String.format(java.util.Locale.ROOT, "%02d", index + 1));
            card.provider.setText(provider);
            // Keep the three scan targets visually distinct at a glance.  The
            // accent is decorative only; provider identity still comes from
            // the real name supplied by the login owner.
            int accent = ResUtil.getColor(providerAccentColor(index));
            card.providerAccent.setBackgroundTintList(ColorStateList.valueOf(accent));
            card.badge.setTextColor(accent);
            card.getRoot().setElevation(dp(3));
            card.scanHint.setText(getString(R.string.cloud_login_scan_provider, provider));
            card.getRoot().setContentDescription(getString(R.string.cloud_login_card_content,
                    provider, getString(R.string.cloud_login_loading)));
            card.getRoot().setId(View.generateViewId());
            card.retry.setContentDescription(getString(R.string.cloud_login_provider_retry, provider));
            LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) card.getRoot().getLayoutParams();
            if (index != 0) params.setMarginStart(dp(16));
            binding.cards.addView(card.getRoot(), params);
            renderCard(index);
        }
    }

    @Override
    protected void initEvent() {
        binding.close.setOnClickListener(view -> closeAndDismiss());
        if (closed.get()) return;
        for (int index = 0; index < PROVIDER_COUNT; index++) {
            final int current = index;
            cards[index].getRoot().setOnClickListener(view -> {
                if (closed.get() || listener == null) return;
                // A ready QR is a display surface. Only an expired/error card is
                // retryable, so an accidental OK on the QR never replaces it.
                if (states[current] == State.EXPIRED || states[current] == State.ERROR)
                    retry(current);
            });
            cards[index].retry.setOnClickListener(view -> {
                if (!closed.get() && listener != null
                        && (states[current] == State.EXPIRED || states[current] == State.ERROR))
                    retry(current);
            });
            cards[index].getRoot().setOnFocusChangeListener((view, focused) -> {
                if (cards[current] == null) return;
                cards[current].getRoot().setSelected(focused);
                cards[current].getRoot().setElevation(dp(focused ? 8 : 3));
                if (focused) lastFocusedCard = current;
            });
        }
        binding.close.setOnKeyListener((view, keyCode, event) -> {
            if (keyCode != android.view.KeyEvent.KEYCODE_DPAD_DOWN
                    || event.getAction() != android.view.KeyEvent.ACTION_DOWN) return false;
            int nearest = nearestCard(lastFocusedCard);
            return nearest >= 0 && cards[nearest].getRoot().requestFocus();
        });
    }

    private void retry(int index) {
        if (closed.get() || listener == null) return;
        // Suppress double clicks immediately; the owner chooses the next request generation.
        states[index] = State.LOADING;
        codes[index] = null;
        statuses[index] = null;
        renderCard(index);
        listener.onRetry(index);
    }

    /** May be called from any thread. Never logs or saves the bitmap or provider status. */
    public void render(int index, @NonNull State state, @Nullable Bitmap code, @Nullable String status) {
        if (index < 0 || index >= PROVIDER_COUNT || state == null || closed.get()) return;
        runOnMain(() -> {
            if (closed.get() || binding == null || cards[index] == null) return;
            states[index] = state;
            // Scanned callbacks may update only text; keep the current QR until authorization finishes.
            if (state != State.SCANNED || code != null) codes[index] = code;
            if (state == State.LOADING || state == State.EXPIRED || state == State.ERROR) codes[index] = null;
            statuses[index] = status;
            renderCard(index);
        });
    }

    public boolean isOpen() {
        return !closed.get();
    }

    /** Successful authentication already belongs to the owner; this avoids reporting cancellation. */
    public void completeAndDismiss() {
        if (!closed.compareAndSet(false, true)) return;
        // Detach the owner synchronously with the closed transition.  The
        // queued main-thread dismiss can race with onStop()/onDestroyView();
        // those callbacks intentionally become no-ops once closed is true,
        // so leaving the listener attached until the runnable runs would
        // retain the Fragment (and its activity) if the runnable is removed.
        listener = null;
        runOnMain(() -> {
            clearCodes();
            dismissAllowingStateLoss();
        });
    }

    @Override
    public void onStart() {
        super.onStart();
        // FragmentManager recreation cannot restore a live provider/controller session safely.
        if (closed.get() || listener == null || binding == null) {
            dismissAllowingStateLoss();
            return;
        }
        Window window = getDialog() == null ? null : getDialog().getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            window.setDimAmount(0.76f);
            int width = Math.min((int) (ResUtil.getScreenWidth(requireContext()) * 0.92f), dp(900));
            window.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT);
            fitQrFrames(width);
        }
        if (getDialog() != null) getDialog().setCanceledOnTouchOutside(false);
        // The QR cards are the primary action. Start on the first card so the
        // user can scan immediately without spending an extra remote click to
        // leave the close button. DPAD_UP still returns to the close action.
        int firstCard = nearestCard(lastFocusedCard);
        if (firstCard >= 0) cards[firstCard].getRoot().requestFocus();
        else binding.close.requestFocus();
        updateFocusRoutes();
    }

    private void fitQrFrames(int panelWidth) {
        // The panel has 28dp horizontal padding and the cards have 13dp
        // internal padding. Keep the QR matrix inside its card even on a
        // narrow preview/window instead of letting it clip into its neighbor.
        int widthLimit = (panelWidth - dp(60) - dp(32)) / PROVIDER_COUNT - dp(24);
        // Fixed text/control space is ~268dp (including the 44dp status and
        // 36dp retry rows). Preserve a safe margin on shorter TV canvases.
        int heightLimit = (int) (ResUtil.getScreenHeight(requireContext()) * 0.92f) - dp(268);
        // Leanback is normally a wide TV surface, but clamping both limits
        // keeps preview windows and split-screen hosts from receiving a
        // negative or overflowing LayoutParams value.
        int minSize = dp(40);
        int size = Math.max(minSize, Math.min(dp(184), Math.min(
                Math.max(minSize, widthLimit), Math.max(minSize, heightLimit))));
        for (ItemCloudLoginBinding card : cards) {
            ViewGroup.LayoutParams params = card.qrFrame.getLayoutParams();
            params.width = size;
            params.height = size;
            card.qrFrame.setLayoutParams(params);
        }
    }

    private void renderCard(int index) {
        ItemCloudLoginBinding card = cards[index];
        if (binding == null || card == null) return;
        State state = states[index];
        Bitmap code = codes[index];
        boolean usableCode = code != null && !code.isRecycled();
        if ((state == State.READY || state == State.SCANNED) && !usableCode) {
            // A ready/scanned state without a payload cannot be displayed as a
            // usable QR. Normalize the local state too, so OK/retry semantics
            // never get stuck on an empty card after a late callback.
            state = State.LOADING;
            states[index] = state;
        }
        boolean shownCode = (state == State.READY || state == State.SCANNED) && usableCode;
        card.qr.setImageBitmap(shownCode ? code : null);
        card.qr.setVisibility(shownCode ? View.VISIBLE : View.INVISIBLE);
        card.placeholder.setVisibility(shownCode ? View.GONE : View.VISIBLE);
        card.loading.setVisibility(state == State.LOADING ? View.VISIBLE : View.GONE);
        card.notice.setText(state == State.EXPIRED ? R.string.cloud_login_placeholder_expired
                : state == State.ERROR ? R.string.cloud_login_placeholder_error
                : R.string.cloud_login_placeholder_loading);
        card.status.setText(statuses[index] == null || statuses[index].trim().isEmpty()
                ? getString(defaultStatus(state)) : statuses[index]);
        boolean retryable = state == State.EXPIRED || state == State.ERROR;
        String description = getString(R.string.cloud_login_card_content,
                providerNames == null ? "" : displayProviderName(providerNames.get(index)),
                shortStatus(card.status.getText()));
        if (retryable) description += "，" + getString(R.string.cloud_login_retry_hint);
        card.getRoot().setContentDescription(description);
        int statusColor;
        switch (state) {
            case READY:
                statusColor = ResUtil.getColor(R.color.cloud_login_success);
                break;
            case SCANNED:
                statusColor = ResUtil.getColor(R.color.cloud_login_warning);
                break;
            case EXPIRED:
                statusColor = ResUtil.getColor(R.color.cloud_login_warning);
                break;
            case ERROR:
                statusColor = ResUtil.getColor(R.color.cloud_login_error);
                break;
            default:
                statusColor = ResUtil.getColor(R.color.tv_theme_text_secondary);
                break;
        }
        card.status.setTextColor(statusColor);
        card.status.setBackgroundTintList(ColorStateList.valueOf(ResUtil.getColor(statusBackgroundColor(state))));
        boolean hadFocus = card.getRoot().hasFocus();
        card.retry.setEnabled(retryable);
        // Keep a stable action slot so one expired card cannot make the row
        // taller than its neighbors.  The empty slot is intentionally hidden
        // from accessibility services until it becomes actionable.
        card.retry.setVisibility(View.VISIBLE);
        card.retry.setAlpha(retryable ? 1f : 0f);
        card.retry.setImportantForAccessibility(retryable
                ? View.IMPORTANT_FOR_ACCESSIBILITY_YES
                : View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        if (retryable) card.retry.setText(R.string.cloud_login_retry);
        card.retry.setTextColor(ResUtil.getColor(state == State.EXPIRED || state == State.ERROR
                ? R.color.tv_theme_text : R.color.tv_theme_text_secondary));
        if (!retryable && hadFocus) {
            int next = nearestRetryableCard(index);
            if (next >= 0) cards[next].getRoot().requestFocus();
            else if (state == State.EXPIRED || state == State.ERROR) binding.close.requestFocus();
        }
        updateFocusRoutes();
    }

    private int defaultStatus(State state) {
        switch (state) {
            case READY: return R.string.cloud_login_ready;
            case SCANNED: return R.string.cloud_login_scanned;
            case EXPIRED: return R.string.cloud_login_expired;
            case ERROR: return R.string.cloud_login_error;
            default: return R.string.cloud_login_loading;
        }
    }

    private int providerAccentColor(int index) {
        switch (index) {
            case 1: return R.color.cloud_login_provider_two;
            case 2: return R.color.cloud_login_provider_three;
            default: return R.color.cloud_login_provider_one;
        }
    }

    private void updateFocusRoutes() {
        if (binding == null) return;
        for (int index = 0; index < PROVIDER_COUNT; index++) {
            if (cards[index] == null) continue;
            View card = cards[index].getRoot();
            card.setNextFocusUpId(binding.close.getId());
            card.setNextFocusDownId(binding.close.getId());
            card.setNextFocusLeftId(neighborId(index, -1));
            card.setNextFocusRightId(neighborId(index, 1));
        }
        int first = nearestCard(lastFocusedCard);
        int target = first >= 0 ? cards[first].getRoot().getId() : binding.close.getId();
        binding.close.setNextFocusDownId(target);
        binding.close.setNextFocusUpId(binding.close.getId());
        binding.close.setNextFocusLeftId(binding.close.getId());
        binding.close.setNextFocusRightId(binding.close.getId());
    }

    private int statusBackgroundColor(State state) {
        switch (state) {
            case READY: return R.color.cloud_login_status_ready;
            case SCANNED:
            case EXPIRED: return R.color.cloud_login_status_scanned;
            case ERROR: return R.color.cloud_login_status_error;
            default: return R.color.cloud_login_status_loading;
        }
    }

    private int neighborId(int start, int direction) {
        for (int index = start + direction; index >= 0 && index < PROVIDER_COUNT; index += direction)
            if (cards[index] != null) return cards[index].getRoot().getId();
        // Keep focus on the edge card instead of unexpectedly jumping to the
        // close action when a user presses left/right at either boundary.
        return cards[start] == null ? binding.close.getId() : cards[start].getRoot().getId();
    }

    private int nearestCard(int preferred) {
        if (preferred >= 0 && preferred < PROVIDER_COUNT && cards[preferred] != null) return preferred;
        for (int distance = 1; distance < PROVIDER_COUNT; distance++) {
            int right = preferred + distance;
            int left = preferred - distance;
            if (right < PROVIDER_COUNT && cards[right] != null) return right;
            if (left >= 0 && cards[left] != null) return left;
        }
        return -1;
    }

    private int nearestRetryableCard(int preferred) {
        if (preferred >= 0 && preferred < PROVIDER_COUNT && cards[preferred] != null
                && cards[preferred].retry.isEnabled()) return preferred;
        for (int distance = 1; distance < PROVIDER_COUNT; distance++) {
            int right = preferred + distance;
            int left = preferred - distance;
            if (right < PROVIDER_COUNT && cards[right] != null && cards[right].retry.isEnabled()) return right;
            if (left >= 0 && cards[left] != null && cards[left].retry.isEnabled()) return left;
        }
        return -1;
    }

    private String displayProviderName(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.endsWith("扫码登录")) value = value.substring(0, value.length() - 4).trim();
        else if (value.endsWith("扫码")) value = value.substring(0, value.length() - 2).trim();
        return value.isEmpty() ? raw : value;
    }

    private String shortStatus(CharSequence raw) {
        String value = raw == null ? "" : raw.toString().trim();
        if (value.length() <= 48) return value;
        return value.substring(0, 47).trim() + "…";
    }

    private void closeAndDismiss() {
        notifyClosed();
        dismissAllowingStateLoss();
    }

    private void notifyClosed() {
        if (!closed.compareAndSet(false, true)) return;
        Listener current = listener;
        listener = null;
        clearCodes();
        if (current != null) current.onClosed();
    }

    private void clearCodes() {
        Arrays.fill(codes, null);
        Arrays.fill(statuses, null);
        for (ItemCloudLoginBinding card : cards) {
            if (card != null) card.qr.setImageDrawable(null);
        }
    }

    private void runOnMain(Runnable action) {
        if (Looper.myLooper() == Looper.getMainLooper()) action.run();
        else main.post(action);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    public void onCancel(@NonNull DialogInterface dialog) {
        notifyClosed();
        super.onCancel(dialog);
    }

    @Override
    public void onDismiss(@NonNull DialogInterface dialog) {
        notifyClosed();
        super.onDismiss(dialog);
    }

    @Override
    public void onStop() {
        notifyClosed();
        super.onStop();
    }

    @Override
    public void onDestroyView() {
        notifyClosed();
        main.removeCallbacksAndMessages(null);
        clearCodes();
        Arrays.fill(cards, null);
        providerNames = null;
        binding = null;
        super.onDestroyView();
    }
}

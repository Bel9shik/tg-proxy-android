package app.oneclick.tgproxy;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.StatusBarManager;
import android.content.ComponentName;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import app.oneclick.tgproxy.service.ProxyConfig;
import app.oneclick.tgproxy.service.ProxyService;

/** One-button UI for the proxy that runs entirely on this Android device. */
@SuppressWarnings("deprecation")
public final class MainActivity extends Activity {
    private static final int NOTIFICATION_PERMISSION_REQUEST = 7;
    private static final int BATTERY_OPTIMIZATION_REQUEST = 8;
    private static final String BATTERY_PROMPT_SHOWN = "battery_optimization_prompt_shown";
    private static final String STATE_PENDING_START = "pending_start";
    private static final String INTERNAL_PERMISSION =
            "app.oneclick.tgproxy.permission.INTERNAL_STATE";

    private PowerButton powerButton;
    private TextView status;
    private TextView hint;
    private boolean enabled;
    private boolean busy;
    private boolean pendingStart;
    private boolean receiverRegistered;

    private final BroadcastReceiver stateReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (!ProxyService.ACTION_STATE_CHANGED.equals(intent.getAction())) return;
            String error = intent.getStringExtra(ProxyService.EXTRA_ERROR);
            boolean running = intent.getBooleanExtra(ProxyService.EXTRA_RUNNING, false);
            applyServiceState(running, error, true);
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        pendingStart = state != null && state.getBoolean(STATE_PENDING_START, false);
        Window window = getWindow();
        window.setStatusBarColor(Color.rgb(244, 248, 252));
        window.setNavigationBarColor(Color.rgb(244, 248, 252));
        window.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        setContentView(buildContent());
        enabled = ProxyService.isRunning();
        renderState(false);
    }

    @Override protected void onStart() {
        super.onStart();
        registerStateReceiver();
        receiverRegistered = true;
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private void registerStateReceiver() {
        IntentFilter filter = new IntentFilter(ProxyService.ACTION_STATE_CHANGED);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(
                    stateReceiver,
                    filter,
                    INTERNAL_PERMISSION,
                    null,
                    Context.RECEIVER_NOT_EXPORTED);
        } else {
            // Before Android 13 there is no exported-state flag. The signature
            // permission still prevents broadcasts from third-party apps.
            registerReceiver(stateReceiver, filter, INTERNAL_PERMISSION, null);
        }
    }

    @Override protected void onResume() {
        super.onResume();
        boolean actual = ProxyService.isRunning();
        if (actual != enabled && !busy) {
            enabled = actual;
            renderState(false);
        }
        if (actual && pendingStart) {
            pendingStart = false;
            openTelegramAfterStart();
        }
    }

    @Override protected void onStop() {
        if (receiverRegistered) {
            unregisterReceiver(stateReceiver);
            receiverRegistered = false;
        }
        super.onStop();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putBoolean(STATE_PENDING_START, pendingStart);
        super.onSaveInstanceState(state);
    }

    private View buildContent() {
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(Gravity.CENTER_HORIZONTAL);
        content.setPadding(dp(28), dp(52), dp(28), dp(30));
        content.setBackgroundColor(Color.rgb(244, 248, 252));

        TextView title = text(getString(R.string.app_name), 30, Color.rgb(28, 46, 64));
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        content.addView(title);

        TextView subtitle = text(getString(R.string.subtitle), 15, Color.rgb(106, 125, 143));
        LinearLayout.LayoutParams subtitleParams = wrap();
        subtitleParams.topMargin = dp(7);
        content.addView(subtitle, subtitleParams);

        content.addView(new SpaceView(this), new LinearLayout.LayoutParams(1, 0, 1));

        powerButton = new PowerButton(this);
        powerButton.setOnClickListener(v -> toggle());
        content.addView(powerButton, new LinearLayout.LayoutParams(dp(270), dp(270)));

        status = text("", 13, Color.rgb(110, 129, 146));
        status.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        status.setLetterSpacing(.09f);
        status.setGravity(Gravity.CENTER);
        status.setPadding(dp(18), dp(9), dp(18), dp(9));
        LinearLayout.LayoutParams statusParams = wrap();
        statusParams.topMargin = dp(24);
        content.addView(status, statusParams);

        hint = text("", 14, Color.rgb(118, 135, 151));
        LinearLayout.LayoutParams hintParams = wrap();
        hintParams.topMargin = dp(13);
        content.addView(hint, hintParams);

        TextView endpoint = text(getString(R.string.local_endpoint), 12, Color.rgb(86, 112, 133));
        endpoint.setTypeface(Typeface.MONOSPACE, Typeface.NORMAL);
        endpoint.setPadding(dp(14), dp(8), dp(14), dp(8));
        endpoint.setBackground(pill(Color.rgb(235, 242, 248)));
        LinearLayout.LayoutParams endpointParams = wrap();
        endpointParams.topMargin = dp(22);
        content.addView(endpoint, endpointParams);

        TextView configureTelegram = text(
                getString(R.string.configure_telegram),
                13,
                Color.rgb(42, 145, 205));
        configureTelegram.setContentDescription(getString(R.string.configure_telegram_description));
        configureTelegram.setPadding(dp(18), dp(12), dp(18), dp(12));
        configureTelegram.setOnClickListener(v -> openTelegramProxyLink(true));
        content.addView(configureTelegram, wrap());

        TextView addQuickTile = text(
                getString(R.string.add_quick_settings_tile),
                13,
                Color.rgb(42, 145, 205));
        addQuickTile.setContentDescription(
                getString(R.string.add_quick_settings_tile_description));
        addQuickTile.setPadding(dp(18), dp(12), dp(18), dp(12));
        android.graphics.drawable.Drawable tileIcon = getDrawable(R.drawable.ic_qs_proxy);
        if (tileIcon != null) {
            tileIcon = tileIcon.mutate();
            tileIcon.setTint(Color.rgb(42, 145, 205));
            tileIcon.setBounds(0, 0, dp(20), dp(20));
            addQuickTile.setCompoundDrawables(tileIcon, null, null, null);
            addQuickTile.setCompoundDrawablePadding(dp(8));
        }
        addQuickTile.setOnClickListener(v -> requestQuickSettingsTile());
        content.addView(addQuickTile, wrap());

        content.addView(new SpaceView(this), new LinearLayout.LayoutParams(1, 0, 1));

        TextView privacy = text(getString(R.string.privacy_note), 12, Color.rgb(132, 149, 164));
        content.addView(privacy);
        return content;
    }

    private void toggle() {
        if (busy) return;
        if (enabled) stopProxy();
        else ensurePermissionAndStart();
    }

    private void ensurePermissionAndStart() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                    new String[]{Manifest.permission.POST_NOTIFICATIONS},
                    NOTIFICATION_PERMISSION_REQUEST);
            return;
        }
        ensureBatteryOptimizationAndStart();
    }

    private void ensureBatteryOptimizationAndStart() {
        PowerManager powerManager = getSystemService(PowerManager.class);
        boolean alreadyAllowed = powerManager != null
                && powerManager.isIgnoringBatteryOptimizations(getPackageName());
        boolean promptShown = getSharedPreferences("app_state", MODE_PRIVATE)
                .getBoolean(BATTERY_PROMPT_SHOWN, false);
        if (!alreadyAllowed && !promptShown) {
            getSharedPreferences("app_state", MODE_PRIVATE)
                    .edit().putBoolean(BATTERY_PROMPT_SHOWN, true).apply();
            try {
                Intent request = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                request.setData(Uri.parse("package:" + getPackageName()));
                startActivityForResult(request, BATTERY_OPTIMIZATION_REQUEST);
                return;
            } catch (Exception ignored) {
                // Some vendor firmwares omit the standard dialog. The foreground
                // service can still start; the user can allow it manually later.
            }
        }
        startProxy();
    }

    @Override public void onRequestPermissionsResult(
            int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == NOTIFICATION_PERMISSION_REQUEST) ensureBatteryOptimizationAndStart();
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == BATTERY_OPTIMIZATION_REQUEST) startProxy();
    }

    private void startProxy() {
        setBusy(true, getString(R.string.status_starting));
        pendingStart = true;
        try {
            Intent intent = new Intent(this, ProxyService.class);
            startForegroundService(intent);
        } catch (Exception error) {
            pendingStart = false;
            applyServiceState(false, error.getMessage(), false);
        }
    }

    private void stopProxy() {
        setBusy(true, getString(R.string.status_stopping));
        pendingStart = false;
        try {
            boolean existed = stopService(new Intent(this, ProxyService.class));
            if (!existed) applyServiceState(false, null, true);
        } catch (Exception error) {
            applyServiceState(enabled, error.getMessage(), false);
        }
    }

    private void applyServiceState(boolean running, String error, boolean animate) {
        enabled = running;
        setBusy(false, "");
        renderState(animate);
        if (error != null && !error.isEmpty()) {
            pendingStart = false;
            new AlertDialog.Builder(this)
                    .setTitle(R.string.error_title)
                    .setMessage(error)
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
            return;
        }
        if (running && pendingStart) {
            pendingStart = false;
            openTelegramAfterStart();
        } else if (!running) {
            Toast.makeText(this, R.string.proxy_stopped_toast, Toast.LENGTH_SHORT).show();
        }
    }

    private void openTelegramAfterStart() {
        if (!new ProxyConfig(this).isTelegramVerified()) {
            openTelegramProxyLink(false);
            return;
        }

        openTelegramApp();
    }

    private void openTelegramApp() {
        String[] packages = {
                "org.telegram.messenger",
                "org.telegram.messenger.web",
                "org.telegram.messenger.beta",
                "org.thunderdog.challegram"
        };
        for (String packageName : packages) {
            Intent launch = getPackageManager().getLaunchIntentForPackage(packageName);
            if (launch == null) continue;
            startActivity(launch);
            return;
        }
        showTelegramMissingDialog();
    }

    private void openTelegramProxyLink(boolean clearVerification) {
        ProxyConfig config = new ProxyConfig(this);
        if (clearVerification) config.clearTelegramVerification();
        openUri("tg://proxy?server=127.0.0.1&port=" + config.getPort()
                + "&secret=dd" + config.getSecret());
    }

    private void openUri(String uri) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(uri)));
        } catch (Exception ignored) {
            showTelegramMissingDialog();
        }
    }

    private void showTelegramMissingDialog() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.telegram_missing_title)
                .setMessage(R.string.telegram_missing_message)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    private void requestQuickSettingsTile() {
        if (Build.VERSION.SDK_INT >= 33) {
            StatusBarManager manager = getSystemService(StatusBarManager.class);
            if (manager != null) {
                manager.requestAddTileService(
                        new ComponentName(this, ProxyTileService.class),
                        getString(R.string.quick_settings_tile_label),
                        Icon.createWithResource(this, R.drawable.ic_qs_proxy),
                        getMainExecutor(),
                        result -> {
                            int message = result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED
                                    ? R.string.quick_settings_tile_added
                                    : result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED
                                    ? R.string.quick_settings_tile_already_added
                                    : 0;
                            if (message != 0) {
                                Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
                            }
                        });
                return;
            }
        }

        new AlertDialog.Builder(this)
                .setTitle(R.string.add_quick_settings_tile)
                .setMessage(R.string.quick_settings_tile_manual)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    private void setBusy(boolean value, String message) {
        busy = value;
        powerButton.setBusy(value);
        if (value) {
            status.setText(message);
            hint.setText(R.string.local_operation);
        }
    }

    private void renderState(boolean animate) {
        powerButton.setEnabledState(enabled, animate);
        if (enabled) {
            status.setText(R.string.status_enabled);
            status.setTextColor(Color.rgb(18, 145, 96));
            status.setBackground(pill(Color.rgb(222, 247, 237)));
            hint.setText(R.string.tap_to_disable);
        } else {
            status.setText(R.string.status_disabled);
            status.setTextColor(Color.rgb(101, 122, 140));
            status.setBackground(pill(Color.rgb(231, 238, 244)));
            hint.setText(R.string.tap_to_enable);
        }
    }

    private TextView text(String value, int sp, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(color);
        view.setGravity(Gravity.CENTER);
        return view;
    }

    private GradientDrawable pill(int color) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dp(22));
        return shape;
    }

    private LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static final class SpaceView extends View {
        SpaceView(Context context) { super(context); }
    }
}

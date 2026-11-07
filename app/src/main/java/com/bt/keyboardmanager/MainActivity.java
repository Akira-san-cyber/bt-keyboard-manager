package com.bt.keyboardmanager;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.hardware.input.InputManager;
import android.os.Bundle;
import android.provider.Settings;
import android.view.InputDevice;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity implements InputManager.InputDeviceListener {

    private static final String SHOW_IME_SETTING = "show_ime_with_hard_keyboard";
    private static final String PERMISSION = "android.permission.WRITE_SECURE_SETTINGS";
    private static final String ADB_COMMAND =
            "adb shell pm grant com.bt.keyboardmanager android.permission.WRITE_SECURE_SETTINGS";

    private static final String PREFS = "btkm";
    private static final String KEY_FORCE = "force_hide";
    private static final String KEY_AUTO = "auto_hide";
    private static final String KEY_OVERRIDDEN = "overridden";
    private static final String KEY_ORIGINAL = "original_value";

    private SwitchCompat switchForce, switchAuto;
    private TextView txtKeyboard, txtIme, txtPerm, txtDiag;
    private InputManager inputManager;
    private SharedPreferences prefs;
    private boolean updatingUi = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        inputManager = (InputManager) getSystemService(Context.INPUT_SERVICE);

        switchForce = findViewById(R.id.switchScreenKeyboard);
        switchAuto = findViewById(R.id.switchBtKeyboard);
        txtKeyboard = findViewById(R.id.txtKeyboardStatus);
        txtIme = findViewById(R.id.txtImeStatus);
        txtPerm = findViewById(R.id.txtPermStatus);
        txtDiag = findViewById(R.id.txtDiagnostics);

        switchForce.setOnCheckedChangeListener((b, checked) -> onToggle(KEY_FORCE, checked, switchForce));
        switchAuto.setOnCheckedChangeListener((b, checked) -> onToggle(KEY_AUTO, checked, switchAuto));

        ((Button) findViewById(R.id.btnSystemSettings)).setOnClickListener(v -> openKeyboardSettings());
        ((Button) findViewById(R.id.btnRunDiagnostics)).setOnClickListener(v -> runDiagnostics());
        ((Button) findViewById(R.id.btnHideNow)).setOnClickListener(v -> hideSoftKeyboardNow());
        ((Button) findViewById(R.id.btnCopyAdb)).setOnClickListener(v -> copyAdbCommand());
    }

    @Override
    protected void onStart() {
        super.onStart();
        inputManager.registerInputDeviceListener(this, null);
        refresh();
    }

    @Override
    protected void onStop() {
        super.onStop();
        inputManager.unregisterInputDeviceListener(this);
    }

    @Override public void onInputDeviceAdded(int deviceId) { refresh(); }
    @Override public void onInputDeviceRemoved(int deviceId) { refresh(); }
    @Override public void onInputDeviceChanged(int deviceId) { refresh(); }

    private void onToggle(String key, boolean checked, SwitchCompat sw) {
        if (updatingUi) return;
        if (checked && !hasWritePermission()) {
            updatingUi = true;
            sw.setChecked(false);
            updatingUi = false;
            showPermissionDialog();
            return;
        }
        prefs.edit().putBoolean(key, checked).apply();
        refresh();
    }

    private void applyPolicy() {
        if (!hasWritePermission()) return;
        boolean hide = prefs.getBoolean(KEY_FORCE, false)
                || (prefs.getBoolean(KEY_AUTO, false) && !getHardwareKeyboards().isEmpty());
        try {
            if (hide) {
                if (!prefs.getBoolean(KEY_OVERRIDDEN, false)) {
                    int current = Settings.Secure.getInt(getContentResolver(), SHOW_IME_SETTING, 0);
                    prefs.edit().putBoolean(KEY_OVERRIDDEN, true).putInt(KEY_ORIGINAL, current).apply();
                }
                Settings.Secure.putInt(getContentResolver(), SHOW_IME_SETTING, 0);
            } else if (prefs.getBoolean(KEY_OVERRIDDEN, false)) {
                int original = prefs.getInt(KEY_ORIGINAL, 1);
                Settings.Secure.putInt(getContentResolver(), SHOW_IME_SETTING, original);
                prefs.edit().putBoolean(KEY_OVERRIDDEN, false).apply();
            }
        } catch (SecurityException e) {
            Toast.makeText(this, "Permission was revoked: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void refresh() {
        applyPolicy();

        updatingUi = true;
        switchForce.setChecked(prefs.getBoolean(KEY_FORCE, false) && hasWritePermission());
        switchAuto.setChecked(prefs.getBoolean(KEY_AUTO, false) && hasWritePermission());
        updatingUi = false;

        List<String> keyboards = getHardwareKeyboards();
        txtKeyboard.setText(keyboards.isEmpty()
                ? "Hardware keyboard: none connected"
                : "Hardware keyboard: " + join(keyboards));

        int show = Settings.Secure.getInt(getContentResolver(), SHOW_IME_SETTING, 0);
        txtIme.setText("On-screen keyboard with hardware keyboard: " + (show == 1 ? "shown" : "hidden"));

        txtPerm.setText(hasWritePermission()
                ? "Permission: granted"
                : "Permission: not granted (tap 'Copy ADB Permission Command')");
    }

    private List<String> getHardwareKeyboards() {
        List<String> names = new ArrayList<>();
        for (int id : InputDevice.getDeviceIds()) {
            InputDevice d = InputDevice.getDevice(id);
            if (d == null || d.isVirtual()) continue;
            boolean isKeyboard = (d.getSources() & InputDevice.SOURCE_KEYBOARD) == InputDevice.SOURCE_KEYBOARD;
            if (isKeyboard && d.getKeyboardType() == InputDevice.KEYBOARD_TYPE_ALPHABETIC) {
                names.add(d.getName());
            }
        }
        return names;
    }

    private boolean hasWritePermission() {
        return checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED;
    }

    private void openKeyboardSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_HARD_KEYBOARD_SETTINGS));
        } catch (Exception e) {
            try {
                startActivity(new Intent(Settings.ACTION_INPUT_METHOD_SETTINGS));
            } catch (Exception e2) {
                Toast.makeText(this, "Unable to open keyboard settings", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void hideSoftKeyboardNow() {
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        View v = getCurrentFocus() != null ? getCurrentFocus() : getWindow().getDecorView();
        imm.hideSoftInputFromWindow(v.getWindowToken(), 0);
        if (getCurrentFocus() != null) getCurrentFocus().clearFocus();
        Toast.makeText(this, "Soft keyboard dismissed", Toast.LENGTH_SHORT).show();
    }

    private void copyAdbCommand() {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("adb", ADB_COMMAND));
        Toast.makeText(this, "Command copied", Toast.LENGTH_SHORT).show();
    }

    private void showPermissionDialog() {
        new AlertDialog.Builder(this)
                .setTitle("One-time setup needed")
                .setMessage("Android only lets an app change this setting after you grant it once from a computer:\n\n"
                        + ADB_COMMAND + "\n\n(USB debugging must be enabled.)")
                .setPositiveButton("Copy command", (d, w) -> copyAdbCommand())
                .setNegativeButton("Close", null)
                .show();
    }

    private void runDiagnostics() {
        StringBuilder sb = new StringBuilder();
        sb.append(hasWritePermission() ? "[OK]   " : "[FAIL] ").append("WRITE_SECURE_SETTINGS granted\n");
        List<String> kb = getHardwareKeyboards();
        sb.append(kb.isEmpty() ? "[INFO] " : "[OK]   ").append("Hardware keyboards: ")
                .append(kb.isEmpty() ? "none" : join(kb)).append('\n');
        int show = Settings.Secure.getInt(getContentResolver(), SHOW_IME_SETTING, -1);
        sb.append("[INFO] ").append(SHOW_IME_SETTING).append(" = ").append(show).append('\n');
        sb.append("[INFO] Force-hide: ").append(prefs.getBoolean(KEY_FORCE, false)).append('\n');
        sb.append("[INFO] Auto-hide: ").append(prefs.getBoolean(KEY_AUTO, false)).append('\n');
        sb.append("[INFO] Original value saved: ").append(prefs.getBoolean(KEY_OVERRIDDEN, false)
                ? String.valueOf(prefs.getInt(KEY_ORIGINAL, -1)) : "n/a (not overriding)").append('\n');
        txtDiag.setText(sb.toString());
    }

    private static String join(List<String> items) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(items.get(i));
        }
        return sb.toString();
    }
}

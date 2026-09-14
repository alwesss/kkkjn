package com.example.soyotest;

import android.app.Activity;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.view.accessibility.AccessibilityManager;

import java.util.List;

public class MainActivity extends Activity {
    private EditText messageInput;
    private EditText packageInput;
    private EditText ttlInput;
    private TextView statusView;
    private TextView statsView;
    private final Runnable refresher = new Runnable() {
        @Override public void run() {
            refreshStatus();
            if (statusView != null) statusView.postDelayed(this, 750);
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        SharedPreferences p = AutomationPrefs.get(this);

        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(20), dp(20), dp(32));
        scroll.addView(root);

        TextView title = text("SOYO MESAJ YARDIMCISI v1.3.1", 24, true);
        root.addView(title);

        TextView developer = text("Developed by Alves", 13, true);
        developer.setTextColor(Color.DKGRAY);
        developer.setPadding(0, dp(3), 0, dp(2));
        root.addView(developer);
        TextView sub = text("Onerilenler -> Sohbet -> Mesaj -> Geri -> Siradaki profil akisini otomatik yurutur.", 14, false);
        sub.setPadding(0, dp(6), 0, dp(18));
        root.addView(sub);

        root.addView(label("Gonderilecek mesaj"));
        messageInput = new EditText(this);
        messageInput.setMinLines(3);
        messageInput.setGravity(Gravity.TOP);
        messageInput.setText(p.getString(AutomationPrefs.KEY_MESSAGE, "Merhaba"));
        root.addView(messageInput, fullWrap());

        root.addView(label("Hedef SOYO paket adi"));
        packageInput = new EditText(this);
        packageInput.setSingleLine(true);
        packageInput.setText(p.getString(AutomationPrefs.KEY_TARGET, "com.haflla.soulu"));
        root.addView(packageInput, fullWrap());

        root.addView(label("Ayni profile tekrar yazma suresi (dakika)"));
        ttlInput = new EditText(this);
        ttlInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        ttlInput.setText(String.valueOf(p.getInt(AutomationPrefs.KEY_TTL, 60)));
        root.addView(ttlInput, fullWrap());

        Button save = button("AYARLARI KAYDET");
        save.setOnClickListener(v -> saveSettings());
        root.addView(save, fullWrap());

        Button accessibility = button("ERISILEBILIRLIK AYARLARINI AC");
        accessibility.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        root.addView(accessibility, fullWrap());

        Button start = button("OTOMASYONU BASLAT");
        start.setOnClickListener(v -> {
            if (!saveSettings()) return;
            if (!isAccessibilityServiceEnabled()) {
                Toast.makeText(this, "Once Soyo Mesaj Yardimcisi erisilebilirlik servisini etkinlestirin.", Toast.LENGTH_LONG).show();
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
                return;
            }
            AutomationPrefs.get(this).edit()
                    .putBoolean(AutomationPrefs.KEY_RUNNING, true)
                    .putString(AutomationPrefs.KEY_STATUS, "Baslatildi - SOYO ekranini bekliyor")
                    .apply();
            sendBroadcast(new Intent(SoyoTestAccessibilityService.ACTION_WAKE).setPackage(getPackageName()));
            refreshStatus();
        });
        root.addView(start, fullWrap());

        Button stop = button("DURDUR");
        stop.setOnClickListener(v -> {
            AutomationPrefs.get(this).edit()
                    .putBoolean(AutomationPrefs.KEY_RUNNING, false)
                    .putString(AutomationPrefs.KEY_STATUS, "Durduruldu")
                    .apply();
            sendBroadcast(new Intent(SoyoTestAccessibilityService.ACTION_WAKE).setPackage(getPackageName()));
            refreshStatus();
        });
        root.addView(stop, fullWrap());

        statusView = text("", 16, true);
        statusView.setPadding(0, dp(22), 0, dp(8));
        root.addView(statusView);
        statsView = text("", 14, false);
        root.addView(statsView);

        TextView note = text("Otomasyon liste sonunda kapanmaz; basa sarip devam eder. Ayni profile tekrar mesaj gonderme araligi yukaridaki sureyle sinirlanir.", 12, false);
        note.setTextColor(Color.DKGRAY);
        note.setPadding(0, dp(24), 0, 0);
        root.addView(note);

        TextView credit = text("Designed & Developed by Alves", 12, true);
        credit.setTextColor(Color.DKGRAY);
        credit.setGravity(Gravity.CENTER);
        credit.setPadding(0, dp(28), 0, dp(4));
        root.addView(credit);

        setContentView(scroll);
    }

    @Override protected void onResume() {
        super.onResume();
        if (statusView != null) statusView.post(refresher);
    }

    @Override protected void onPause() {
        if (statusView != null) statusView.removeCallbacks(refresher);
        super.onPause();
    }

    private boolean saveSettings() {
        String msg = messageInput.getText().toString().trim();
        String pkg = packageInput.getText().toString().trim();
        if (msg.isEmpty() || pkg.isEmpty()) {
            Toast.makeText(this, "Mesaj ve paket adi bos olamaz.", Toast.LENGTH_SHORT).show();
            return false;
        }
        int ttl = 60;
        try { ttl = Integer.parseInt(ttlInput.getText().toString().trim()); } catch (Exception ignored) {}
        if (ttl < 1) ttl = 60;
        AutomationPrefs.get(this).edit()
                .putString(AutomationPrefs.KEY_MESSAGE, msg)
                .putString(AutomationPrefs.KEY_TARGET, pkg)
                .putInt(AutomationPrefs.KEY_TTL, ttl)
                .apply();
        Toast.makeText(this, "Ayarlar kaydedildi.", Toast.LENGTH_SHORT).show();
        return true;
    }

    private void refreshStatus() {
        SharedPreferences p = AutomationPrefs.get(this);
        boolean running = p.getBoolean(AutomationPrefs.KEY_RUNNING, false);
        String status = p.getString(AutomationPrefs.KEY_STATUS, "Hazir");
        int sent = p.getInt(AutomationPrefs.KEY_SENT, 0);
        int skipped = p.getInt(AutomationPrefs.KEY_SKIPPED, 0);
        String last = p.getString(AutomationPrefs.KEY_LAST, "-");
        boolean serviceEnabled = isAccessibilityServiceEnabled();
        statusView.setText("Durum: " + (running ? "CALISIYOR" : "BEKLIYOR")
                + "\nErisilebilirlik: " + (serviceEnabled ? "ACIK" : "KAPALI")
                + "\n" + status);
        statsView.setText("Gonderilen: " + sent + "   Atlanan: " + skipped + "\nSon profil: " + last);
    }

    private boolean isAccessibilityServiceEnabled() {
        AccessibilityManager am = (AccessibilityManager) getSystemService(ACCESSIBILITY_SERVICE);
        if (am == null || !am.isEnabled()) return false;
        List<AccessibilityServiceInfo> services = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK);
        if (services == null) return false;
        for (AccessibilityServiceInfo info : services) {
            if (info == null || info.getResolveInfo() == null || info.getResolveInfo().serviceInfo == null) continue;
            String pkg = info.getResolveInfo().serviceInfo.packageName;
            String name = info.getResolveInfo().serviceInfo.name;
            if (getPackageName().equals(pkg) && SoyoTestAccessibilityService.class.getName().equals(name)) return true;
        }
        return false;
    }

    private TextView label(String s) {
        TextView v = text(s, 14, true);
        v.setPadding(0, dp(14), 0, dp(4));
        return v;
    }

    private TextView text(String s, int sp, boolean bold) {
        TextView v = new TextView(this);
        v.setText(s);
        v.setTextSize(sp);
        v.setTextColor(Color.BLACK);
        if (bold) v.setTypeface(v.getTypeface(), android.graphics.Typeface.BOLD);
        return v;
    }

    private Button button(String s) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setPadding(dp(8), dp(8), dp(8), dp(8));
        return b;
    }

    private LinearLayout.LayoutParams fullWrap() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, dp(6), 0, dp(6));
        return lp;
    }

    private int dp(int n) {
        return (int) (n * getResources().getDisplayMetrics().density + 0.5f);
    }
}

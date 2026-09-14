package com.example.soyotest;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class SoyoTestAccessibilityService extends AccessibilityService {
    public static final String ACTION_WAKE = "com.example.soyotest.WAKE";

    private enum State { IDLE, LIST_SCAN, OPENING_CHAT, WAITING_INPUT, SENDING, RETURNING, SCROLLING }

    private final Handler h = new Handler(Looper.getMainLooper());
    private State state = State.IDLE;
    private long stateSince = 0L;
    private String activeProfileKey = null;
    private String activeProfileLabel = null;
    private String lastPageFingerprint = "";
    private String scrollFingerprint = "";
    private boolean checkScrollResult = false;
    private int samePageCount = 0;
    private int sendAttempt = 0;
    private int messageMatchCountBeforeSend = 0;
    private long lastBackActionAt = 0L;
    private boolean rewindingToTop = false;
    private boolean receiverRegistered = false;
    private final Set<String> attemptedThisPage = new HashSet<>();

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            try { step(); } catch (Throwable t) { setStatus("Hata yakalandi, tekrar taraniyor: " + t.getClass().getSimpleName()); recoverToList(); }
            h.postDelayed(this, 420);
        }
    };

    private final BroadcastReceiver wakeReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (!AutomationPrefs.running(SoyoTestAccessibilityService.this)) resetState();
            else if (state == State.IDLE) transition(State.LIST_SCAN, "Baslatildi");
        }
    };

    @Override public void onServiceConnected() {
        super.onServiceConnected();
        if (!receiverRegistered) {
            IntentFilter f = new IntentFilter(ACTION_WAKE);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(wakeReceiver, f, Context.RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(wakeReceiver, f);
            }
            receiverRegistered = true;
        }
        h.removeCallbacks(tick);
        h.post(tick);
        setStatus("Erisilebilirlik servisi hazir");
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || !AutomationPrefs.running(this)) return;
        CharSequence pkg = event.getPackageName();
        if (pkg != null && !AutomationPrefs.target(this).equals(pkg.toString())) return;
        if (state == State.IDLE) transition(State.LIST_SCAN, "SOYO ekrani algilandi");
    }

    @Override public void onInterrupt() {
        setStatus("Servis kesintiye ugradi");
    }

    @Override public void onDestroy() {
        h.removeCallbacks(tick);
        if (receiverRegistered) {
            try { unregisterReceiver(wakeReceiver); } catch (Exception ignored) {}
        }
        super.onDestroy();
    }

    private void step() {
        if (!AutomationPrefs.running(this)) {
            if (state != State.IDLE) resetState();
            return;
        }
        String target = AutomationPrefs.target(this);
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null || root.getPackageName() == null) {
            setStatus("Aktif pencere okunamiyor - erisilebilirlik iznini kontrol edin");
            return;
        }
        String foregroundPackage = root.getPackageName().toString();
        if (!target.equals(foregroundPackage)) {
            setStatus("SOYO bekleniyor. On plandaki paket: " + foregroundPackage);
            return;
        }

        pruneExpiredRecipients();

        switch (state) {
            case IDLE:
                transition(State.LIST_SCAN, "Onerilenler taraniyor");
                break;
            case LIST_SCAN:
                scanList(root);
                break;
            case OPENING_CHAT:
            case WAITING_INPUT:
                handleChatOpening(root);
                break;
            case SENDING:
                verifyAndReturn(root);
                break;
            case RETURNING:
                handleReturn(root);
                break;
            case SCROLLING:
                if (elapsed() > 1050) transition(State.LIST_SCAN, "Kaydirma sonrasi yeniden taraniyor");
                break;
        }
    }

    private void scanList(AccessibilityNodeInfo root) {
        if (containsEditText(root)) {
            if (elapsed() > 1800) performGlobalAction(GLOBAL_ACTION_BACK);
            return;
        }

        List<AccessibilityNodeInfo> chatButtons = findTextNodes(root, "sohbet");
        List<Candidate> candidates = new ArrayList<>();
        for (AccessibilityNodeInfo n : chatButtons) {
            Candidate c = candidateFromChatButton(n);
            if (c != null) candidates.add(c);
        }
        Collections.sort(candidates, Comparator.comparingInt(c -> c.top));

        String fingerprint = pageFingerprint(candidates);
        if (checkScrollResult) {
            if (fingerprint.equals(scrollFingerprint)) samePageCount++;
            else samePageCount = 0;
            checkScrollResult = false;
        }
        lastPageFingerprint = fingerprint;

        // Listenin sonuna gelince artik otomasyonu KAPATMIYORUZ.
        // Basa kadar geri sarip yeni bir tur baslatiyoruz.
        if (rewindingToTop) {
            if (samePageCount >= 2) {
                rewindingToTop = false;
                samePageCount = 0;
                lastPageFingerprint = "";
                attemptedThisPage.clear();
                transition(State.LIST_SCAN, "Liste basina donuldu - yeni tur basliyor");
                return;
            }
            attemptedThisPage.clear();
            rememberScrollFingerprint(fingerprint);
            transition(State.SCROLLING, "Liste basina sariliyor");
            scrollList(root, false);
            return;
        }

        for (Candidate c : candidates) {
            if (attemptedThisPage.contains(c.key)) continue;
            if (wasSentRecently(c.key)) {
                attemptedThisPage.add(c.key);
                incrementSkipped();
                continue;
            }
            attemptedThisPage.add(c.key);
            activeProfileKey = c.key;
            activeProfileLabel = c.label;
            sendAttempt = 0;
            messageMatchCountBeforeSend = 0;
            setLastProfile(c.label);
            if (clickNodeOrTap(c.button)) {
                transition(State.OPENING_CHAT, "Sohbet aciliyor: " + c.label);
                return;
            }
        }

        if (samePageCount >= 4) {
            rewindingToTop = true;
            samePageCount = 0;
            lastPageFingerprint = "";
            attemptedThisPage.clear();
            rememberScrollFingerprint(fingerprint);
            transition(State.SCROLLING, "Listenin sonu - durmadan devam etmek icin basa sariliyor");
            scrollList(root, false);
            return;
        }

        attemptedThisPage.clear();
        rememberScrollFingerprint(fingerprint);
        transition(State.SCROLLING, "Yeni profiller icin kaydiriliyor");
        scrollList(root, true);
    }

    private void handleChatOpening(AccessibilityNodeInfo root) {
        AccessibilityNodeInfo edit = findBestEditText(root);
        if (edit != null) {
            if (state != State.WAITING_INPUT) transition(State.WAITING_INPUT, "Mesaj alani bulundu: " + safeLabel());
            String msg = AutomationPrefs.message(this);
            if (!sameText(edit.getText(), msg)) {
                Bundle args = new Bundle();
                args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, msg);
                if (!edit.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
                    edit.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
                    return;
                }
                // Compose/React Native arayuzlerinde gonder ikonu metin degisince bir sonraki
                // accessibility agacinda aktif oluyor. Ayni eski root ile tiklamaya calismayalim.
                setStatus("Mesaj yazildi, gonder dugmesi bekleniyor: " + safeLabel());
                return;
            }
            messageMatchCountBeforeSend = findTextNodes(root, msg.toLowerCase(Locale.ROOT)).size();
            if (tryPressSend(root, edit)) {
                sendAttempt++;
                transition(State.SENDING, "Mesaj gonderiliyor: " + safeLabel());
                return;
            }
        }

        if (elapsed() > 5500) {
            incrementSkipped();
            setStatus("Sohbet/mesaj alani bulunamadi, profil atlandi: " + safeLabel());
            recoverToList();
        }
    }

    private void verifyAndReturn(AccessibilityNodeInfo root) {
        AccessibilityNodeInfo edit = findBestEditText(root);
        String msg = AutomationPrefs.message(this);
        boolean inputCleared = edit == null || edit.getText() == null || edit.getText().toString().trim().isEmpty();
        int messageMatchesNow = findTextNodes(root, msg.toLowerCase(Locale.ROOT)).size();
        boolean newMessageAppeared = messageMatchesNow > messageMatchCountBeforeSend;
        if (inputCleared || newMessageAppeared) {
            markSent(activeProfileKey);
            incrementSent();
            performGlobalAction(GLOBAL_ACTION_BACK);
            transition(State.RETURNING, "Gonderildi, listeye donuluyor: " + safeLabel());
            return;
        }
        if (elapsed() > 3500) {
            if (sendAttempt < 4) {
                if (tryPressSend(root, edit)) {
                    sendAttempt++;
                    stateSince = System.currentTimeMillis();
                    setStatus("Gonder dugmesi yeniden denendi (" + sendAttempt + "/4): " + safeLabel());
                    return;
                }
            }
            incrementSkipped();
            setStatus("Mesaj gonderimi dogrulanamadi, profil atlandi: " + safeLabel());
            recoverToList();
        }
    }

    private void handleReturn(AccessibilityNodeInfo root) {
        if (!containsEditText(root) && !findTextNodes(root, "sohbet").isEmpty()) {
            activeProfileKey = null;
            activeProfileLabel = null;
            transition(State.LIST_SCAN, "Siradaki profil araniyor");
            return;
        }
        long now = System.currentTimeMillis();
        if (elapsed() > 2400 && now - lastBackActionAt > 2000) {
            performGlobalAction(GLOBAL_ACTION_BACK);
            lastBackActionAt = now;
        }
        if (elapsed() > 6000) transition(State.LIST_SCAN, "Liste yeniden taraniyor");
    }

    private Candidate candidateFromChatButton(AccessibilityNodeInfo button) {
        AccessibilityNodeInfo row = button;
        for (int depth = 0; depth < 5 && row != null; depth++) {
            String combined = collectText(row, 0, 3);
            if (combined.length() >= 3 && !combined.equalsIgnoreCase("sohbet")) {
                Rect b = new Rect();
                row.getBoundsInScreen(b);
                String cleaned = normalizeRowText(combined);
                if (!cleaned.isEmpty()) {
                    String key = sha256(cleaned);
                    String label = firstMeaningfulLine(cleaned);
                    return new Candidate(button, key, label, b.top);
                }
            }
            row = row.getParent();
        }
        return null;
    }

    private String normalizeRowText(String s) {
        if (s == null) return "";
        String x = s.replaceAll("(?i)\\bsohbet\\b", " ")
                .replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
        return x;
    }

    private String firstMeaningfulLine(String cleaned) {
        if (cleaned == null || cleaned.isEmpty()) return "bilinmeyen profil";
        String[] parts = cleaned.split(" ");
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p.matches(".*\\d.*") || p.contains("km")) break;
            if (sb.length() > 0) sb.append(' ');
            sb.append(p);
            if (sb.length() > 40) break;
        }
        String out = sb.toString().trim();
        return out.isEmpty() ? cleaned.substring(0, Math.min(cleaned.length(), 40)) : out;
    }

    private AccessibilityNodeInfo findSendButton(AccessibilityNodeInfo root, AccessibilityNodeInfo edit) {
        String[] words = {"gonder", "gönder", "send", "mesaj gonder", "mesaj gönder"};
        for (String w : words) {
            List<AccessibilityNodeInfo> exact = findTextNodes(root, w);
            for (AccessibilityNodeInfo n : exact) {
                if (n == null || n == edit || n.isEditable()) continue;
                if (n.isVisibleToUser() && n.isEnabled() && isNearComposer(n, edit)) return n;
            }
        }

        List<AccessibilityNodeInfo> all = flatten(root, 2400);
        Rect eb = new Rect();
        if (edit != null) edit.getBoundsInScreen(eb);
        AccessibilityNodeInfo best = null;
        int bestScore = Integer.MAX_VALUE;
        for (AccessibilityNodeInfo n : all) {
            if (n == null || n == edit || n.isEditable() || !n.isVisibleToUser() || !n.isEnabled()) continue;

            String ds = nodeText(n).toLowerCase(Locale.ROOT);
            String viewId = n.getViewIdResourceName();
            String id = viewId == null ? "" : viewId.toLowerCase(Locale.ROOT);
            if ((ds.contains("gonder") || ds.contains("gönder") || ds.contains("send")
                    || id.contains("send") || id.contains("gonder")) && isNearComposer(n, edit)) {
                return n;
            }

            if (edit != null) {
                Rect nb = new Rect();
                n.getBoundsInScreen(nb);
                if (nb.isEmpty() || nb.centerX() <= eb.centerX()) continue;
                if (nb.centerX() < eb.right + dp(28)) continue;
                int dy = Math.abs(nb.centerY() - eb.centerY());
                int dx = nb.centerX() - eb.centerX();
                boolean clickable = isClickableTree(n);
                boolean buttonLike = false;
                CharSequence cls = n.getClassName();
                if (cls != null) {
                    String c = cls.toString().toLowerCase(Locale.ROOT);
                    buttonLike = c.contains("button") || c.contains("image");
                }
                if (!clickable && !buttonLike) continue;
                int score = dy * 5 + (getResources().getDisplayMetrics().widthPixels - nb.centerX());
                if (clickable) score -= 120;
                if (buttonLike) score -= 80;
                if (dy < Math.max(dp(90), eb.height() * 2) && dx < getResources().getDisplayMetrics().widthPixels / 2
                        && score < bestScore) {
                    bestScore = score;
                    best = n;
                }
            }
        }
        return best;
    }

    private boolean isNearComposer(AccessibilityNodeInfo n, AccessibilityNodeInfo edit) {
        if (n == null) return false;
        if (edit == null) return true;
        Rect eb = new Rect();
        Rect nb = new Rect();
        edit.getBoundsInScreen(eb);
        n.getBoundsInScreen(nb);
        if (eb.isEmpty() || nb.isEmpty()) return false;
        int dy = Math.abs(nb.centerY() - eb.centerY());
        int allowedDy = Math.max(dp(110), eb.height() * 2);
        return dy <= allowedDy && nb.centerX() >= eb.centerX() - dp(24);
    }

    private boolean tryPressSend(AccessibilityNodeInfo root, AccessibilityNodeInfo edit) {
        AccessibilityNodeInfo send = findSendButton(root, edit);
        if (send != null && clickNodeOrTap(send)) return true;

        // Bazi uygulamalar gonder ikonunu accessibility agacina hic koymuyor.
        // Son care olarak mesaj kutusunun hizasinda sag taraftaki gonder alanina dokun.
        if (edit != null && edit.isVisibleToUser()) {
            Rect eb = new Rect();
            edit.getBoundsInScreen(eb);
            int w = getResources().getDisplayMetrics().widthPixels;
            int hgt = getResources().getDisplayMetrics().heightPixels;
            int x = Math.min(w - dp(8), Math.max(eb.right + dp(32), w - dp(30)));
            int y = Math.max(dp(24), Math.min(hgt - dp(24), eb.centerY()));
            return tapAt(x, y);
        }
        return false;
    }

    private AccessibilityNodeInfo findBestEditText(AccessibilityNodeInfo root) {
        List<AccessibilityNodeInfo> all = flatten(root, 1800);
        AccessibilityNodeInfo best = null;
        int bestBottom = -1;
        for (AccessibilityNodeInfo n : all) {
            CharSequence cls = n.getClassName();
            boolean isEdit = cls != null && cls.toString().contains("EditText");
            if (!isEdit && !n.isEditable()) continue;
            Rect r = new Rect(); n.getBoundsInScreen(r);
            if (r.bottom > bestBottom) { bestBottom = r.bottom; best = n; }
        }
        return best;
    }

    private boolean containsEditText(AccessibilityNodeInfo root) {
        return findBestEditText(root) != null;
    }

    private void scrollList(AccessibilityNodeInfo root, boolean forward) {
        // Bir onceki accessibility kaydirmasi ekrani degistirmediyse ayni aksiyona
        // takilip kalma; bu turda fiziksel swipe yedegini zorla.
        boolean forceGesture = samePageCount > 0;
        if (!forceGesture) {
            int action = forward ? AccessibilityNodeInfo.ACTION_SCROLL_FORWARD : AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD;
            AccessibilityNodeInfo best = findBestScrollable(root);
            if (best != null && best.performAction(action)) {
                setStatus(forward ? "Liste accessibility ile asagi kaydirildi" : "Liste accessibility ile yukari sarildi");
                return;
            }
        }
        swipeVertical(forward);
    }

    private AccessibilityNodeInfo findBestScrollable(AccessibilityNodeInfo root) {
        AccessibilityNodeInfo best = null;
        int bestArea = -1;
        for (AccessibilityNodeInfo n : flatten(root, 2400)) {
            if (n == null || !n.isVisibleToUser() || !n.isScrollable()) continue;
            Rect r = new Rect();
            n.getBoundsInScreen(r);
            int area = Math.max(0, r.width()) * Math.max(0, r.height());
            if (area > bestArea) {
                bestArea = area;
                best = n;
            }
        }
        return best;
    }

    private void swipeVertical(boolean forward) {
        int w = getResources().getDisplayMetrics().widthPixels;
        int hgt = getResources().getDisplayMetrics().heightPixels;
        float x = w * 0.50f;
        float startY = forward ? hgt * 0.78f : hgt * 0.30f;
        float endY = forward ? hgt * 0.34f : hgt * 0.76f;
        Path p = new Path();
        p.moveTo(x, startY);
        p.lineTo(x, endY);
        GestureDescription.StrokeDescription stroke = new GestureDescription.StrokeDescription(p, 0, 420);
        GestureDescription g = new GestureDescription.Builder().addStroke(stroke).build();
        boolean accepted = dispatchGesture(g, new GestureResultCallback() {
            @Override public void onCompleted(GestureDescription gestureDescription) {
                setStatus(forward ? "Liste hareketi tamamlandi - tarama devam ediyor" : "Liste basa sariliyor");
            }
            @Override public void onCancelled(GestureDescription gestureDescription) {
                setStatus("Kaydirma iptal oldu - sonraki turda tekrar denenecek");
            }
        }, null);
        if (!accepted) setStatus("Kaydirma baslatilamadi - otomasyon yeniden deneyecek");
    }

    private void recoverToList() {
        performGlobalAction(GLOBAL_ACTION_BACK);
        transition(State.RETURNING, "Hata sonrasi listeye donuluyor");
    }

    private List<AccessibilityNodeInfo> findTextNodes(AccessibilityNodeInfo root, String needleLower) {
        if (root == null || needleLower == null) return Collections.emptyList();
        String q = needleLower.toLowerCase(Locale.ROOT);
        List<AccessibilityNodeInfo> out = new ArrayList<>();
        for (AccessibilityNodeInfo n : flatten(root, 2200)) {
            String t = nodeText(n).toLowerCase(Locale.ROOT);
            if (t.equals(q) || t.contains(q)) out.add(n);
        }
        return out;
    }

    private List<AccessibilityNodeInfo> flatten(AccessibilityNodeInfo root, int cap) {
        List<AccessibilityNodeInfo> out = new ArrayList<>();
        if (root == null) return out;
        ArrayList<AccessibilityNodeInfo> q = new ArrayList<>();
        q.add(root);
        for (int i = 0; i < q.size() && out.size() < cap; i++) {
            AccessibilityNodeInfo n = q.get(i);
            out.add(n);
            for (int c = 0; c < n.getChildCount(); c++) {
                AccessibilityNodeInfo child = n.getChild(c);
                if (child != null) q.add(child);
            }
        }
        return out;
    }

    private String collectText(AccessibilityNodeInfo n, int depth, int maxDepth) {
        if (n == null || depth > maxDepth) return "";
        LinkedHashSet<String> parts = new LinkedHashSet<>();
        String self = nodeText(n).trim();
        if (!self.isEmpty()) parts.add(self);
        if (depth < maxDepth) {
            for (int i = 0; i < n.getChildCount(); i++) {
                String child = collectText(n.getChild(i), depth + 1, maxDepth).trim();
                if (!child.isEmpty()) parts.add(child);
            }
        }
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(p);
            if (sb.length() > 500) break;
        }
        return sb.toString();
    }

    private String nodeText(AccessibilityNodeInfo n) {
        if (n == null) return "";
        StringBuilder sb = new StringBuilder();
        if (n.getText() != null) sb.append(n.getText());
        if (n.getContentDescription() != null) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(n.getContentDescription());
        }
        return sb.toString();
    }

    private boolean clickNodeOrParent(AccessibilityNodeInfo n) {
        AccessibilityNodeInfo x = n;
        for (int i = 0; i < 6 && x != null; i++) {
            if (x.isEnabled() && x.isClickable() && x.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true;
            x = x.getParent();
        }
        return false;
    }

    private boolean clickNodeOrTap(AccessibilityNodeInfo n) {
        if (n == null) return false;
        if (clickNodeOrParent(n)) return true;

        AccessibilityNodeInfo x = n;
        for (int i = 0; i < 4 && x != null; i++) {
            Rect r = new Rect();
            x.getBoundsInScreen(r);
            if (!r.isEmpty() && x.isVisibleToUser() && tapAt(r.centerX(), r.centerY())) return true;
            x = x.getParent();
        }
        return false;
    }

    private boolean tapAt(int x, int y) {
        int w = getResources().getDisplayMetrics().widthPixels;
        int hgt = getResources().getDisplayMetrics().heightPixels;
        if (x < 0 || y < 0 || x >= w || y >= hgt) return false;
        Path p = new Path();
        p.moveTo(x, y);
        GestureDescription.StrokeDescription stroke = new GestureDescription.StrokeDescription(p, 0, 70);
        GestureDescription g = new GestureDescription.Builder().addStroke(stroke).build();
        return dispatchGesture(g, null, null);
    }

    private boolean isClickableTree(AccessibilityNodeInfo n) {
        AccessibilityNodeInfo x = n;
        for (int i = 0; i < 3 && x != null; i++) {
            if (x.isClickable()) return true;
            x = x.getParent();
        }
        return false;
    }

    private String pageFingerprint(List<Candidate> list) {
        if (list == null || list.isEmpty()) return "__EMPTY_PAGE__";
        StringBuilder sb = new StringBuilder();
        for (Candidate c : list) sb.append(c.key).append('|');
        return sb.toString();
    }

    private void rememberScrollFingerprint(String fingerprint) {
        scrollFingerprint = fingerprint == null ? "" : fingerprint;
        checkScrollResult = true;
    }

    private boolean wasSentRecently(String key) {
        if (key == null) return false;
        SharedPreferences p = AutomationPrefs.get(this);
        long ts = p.getLong("recipient_" + key, 0L);
        return ts > 0 && System.currentTimeMillis() - ts < AutomationPrefs.ttlMillis(this);
    }

    private void markSent(String key) {
        if (key == null) return;
        AutomationPrefs.get(this).edit().putLong("recipient_" + key, System.currentTimeMillis()).apply();
    }

    private void pruneExpiredRecipients() {
        SharedPreferences p = AutomationPrefs.get(this);
        long now = System.currentTimeMillis();
        long ttl = AutomationPrefs.ttlMillis(this);
        SharedPreferences.Editor e = null;
        for (String k : p.getAll().keySet()) {
            if (!k.startsWith("recipient_")) continue;
            Object v = p.getAll().get(k);
            if (v instanceof Long && now - (Long) v >= ttl) {
                if (e == null) e = p.edit();
                e.remove(k);
            }
        }
        if (e != null) e.apply();
    }

    private void incrementSent() {
        SharedPreferences p = AutomationPrefs.get(this);
        p.edit().putInt(AutomationPrefs.KEY_SENT, p.getInt(AutomationPrefs.KEY_SENT, 0) + 1).apply();
    }

    private void incrementSkipped() {
        SharedPreferences p = AutomationPrefs.get(this);
        p.edit().putInt(AutomationPrefs.KEY_SKIPPED, p.getInt(AutomationPrefs.KEY_SKIPPED, 0) + 1).apply();
    }

    private void setLastProfile(String s) {
        AutomationPrefs.get(this).edit().putString(AutomationPrefs.KEY_LAST, s == null ? "-" : s).apply();
    }

    private void transition(State next, String status) {
        state = next;
        stateSince = System.currentTimeMillis();
        if (next == State.RETURNING) lastBackActionAt = 0L;
        setStatus(status);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private long elapsed() { return System.currentTimeMillis() - stateSince; }

    private void resetState() {
        state = State.IDLE;
        activeProfileKey = null;
        activeProfileLabel = null;
        lastPageFingerprint = "";
        scrollFingerprint = "";
        checkScrollResult = false;
        samePageCount = 0;
        rewindingToTop = false;
        messageMatchCountBeforeSend = 0;
        attemptedThisPage.clear();
        lastBackActionAt = 0L;
        stateSince = System.currentTimeMillis();
    }

    private String safeLabel() { return activeProfileLabel == null ? "profil" : activeProfileLabel; }

    private void setStatus(String s) {
        AutomationPrefs.get(this).edit().putString(AutomationPrefs.KEY_STATUS, s == null ? "" : s).apply();
    }

    private boolean sameText(CharSequence a, String b) {
        String x = a == null ? "" : a.toString();
        String y = b == null ? "" : b;
        return x.equals(y);
    }

    private String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 12 && i < d.length; i++) sb.append(String.format(Locale.ROOT, "%02x", d[i]));
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(s.hashCode());
        }
    }

    private static final class Candidate {
        final AccessibilityNodeInfo button;
        final String key;
        final String label;
        final int top;
        Candidate(AccessibilityNodeInfo button, String key, String label, int top) {
            this.button = button; this.key = key; this.label = label; this.top = top;
        }
    }
}

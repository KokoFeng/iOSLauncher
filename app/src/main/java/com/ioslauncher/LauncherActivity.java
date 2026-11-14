package com.ioslauncher;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.ActivityOptions;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.ScrollView;
import android.widget.Space;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

public class LauncherActivity extends Activity {

    static class App {
        String label;
        String pkg;
        ComponentName cn;
        Drawable icon;
        int cat;
    }

    static class Cell {
        App app;
        FrameLayout view;
        View badge;
        ObjectAnimator anim;
    }

    static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;
    static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;
    static final String[] CAT_ORDER = {
            "Sosial", "Produktivitas", "Hiburan", "Foto & Video", "Game", "Berita", "Peta", "Lainnya"
    };

    final List<App> all = new ArrayList<>();
    final List<Cell> homeCells = new ArrayList<>();
    final Map<String, Bitmap> cache = new HashMap<>();
    final Random rnd = new Random();

    SharedPreferences prefs;
    FrameLayout root;
    Pager pager;
    LinearLayout dots;
    TextView doneBtn;
    FrameLayout overlay;
    PopupWindow popup;

    boolean editMode = false;
    boolean built = false;
    int W, H, topInset, botInset, pageCount = 1;
    float d = 1f;

    final BroadcastReceiver pkgReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            loadApps();
            rebuildKeep();
        }
    };

    // ------------------------------------------------------------------ lifecycle

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("launcher", MODE_PRIVATE);
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);

        root = new FrameLayout(this);
        root.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULL_SCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        root.setOnApplyWindowInsetsListener((v, ins) -> {
            int t = ins.getSystemWindowInsetTop();
            int bt = ins.getSystemWindowInsetBottom();
            if (!built || t != topInset || bt != botInset) {
                topInset = t;
                botInset = bt;
                buildUi();
            }
            return ins;
        });
        setContentView(root);

        loadApps();

        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_PACKAGE_ADDED);
        f.addAction(Intent.ACTION_PACKAGE_REMOVED);
        f.addAction(Intent.ACTION_PACKAGE_CHANGED);
        f.addDataScheme("package");
        registerReceiver(pkgReceiver, f);

        root.post(() -> {
            if (!built) buildUi();
        });
    }

    @Override
    protected void onDestroy() {
        try {
            unregisterReceiver(pkgReceiver);
        } catch (Exception ignored) {
        }
        super.onDestroy();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (Intent.ACTION_MAIN.equals(intent.getAction())) {
            dismissPopup();
            closeOverlay();
            if (editMode) setEditMode(false);
            if (pager != null) pager.smoothScrollTo(0, 0);
        }
    }

    @Override
    public void onBackPressed() {
        if (popup != null && popup.isShowing()) {
            dismissPopup();
        } else if (overlay != null) {
            closeOverlay();
        } else if (editMode) {
            setEditMode(false);
        } else if (pager != null) {
            pager.smoothScrollTo(0, 0);
        }
    }

    // ------------------------------------------------------------------ data

    void loadApps() {
        PackageManager pm = getPackageManager();
        Intent i = new Intent(Intent.ACTION_MAIN);
        i.addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> ris = pm.queryIntentActivities(i, 0);
        all.clear();
        cache.clear();
        for (ResolveInfo ri : ris) {
            String p = ri.activityInfo.packageName;
            if (p.equals(getPackageName())) continue;
            App a = new App();
            a.label = String.valueOf(ri.loadLabel(pm));
            a.pkg = p;
            a.cn = new ComponentName(p, ri.activityInfo.name);
            a.icon = ri.loadIcon(pm);
            a.cat = ri.activityInfo.applicationInfo.category;
            all.add(a);
        }
        Collections.sort(all, (x, y) -> x.label.compareToIgnoreCase(y.label));
    }

    Set<String> hiddenSet() {
        return new HashSet<>(prefs.getStringSet("hidden", new HashSet<String>()));
    }

    List<App> pickDock() {
        List<App> out = new ArrayList<>();
        PackageManager pm = getPackageManager();
        Intent[] ints = {
                new Intent(Intent.ACTION_DIAL),
                new Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:")),
                new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com")),
                new Intent(MediaStore.ACTION_IMAGE_CAPTURE)
        };
        for (Intent it : ints) {
            ResolveInfo ri = pm.resolveActivity(it, PackageManager.MATCH_DEFAULT_ONLY);
            if (ri != null && ri.activityInfo != null) {
                for (App a : all) {
                    if (a.pkg.equals(ri.activityInfo.packageName) && !out.contains(a)) {
                        out.add(a);
                        break;
                    }
                }
            }
        }
        for (App a : all) {
            if (out.size() >= 4) break;
            if (!out.contains(a)) out.add(a);
        }
        return out;
    }

    String catName(int c) {
        switch (c) {
            case ApplicationInfo.CATEGORY_SOCIAL:
                return "Sosial";
            case ApplicationInfo.CATEGORY_PRODUCTIVITY:
                return "Produktivitas";
            case ApplicationInfo.CATEGORY_AUDIO:
            case ApplicationInfo.CATEGORY_VIDEO:
                return "Hiburan";
            case ApplicationInfo.CATEGORY_IMAGE:
                return "Foto & Video";
            case ApplicationInfo.CATEGORY_GAME:
                return "Game";
            case ApplicationInfo.CATEGORY_NEWS:
                return "Berita";
            case ApplicationInfo.CATEGORY_MAPS:
                return "Peta";
            default:
                return "Lainnya";
        }
    }

    // ------------------------------------------------------------------ helpers

    int dp(int v) {
        return Math.round(v * d);
    }

    GradientDrawable rounded(int color, int r) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(r);
        return g;
    }

    Bitmap iconBitmap(App a, int s) {
        String k = a.pkg + "/" + a.cn.getClassName() + "@" + s;
        Bitmap b = cache.get(k);
        if (b != null) return b;
        b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Drawable dr = a.icon;
        if (dr instanceof AdaptiveIconDrawable) {
            AdaptiveIconDrawable ad = (AdaptiveIconDrawable) dr;
            int off = s / 4;
            Drawable bg = ad.getBackground();
            Drawable fg = ad.getForeground();
            if (bg != null) {
                bg.setBounds(-off, -off, s + off, s + off);
                bg.draw(c);
            }
            if (fg != null) {
                fg.setBounds(-off, -off, s + off, s + off);
                fg.draw(c);
            }
        } else {
            c.drawColor(0xFFEFEFF4);
            int p = s / 8;
            dr.setBounds(p, p, s - p, s - p);
            dr.draw(c);
        }
        cache.put(k, b);
        return b;
    }

    ImageView roundIcon(App a, final int size) {
        ImageView iv = new ImageView(this);
        iv.setImageBitmap(iconBitmap(a, size));
        iv.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), v.getWidth() * 0.225f);
            }
        });
        iv.setClipToOutline(true);
        return iv;
    }

    Cell makeCell(final App a, int iconSize, boolean label, final boolean home, int cellW) {
        final Cell c = new Cell();
        c.app = a;
        FrameLayout fl = new FrameLayout(this);
        c.view = fl;

        LinearLayout ll = new LinearLayout(this);
        ll.setOrientation(LinearLayout.VERTICAL);
        ll.setGravity(Gravity.CENTER_HORIZONTAL);
        ll.setPadding(0, dp(4), 0, 0);
        ll.addView(roundIcon(a, iconSize), new LinearLayout.LayoutParams(iconSize, iconSize));
        if (label) {
            TextView t = new TextView(this);
            t.setText(a.label);
            t.setTextColor(Color.WHITE);
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            t.setSingleLine(true);
            t.setEllipsize(TextUtils.TruncateAt.END);
            t.setGravity(Gravity.CENTER);
            t.setShadowLayer(3f, 0f, 1f, 0x99000000);
            t.setPadding(dp(2), dp(4), dp(2), 0);
            ll.addView(t, new LinearLayout.LayoutParams(MATCH, WRAP));
        }
        fl.addView(ll, new FrameLayout.LayoutParams(MATCH, MATCH));

        if (home) {
            TextView b = new TextView(this);
            b.setText("\u2212");
            b.setTextColor(Color.WHITE);
            b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            b.setGravity(Gravity.CENTER);
            b.setBackground(rounded(0xFF8E8E93, dp(11)));
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(dp(22), dp(22),
                    Gravity.TOP | Gravity.START);
            lp.leftMargin = Math.max(0, (cellW - iconSize) / 2 - dp(6));
            b.setVisibility(View.GONE);
            b.setOnClickListener(v -> hideApp(a.pkg));
            fl.addView(b, lp);
            c.badge = b;
        }

        fl.setOnClickListener(v -> {
            if (!editMode) launch(a, v);
        });
        fl.setOnLongClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            if (!editMode) quickMenu(v, a, home);
            return true;
        });
        return c;
    }

    void launch(App a, View v) {
        try {
            Intent i = new Intent(Intent.ACTION_MAIN);
            i.addCategory(Intent.CATEGORY_LAUNCHER);
            i.setComponent(a.cn);
            i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            ActivityOptions o = ActivityOptions.makeScaleUpAnimation(v, 0, 0, v.getWidth(), v.getHeight());
            startActivity(i, o.toBundle());
        } catch (Exception e) {
            Toast.makeText(this, "Tidak bisa membuka " + a.label, Toast.LENGTH_SHORT).show();
        }
        if (overlay != null) closeOverlay();
    }

    // ------------------------------------------------------------------ UI

    class Pager extends HorizontalScrollView {
        int startX = 0;

        Pager(Context c) {
            super(c);
            setHorizontalScrollBarEnabled(false);
            setOverScrollMode(OVER_SCROLL_NEVER);
            setFillViewport(true);
        }

        @Override
        public boolean dispatchTouchEvent(MotionEvent e) {
            if (e.getActionMasked() == MotionEvent.ACTION_DOWN) startX = getScrollX();
            return super.dispatchTouchEvent(e);
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            boolean r = super.onTouchEvent(e);
            int a = e.getActionMasked();
            if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) snap();
            return r;
        }

        void snap() {
            int sx = getScrollX();
            int startPage = Math.round(startX / (float) W);
            int page = Math.round(sx / (float) W);
            int dx = sx - startX;
            if (Math.abs(dx) > W / 8) page = dx > 0 ? Math.max(page, startPage + 1) : Math.min(page, startPage - 1);
            page = Math.max(0, Math.min(pageCount - 1, page));
            final int tx = page * W;
            post(() -> smoothScrollTo(tx, 0));
        }

        @Override
        protected void onScrollChanged(int l, int t, int ol, int ot) {
            super.onScrollChanged(l, t, ol, ot);
            updateDots(Math.round(l / (float) W));
        }
    }

    void updateDots(int cur) {
        if (dots == null) return;
        for (int i = 0; i < dots.getChildCount(); i++) {
            dots.getChildAt(i).setAlpha(i == cur ? 1f : 0.4f);
        }
    }

    void buildUi() {
        built = true;
        root.removeAllViews();
        overlay = null;
        homeCells.clear();
        editMode = false;

        DisplayMetrics dm = getResources().getDisplayMetrics();
        d = dm.density;
        W = dm.widthPixels;
        H = root.getHeight() > 0 ? root.getHeight() : dm.heightPixels;

        int dockH = dp(88), pillH = dp(34), dotsH = dp(22);
        int bottomH = dockH + pillH + dotsH + dp(14) + botInset;
        int top = topInset + dp(20);
        int avail = H - top - bottomH;
        int rows = Math.max(3, Math.min(6, avail / dp(92)));
        int cellH = Math.min(dp(104), avail / rows);
        int cellW = W / 4;
        int iconSize = Math.min(dp(60), cellW - dp(16));

        List<App> dockApps = pickDock();
        Set<String> hid = hiddenSet();
        List<App> home = new ArrayList<>();
        for (App a : all) if (!dockApps.contains(a) && !hid.contains(a.pkg)) home.add(a);

        int perPage = rows * 4;
        int homePages = Math.max(1, (home.size() + perPage - 1) / perPage);
        pageCount = homePages + 1;

        pager = new Pager(this);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        for (int p = 0; p < homePages; p++) {
            LinearLayout page = new LinearLayout(this);
            page.setOrientation(LinearLayout.VERTICAL);
            for (int r = 0; r < rows; r++) {
                LinearLayout rl = new LinearLayout(this);
                rl.setOrientation(LinearLayout.HORIZONTAL);
                for (int c = 0; c < 4; c++) {
                    int idx = p * perPage + r * 4 + c;
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, MATCH, 1f);
                    if (idx < home.size()) {
                        Cell cell = makeCell(home.get(idx), iconSize, true, true, cellW);
                        homeCells.add(cell);
                        rl.addView(cell.view, lp);
                    } else {
                        rl.addView(new Space(this), lp);
                    }
                }
                page.addView(rl, new LinearLayout.LayoutParams(MATCH, cellH));
            }
            row.addView(page, new LinearLayout.LayoutParams(W, MATCH));
        }
        row.addView(libraryPage(), new LinearLayout.LayoutParams(W, MATCH));
        pager.addView(row, new FrameLayout.LayoutParams(WRAP, MATCH));
        FrameLayout.LayoutParams pl = new FrameLayout.LayoutParams(MATCH, MATCH);
        pl.topMargin = top;
        pl.bottomMargin = bottomH;
        root.addView(pager, pl);

        // bagian bawah: titik halaman, kolom cari, dock
        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setGravity(Gravity.CENTER_HORIZONTAL);
        bottom.setPadding(0, 0, 0, botInset + dp(8));

        dots = new LinearLayout(this);
        dots.setOrientation(LinearLayout.HORIZONTAL);
        dots.setGravity(Gravity.CENTER);
        for (int i = 0; i < pageCount; i++) {
            View dot = new View(this);
            dot.setBackground(rounded(Color.WHITE, dp(4)));
            LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(dp(7), dp(7));
            dl.setMargins(dp(4), 0, dp(4), 0);
            dots.addView(dot, dl);
        }
        updateDots(0);
        bottom.addView(dots, new LinearLayout.LayoutParams(WRAP, dotsH));

        TextView pill = new TextView(this);
        pill.setText("\uD83D\uDD0D  Cari");
        pill.setTextColor(0xCCFFFFFF);
        pill.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        pill.setGravity(Gravity.CENTER);
        pill.setBackground(rounded(0x40FFFFFF, dp(17)));
        pill.setOnClickListener(v -> showOverlay("", true, all));
        bottom.addView(pill, new LinearLayout.LayoutParams(dp(150), pillH));

        LinearLayout dock = new LinearLayout(this);
        dock.setOrientation(LinearLayout.HORIZONTAL);
        dock.setGravity(Gravity.CENTER_VERTICAL);
        dock.setBackground(rounded(0x40FFFFFF, dp(28)));
        for (App a : dockApps) {
            Cell cell = makeCell(a, iconSize, false, false, cellW);
            dock.addView(cell.view, new LinearLayout.LayoutParams(0, MATCH, 1f));
        }
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(MATCH, dockH);
        dlp.setMargins(dp(12), dp(8), dp(12), 0);
        bottom.addView(dock, dlp);

        root.addView(bottom, new FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM));

        doneBtn = new TextView(this);
        doneBtn.setText("Selesai");
        doneBtn.setTextColor(Color.WHITE);
        doneBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        doneBtn.setTypeface(null, android.graphics.Typeface.BOLD);
        doneBtn.setGravity(Gravity.CENTER);
        doneBtn.setBackground(rounded(0x66000000, dp(16)));
        doneBtn.setPadding(dp(14), dp(6), dp(14), dp(6));
        doneBtn.setVisibility(View.GONE);
        doneBtn.setOnClickListener(v -> setEditMode(false));
        FrameLayout.LayoutParams dn = new FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP | Gravity.END);
        dn.topMargin = topInset + dp(8);
        dn.rightMargin = dp(16);
        root.addView(doneBtn, dn);
    }

    void rebuildKeep() {
        int cur = pager != null ? Math.round(pager.getScrollX() / (float) Math.max(1, W)) : 0;
        buildUi();
        final int tx = Math.min(cur, pageCount - 1) * W;
        pager.post(() -> pager.scrollTo(tx, 0));
    }

    View libraryPage() {
        ScrollView sv = new ScrollView(this);
        sv.setVerticalScrollBarEnabled(false);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(16), dp(8), dp(16), dp(16));

        TextView title = new TextView(this);
        title.setText("App Library");
        title.setTextColor(Color.WHITE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setShadowLayer(3f, 0f, 1f, 0x99000000);
        title.setPadding(0, 0, 0, dp(14));
        col.addView(title, new LinearLayout.LayoutParams(WRAP, WRAP));

        LinkedHashMap<String, List<App>> groups = new LinkedHashMap<>();
        for (String n : CAT_ORDER) groups.put(n, new ArrayList<App>());
        for (App a : all) groups.get(catName(a.cat)).add(a);
        List<String> names = new ArrayList<>();
        for (Map.Entry<String, List<App>> e : groups.entrySet()) {
            if (!e.getValue().isEmpty()) names.add(e.getKey());
        }

        int cw = (W - dp(16) * 3) / 2;
        for (int i = 0; i < names.size(); i += 2) {
            LinearLayout r = new LinearLayout(this);
            r.setOrientation(LinearLayout.HORIZONTAL);
            for (int j = 0; j < 2; j++) {
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(cw, WRAP);
                if (j == 0) lp.rightMargin = dp(16);
                if (i + j < names.size()) {
                    String n = names.get(i + j);
                    r.addView(libCard(n, groups.get(n), cw), lp);
                } else {
                    r.addView(new Space(this), lp);
                }
            }
            LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(MATCH, WRAP);
            rp.bottomMargin = dp(14);
            col.addView(r, rp);
        }
        sv.addView(col, new FrameLayout.LayoutParams(MATCH, WRAP));
        return sv;
    }

    View libCard(final String name, final List<App> list, int cw) {
        LinearLayout colu = new LinearLayout(this);
        colu.setOrientation(LinearLayout.VERTICAL);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(rounded(0x40FFFFFF, dp(22)));
        int pad = dp(12);
        card.setPadding(pad, pad, pad, pad);
        int mini = (cw - pad * 2 - dp(10)) / 2;
        for (int r = 0; r < 2; r++) {
            LinearLayout rr = new LinearLayout(this);
            rr.setOrientation(LinearLayout.HORIZONTAL);
            for (int c = 0; c < 2; c++) {
                int k = r * 2 + c;
                View v;
                if (k < list.size()) {
                    final App a = list.get(k);
                    ImageView iv = roundIcon(a, mini);
                    iv.setOnClickListener(x -> launch(a, x));
                    v = iv;
                } else {
                    v = new Space(this);
                }
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(mini, mini);
                if (c == 0) lp.rightMargin = dp(10);
                rr.addView(v, lp);
            }
            LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(WRAP, WRAP);
            if (r == 0) rp.bottomMargin = dp(10);
            card.addView(rr, rp);
        }
        card.setOnClickListener(x -> showOverlay(name, false, list));
        colu.addView(card, new LinearLayout.LayoutParams(MATCH, WRAP));

        TextView t = new TextView(this);
        t.setText(name);
        t.setTextColor(Color.WHITE);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        t.setGravity(Gravity.CENTER);
        t.setShadowLayer(3f, 0f, 1f, 0x99000000);
        t.setPadding(0, dp(5), 0, 0);
        colu.addView(t, new LinearLayout.LayoutParams(MATCH, WRAP));
        return colu;
    }

    // ------------------------------------------------------------------ edit mode

    void setEditMode(boolean on) {
        editMode = on;
        for (Cell c : homeCells) {
            if (c.badge != null) c.badge.setVisibility(on ? View.VISIBLE : View.GONE);
            if (c.anim != null) {
                c.anim.cancel();
                c.anim = null;
            }
            c.view.setRotation(0f);
            if (on) {
                ObjectAnimator a = ObjectAnimator.ofFloat(c.view, "rotation", -1.8f, 1.8f);
                a.setDuration(110 + rnd.nextInt(40));
                a.setRepeatCount(ValueAnimator.INFINITE);
                a.setRepeatMode(ValueAnimator.REVERSE);
                a.start();
                c.anim = a;
            }
        }
        if (doneBtn != null) doneBtn.setVisibility(on ? View.VISIBLE : View.GONE);
    }

    void hideApp(String pkg) {
        Set<String> s = hiddenSet();
        s.add(pkg);
        prefs.edit().putStringSet("hidden", s).apply();
        boolean was = editMode;
        rebuildKeep();
        if (was) setEditMode(true);
    }

    void showApp(String pkg) {
        Set<String> s = hiddenSet();
        s.remove(pkg);
        prefs.edit().putStringSet("hidden", s).apply();
        closeOverlay();
        rebuildKeep();
    }

    // ------------------------------------------------------------------ menu cepat

    void dismissPopup() {
        if (popup != null) {
            popup.dismiss();
            popup = null;
        }
    }

    void menuItem(LinearLayout box, String text, final Runnable r) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(Color.WHITE);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        t.setPadding(dp(16), dp(12), dp(16), dp(12));
        t.setOnClickListener(v -> {
            dismissPopup();
            r.run();
        });
        box.addView(t, new LinearLayout.LayoutParams(MATCH, WRAP));
    }

    void quickMenu(View anchor, final App a, boolean home) {
        dismissPopup();
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(rounded(0xF02C2C2E, dp(14)));
        menuItem(box, "Info Aplikasi", () -> startActivity(new Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + a.pkg))));
        menuItem(box, "Hapus Aplikasi", () -> startActivity(new Intent(
                Intent.ACTION_DELETE, Uri.parse("package:" + a.pkg))));
        if (home) {
            menuItem(box, "Hapus dari Layar Utama", () -> hideApp(a.pkg));
            menuItem(box, "Edit Layar Utama", () -> setEditMode(true));
        } else if (hiddenSet().contains(a.pkg)) {
            menuItem(box, "Tampilkan di Layar Utama", () -> showApp(a.pkg));
        }

        int pw = dp(220);
        box.measure(View.MeasureSpec.makeMeasureSpec(pw, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int ph = box.getMeasuredHeight();

        popup = new PopupWindow(box, pw, WRAP, true);
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        popup.setOutsideTouchable(true);
        popup.setElevation(dp(8));

        int[] loc = new int[2];
        anchor.getLocationOnScreen(loc);
        int x = loc[0] + anchor.getWidth() / 2 - pw / 2;
        x = Math.max(dp(8), Math.min(W - pw - dp(8), x));
        int y;
        if (loc[1] > H / 2) y = loc[1] - ph - dp(6);
        else y = loc[1] + anchor.getHeight() + dp(6);
        popup.showAtLocation(root, Gravity.NO_GRAVITY, x, y);
    }

    // ------------------------------------------------------------------ overlay (cari / kategori)

    void closeOverlay() {
        if (overlay != null) {
            InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) imm.hideSoftInputFromWindow(overlay.getWindowToken(), 0);
            root.removeView(overlay);
            overlay = null;
        }
    }

    void fillGrid(LinearLayout results, List<App> list) {
        results.removeAllViews();
        int cellW = W / 4;
        int iconSize = Math.min(dp(60), cellW - dp(16));
        for (int i = 0; i < list.size(); i += 4) {
            LinearLayout rl = new LinearLayout(this);
            rl.setOrientation(LinearLayout.HORIZONTAL);
            for (int c = 0; c < 4; c++) {
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, WRAP, 1f);
                if (i + c < list.size()) {
                    rl.addView(makeCell(list.get(i + c), iconSize, true, false, cellW).view, lp);
                } else {
                    rl.addView(new Space(this), lp);
                }
            }
            LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(MATCH, WRAP);
            rp.bottomMargin = dp(10);
            results.addView(rl, rp);
        }
    }

    void showOverlay(String title, boolean search, final List<App> src) {
        closeOverlay();
        overlay = new FrameLayout(this);
        overlay.setBackgroundColor(0xEE0E0E12);
        overlay.setClickable(true);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(0, topInset + dp(16), 0, botInset);

        final LinearLayout results = new LinearLayout(this);
        results.setOrientation(LinearLayout.VERTICAL);
        results.setPadding(0, dp(4), 0, dp(16));
        ScrollView sv = new ScrollView(this);
        sv.addView(results, new FrameLayout.LayoutParams(MATCH, WRAP));

        EditText et = null;
        if (search) {
            et = new EditText(this);
            et.setHint("Cari");
            et.setHintTextColor(0x99FFFFFF);
            et.setTextColor(Color.WHITE);
            et.setSingleLine(true);
            et.setBackground(rounded(0x33FFFFFF, dp(12)));
            et.setPadding(dp(14), dp(10), dp(14), dp(10));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(MATCH, WRAP);
            lp.setMargins(dp(16), 0, dp(16), dp(12));
            col.addView(et, lp);
            et.addTextChangedListener(new TextWatcher() {
                @Override
                public void beforeTextChanged(CharSequence s, int a, int b, int c) {
                }

                @Override
                public void onTextChanged(CharSequence s, int a, int b, int c) {
                }

                @Override
                public void afterTextChanged(Editable s) {
                    String q = s.toString().trim().toLowerCase();
                    List<App> out = new ArrayList<>();
                    for (App a : src) if (a.label.toLowerCase().contains(q)) out.add(a);
                    fillGrid(results, out);
                }
            });
        } else {
            TextView t = new TextView(this);
            t.setText(title);
            t.setTextColor(Color.WHITE);
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
            t.setTypeface(null, android.graphics.Typeface.BOLD);
            t.setPadding(dp(20), 0, dp(20), dp(14));
            col.addView(t, new LinearLayout.LayoutParams(WRAP, WRAP));
        }
        fillGrid(results, src);
        col.addView(sv, new LinearLayout.LayoutParams(MATCH, 0, 1f));
        overlay.addView(col, new FrameLayout.LayoutParams(MATCH, MATCH));
        root.addView(overlay, new FrameLayout.LayoutParams(MATCH, MATCH));

        overlay.setAlpha(0f);
        overlay.animate().alpha(1f).setDuration(180).start();

        if (et != null) {
            final EditText fet = et;
            fet.requestFocus();
            fet.postDelayed(() -> {
                InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
                if (imm != null) imm.showSoftInput(fet, InputMethodManager.SHOW_IMPLICIT);
            }, 200);
        }
    }
}

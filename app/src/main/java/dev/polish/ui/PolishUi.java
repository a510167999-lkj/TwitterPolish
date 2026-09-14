package dev.polish.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

/**
 * Polish UI contract v1. Kept inside each independent module, with no host resources
 * or shared project dependency. Status text is supplied by the real hook owner.
 */
public final class PolishUi {
    public static final int BACKGROUND = 0xFFF4F6FA;
    public static final int SURFACE = 0xFFFFFFFF;
    public static final int ACCENT = 0xFF3563E9;
    public static final int TEXT = 0xFF172033;
    public static final int MUTED = 0xFF65718A;
    public static final int BORDER = 0xFFE1E6EF;
    public static final int ACCENT_SOFT = 0xFFEAF0FF;
    public static final int WARNING = 0xFF8C5A12;

    private PolishUi() { }

    public static Context themed(Context context) {
        return new ContextThemeWrapper(context, android.R.style.Theme_Material_Light_NoActionBar);
    }

    public static final class Panel {
        public final Context context;
        public final ScrollView scroll;
        public final LinearLayout content;

        private Panel(Context original, String title, String subtitle) {
            context = themed(original);
            scroll = new ScrollView(context);
            scroll.setFillViewport(true);
            scroll.setBackgroundColor(BACKGROUND);
            scroll.setClipToPadding(false);
            content = new LinearLayout(context);
            content.setOrientation(LinearLayout.VERTICAL);
            int pad = dp(context, 20);
            content.setPadding(pad, pad, pad, pad);
            TextView family = text(context, "POLISH / 设置", 11, MUTED);
            family.setLetterSpacing(0.10f);
            content.addView(family);
            TextView heading = text(context, title, 26, TEXT);
            heading.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            heading.setPadding(0, dp(context, 8), 0, dp(context, 6));
            content.addView(heading);
            if (subtitle != null && !subtitle.isEmpty()) {
                TextView summary = text(context, subtitle, 13, MUTED);
                summary.setPadding(0, 0, 0, dp(context, 20));
                content.addView(summary);
            }
            scroll.addView(content, new ScrollView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        public LinearLayout section(String title, String description) {
            LinearLayout card = new LinearLayout(context);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(dp(context, 16), dp(context, 16), dp(context, 16), dp(context, 12));
            card.setBackground(shape(context, SURFACE, 18, BORDER));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = dp(context, 12);
            content.addView(card, lp);
            TextView heading = text(context, title, 17, TEXT);
            heading.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            card.addView(heading);
            if (description != null && !description.isEmpty()) {
                TextView hint = text(context, description, 12, MUTED);
                hint.setPadding(0, dp(context, 5), 0, dp(context, 8));
                card.addView(hint);
            }
            return card;
        }

        public void note(String message) {
            TextView note = text(context, message, 12, MUTED);
            note.setPadding(dp(context, 4), dp(context, 4), dp(context, 4), dp(context, 12));
            content.addView(note);
        }
    }

    public static Panel screen(Context context, String title, String subtitle) {
        return new Panel(context, title, subtitle);
    }

    public static TextView text(Context context, String value, float size, int color) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setLineSpacing(dp(context, 3), 1f);
        return view;
    }

    public static void info(LinearLayout card, String title, String description) {
        Context context = card.getContext();
        TextView label = text(context, title, 14, TEXT);
        label.setPadding(0, dp(context, 10), 0, dp(context, 3));
        card.addView(label);
        if (description != null && !description.isEmpty()) {
            TextView detail = text(context, description, 12, MUTED);
            detail.setTextIsSelectable(true);
            detail.setPadding(0, 0, 0, dp(context, 8));
            card.addView(detail);
        }
    }

    public static Switch toggle(LinearLayout card, String title, String description, boolean checked) {
        Context context = card.getContext();
        Switch control = new Switch(context);
        String value = title + (description == null || description.isEmpty() ? "" : "\n" + description);
        SpannableString label = new SpannableString(value);
        if (value.length() > title.length()) {
            label.setSpan(new RelativeSizeSpan(0.84f), title.length(), value.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            label.setSpan(new ForegroundColorSpan(MUTED), title.length(), value.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        control.setText(label);
        control.setTextColor(TEXT);
        control.setTextSize(14);
        control.setGravity(Gravity.CENTER_VERTICAL);
        control.setSwitchPadding(dp(context, 12));
        control.setPadding(0, dp(context, 12), 0, dp(context, 12));
        control.setMinHeight(dp(context, 64));
        control.setShowText(false);
        int[][] states = new int[][] { new int[] { android.R.attr.state_checked }, new int[0] };
        control.setThumbTintList(new ColorStateList(states, new int[] { ACCENT, MUTED }));
        control.setTrackTintList(new ColorStateList(states, new int[] { 0xFFB7C9FF, BORDER }));
        control.setChecked(checked);
        control.setContentDescription(value);
        card.addView(control, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return control;
    }

    public static Button action(LinearLayout parent, String label, boolean primary, View.OnClickListener listener) {
        Context context = parent.getContext();
        Button button = new Button(context);
        button.setText(label);
        button.setTextSize(14);
        button.setAllCaps(false);
        button.setTextColor(primary ? SURFACE : ACCENT);
        button.setBackgroundTintList(null);
        button.setBackground(shape(context, primary ? ACCENT : ACCENT_SOFT, 12, 0));
        button.setMinHeight(dp(context, 48));
        button.setPadding(dp(context, 12), dp(context, 10), dp(context, 12), dp(context, 10));
        button.setOnClickListener(listener);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(context, 8);
        lp.bottomMargin = dp(context, 4);
        parent.addView(button, lp);
        return button;
    }

    public static AlertDialog.Builder dialog(Context context, Panel panel) {
        return new AlertDialog.Builder(themed(context)).setView(panel.scroll);
    }

    public static void styleDialog(AlertDialog dialog) {
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(shape(dialog.getContext(), BACKGROUND, 22, 0));
            int width = Math.min(dp(dialog.getContext(), 560),
                    dialog.getContext().getResources().getDisplayMetrics().widthPixels - dp(dialog.getContext(), 24));
            window.setLayout(width, WindowManager.LayoutParams.WRAP_CONTENT);
        }
        for (int id : new int[] { AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL }) {
            Button button = dialog.getButton(id);
            if (button != null) {
                button.setTextColor(ACCENT);
                button.setAllCaps(false);
                button.setMinHeight(dp(dialog.getContext(), 48));
            }
        }
    }

    public static void install(Activity activity, Panel panel) {
        activity.setContentView(panel.scroll);
        Window window = activity.getWindow();
        window.setStatusBarColor(BACKGROUND);
        window.setNavigationBarColor(BACKGROUND);
        window.getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        // Target 35+ enables edge-to-edge: apply the real system insets once, not guessed pixels.
        panel.scroll.setOnApplyWindowInsetsListener((view, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            } else {
                view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                        insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            }
            return insets;
        });
        panel.scroll.requestApplyInsets();
    }

    public static String hostVersion(Context context, String packageName) {
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(packageName, 0);
            long code = Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode;
            return packageName + " · " + (info.versionName == null ? "未知版本" : info.versionName) + " (" + code + ")";
        } catch (Throwable ignored) {
            return packageName + " · 未读取到宿主版本";
        }
    }

    public static GradientDrawable shape(Context context, int fill, float radius, int stroke) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(fill);
        shape.setCornerRadius(dp(context, radius));
        if (stroke != 0) shape.setStroke(dp(context, 1), stroke);
        return shape;
    }

    public static int dp(Context context, float value) {
        return Math.round(context.getResources().getDisplayMetrics().density * value);
    }
}

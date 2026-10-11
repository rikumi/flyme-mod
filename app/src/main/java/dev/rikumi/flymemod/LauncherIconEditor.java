package dev.rikumi.flymemod;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

/** ColorOS edit-preview -> icon-pack pages -> select -> save flow, with a full-screen grid. */
final class LauncherIconEditor {
    record Edit(String title, String pack, String drawable, boolean mask) {
        Edit(String title, String pack, String drawable) { this(title, pack, drawable, false); }
    }
    interface Save { void save(Edit edit, Runnable finished); }
    private final Context ctx;
    private final ColorOsIconPackCatalog catalog;
    private final BiConsumer<String, Throwable> log;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newFixedThreadPool(2);
    private final ExecutorService images = new ThreadPoolExecutor(2, 2, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(32), new ThreadPoolExecutor.DiscardOldestPolicy());
    private final int foreground, muted;
    private Dialog activePicker;

    LauncherIconEditor(Context ctx, ColorOsIconPackCatalog catalog, BiConsumer<String, Throwable> log) {
        this.ctx = ctx; this.catalog = catalog; this.log = log;
        boolean dark = (ctx.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        foreground = dark ? Color.WHITE : Color.BLACK;
        muted = dark ? 0x99FFFFFF : 0x99000000;
    }

    void show(String originalName, String applicationPackage, String applicationComponent, Bitmap originalIcon, Edit previous, Save save) {
        LinearLayout root = column();
        root.setPadding(dp(24), dp(12), dp(24), dp(8));
        ImageView preview = new ImageView(ctx);
        preview.setScaleType(ImageView.ScaleType.FIT_CENTER);
        preview.setImageBitmap(originalIcon);
        LinearLayout.LayoutParams previewParams = new LinearLayout.LayoutParams(dp(72), dp(72));
        previewParams.gravity = Gravity.CENTER_HORIZONTAL;
        FrameLayout iconArea = new FrameLayout(ctx);
        iconArea.setContentDescription("编辑图标");
        iconArea.addView(preview, new FrameLayout.LayoutParams(-1, -1));
        ImageView pencil = new ImageView(ctx);
        android.graphics.drawable.Drawable pencilIcon = flymeDrawable(ctx, "mz_titlebar_ic_edit").mutate();
        pencilIcon.setTint(Color.WHITE);
        pencil.setImageDrawable(pencilIcon);
        pencil.setScaleType(ImageView.ScaleType.FIT_CENTER);
        pencil.setPadding(dp(5), dp(5), dp(5), dp(5));
        android.graphics.drawable.GradientDrawable pencilBackground = new android.graphics.drawable.GradientDrawable();
        pencilBackground.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        pencilBackground.setColor(0xFF808080);
        pencil.setBackground(pencilBackground);
        iconArea.addView(pencil, new FrameLayout.LayoutParams(dp(24), dp(24), Gravity.BOTTOM | Gravity.END));
        root.addView(iconArea, previewParams);
        EditText name = new EditText(ctx);
        name.setSingleLine(true);
        name.setTextColor(foreground);
        name.setText(previous == null || previous.title == null ? originalName : previous.title);
        name.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams nameParams = new LinearLayout.LayoutParams(-1, dp(56));
        nameParams.topMargin = dp(20);
        root.addView(name, nameParams);
        Edit[] selected = {previous == null ? new Edit(null, null, null) : previous};
        AlertDialog dialog = new AlertDialog.Builder(ctx).setTitle("编辑图标和名称").setView(root)
                .setNegativeButton("取消", null).setNeutralButton("恢复默认", null)
                .setPositiveButton("保存", null).create();
        dialog.setOnDismissListener(ignored -> {
            if (activePicker != null) activePicker.dismiss();
            worker.shutdownNow(); images.shutdownNow();
        });
        iconArea.setOnClickListener(view -> {
            iconArea.setEnabled(false);
            worker.execute(() -> {
                try {
                    List<ColorOsIconPackCatalog.Pack> available = catalog.packs(ctx);
                    main.post(() -> {
                        if (!dialog.isShowing()) return;
                        iconArea.setEnabled(true);
                        if (available.isEmpty()) {
                            Toast.makeText(ctx, "未找到图标包", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        try {
                            picker(available, applicationPackage, applicationComponent, (pack, entry, mask) -> {
                                selected[0] = new Edit(null, pack.pkg(), entry.drawable(), mask);
                                loadPreview(preview, pack.pkg(), entry.drawable(), mask, dialog);
                            });
                        } catch (Exception error) { error("打开图标选择器失败", error); }
                    });
                } catch (Exception error) {
                    main.post(() -> { if (dialog.isShowing()) iconArea.setEnabled(true); });
                    error("读取图标包失败", error);
                }
            });
        });
        dialog.setOnShowListener(ignored -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
                String title = name.getText().toString().trim();
                if (title.isEmpty()) { name.setError("名称不能为空"); return; }
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
                Edit value = selected[0];
                save.save(new Edit(title.equals(originalName) ? null : title, value.pack, value.drawable, value.mask), dialog::dismiss);
            });
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(view -> save.save(null, dialog::dismiss));
        });
        dialog.show();
        if (previous != null && previous.pack != null) loadPreview(preview, previous.pack, previous.drawable, previous.mask, dialog);
    }

    private void loadPreview(ImageView preview, String pack, String drawable, boolean mask, Dialog dialog) {
        String tag = pack + ":" + drawable + ":" + mask;
        preview.setTag(tag);
        worker.execute(() -> {
            try {
                Bitmap bitmap = catalog.bitmap(ctx, pack, drawable, dp(72), mask);
                main.post(() -> { if (dialog.isShowing() && tag.equals(preview.getTag())) preview.setImageBitmap(bitmap); });
            } catch (Exception error) { error("加载图标失败", error); }
        });
    }

    private interface Selection { void select(ColorOsIconPackCatalog.Pack pack, ColorOsIconPackCatalog.Entry entry, boolean mask); }
    private void picker(List<ColorOsIconPackCatalog.Pack> available, String applicationPackage, String applicationComponent, Selection selection) throws ReflectiveOperationException {
        Dialog dialog = new Dialog(ctx);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout root = column();
        int surface = ctx.getResources().getIdentifier("fd_sys_color_surface_default", "color", ctx.getPackageName());
        int background = surface == 0 ? (foreground == Color.WHITE ? 0xFF101010 : 0xFFF2F2F2)
                : ctx.getColor(surface);
        root.setBackgroundColor(background);
        Class<?> toolbarClass = ctx.getClassLoader().loadClass("flyme.support.v7.widget.Toolbar");
        View toolbar = (View) toolbarClass.getConstructor(Context.class).newInstance(ctx);
        toolbarClass.getMethod("setTitle", CharSequence.class).invoke(toolbar, "选择图标");
        toolbarClass.getMethod("setNavigationIcon", android.graphics.drawable.Drawable.class)
                .invoke(toolbar, flymeDrawable(ctx, "mz_titlebar_ic_back_state_drawable"));
        toolbarClass.getMethod("setNavigationOnClickListener", View.OnClickListener.class)
                .invoke(toolbar, (View.OnClickListener) view -> dialog.dismiss());
        toolbar.setBackgroundColor(background);
        root.addView(toolbar, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout body = column();
        body.setPadding(dp(12), 0, dp(12), dp(8));
        root.addView(body, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout packRow = new LinearLayout(ctx);
        packRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(-1, dp(56));
        rowParams.leftMargin = dp(16); rowParams.rightMargin = dp(16);
        body.addView(packRow, rowParams);
        Spinner packs = new Spinner(ctx);
        packs.setPaddingRelative(0, packs.getPaddingTop(), packs.getPaddingEnd(), packs.getPaddingBottom());
        packRow.addView(packs, new LinearLayout.LayoutParams(0, -1, 1));
        CheckBox applyMask = new CheckBox(ctx) {
            @Override public int getCompoundPaddingLeft() {
                return super.getCompoundPaddingLeft() + (getLayoutDirection() == View.LAYOUT_DIRECTION_RTL ? 0 : dp(8));
            }
            @Override public int getCompoundPaddingRight() {
                return super.getCompoundPaddingRight() + (getLayoutDirection() == View.LAYOUT_DIRECTION_RTL ? dp(8) : 0);
            }
        };
        applyMask.setText("套用当前图标包遮罩");
        applyMask.setTextColor(foreground);
        applyMask.setTextSize(14);
        applyMask.setSingleLine(true);
        applyMask.setChecked(true);
        LinearLayout.LayoutParams maskParams = new LinearLayout.LayoutParams(-2, -2);
        maskParams.leftMargin = dp(8);
        packRow.addView(applyMask, maskParams);
        EditText search = new EditText(ctx);
        search.setSingleLine(true);
        search.setTextColor(foreground); search.setHintTextColor(muted);
        search.setHint("搜索图标");
        LinearLayout.LayoutParams searchParams = new LinearLayout.LayoutParams(-1, dp(52));
        searchParams.leftMargin = dp(16); searchParams.rightMargin = dp(16);
        body.addView(search, searchParams);
        TextView status = text("加载中…", 14, muted);
        status.setGravity(Gravity.CENTER);
        body.addView(status, new LinearLayout.LayoutParams(-1, dp(36)));
        GridView matching = new GridView(ctx);
        matching.setNumColumns(Math.max(3, ctx.getResources().getConfiguration().screenWidthDp / 88));
        matching.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        matching.setHorizontalSpacing(dp(8));
        IconAdapter matchingAdapter = new IconAdapter(dialog, true);
        matching.setAdapter(matchingAdapter);
        matching.setVisibility(View.GONE);
        body.addView(matching, new LinearLayout.LayoutParams(-1, dp(110)));
        GridView grid = new GridView(ctx);
        grid.setNumColumns(Math.max(3, ctx.getResources().getConfiguration().screenWidthDp / 88));
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        grid.setVerticalSpacing(dp(12)); grid.setHorizontalSpacing(dp(8));
        IconAdapter adapter = new IconAdapter(dialog, true);
        grid.setAdapter(adapter);
        body.addView(grid, new LinearLayout.LayoutParams(-1, 0, 1));
        dialog.setContentView(root);
        dialog.show();
        activePicker = dialog;
        dialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(background));
        dialog.getWindow().getDecorView().setPadding(0, 0, 0, 0);
        dialog.getWindow().setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        // Window content respects system-bar/IME insets; only the former half
        // panel's height constraint is removed, never drawing under the keyboard.
        dialog.getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        applyMask.setOnCheckedChangeListener((button, checked) -> {
            matchingAdapter.mask = checked; adapter.mask = checked;
            matchingAdapter.notifyDataSetChanged(); adapter.notifyDataSetChanged();
        });
        int[] generation = {0};
        List<ColorOsIconPackCatalog.Entry>[] all = new List[]{List.of()};
        java.util.Set<String>[] preferred = new java.util.Set[]{java.util.Set.of()};
        Runnable filter = () -> {
            String query = search.getText().toString().trim().toLowerCase(Locale.ROOT);
            List<ColorOsIconPackCatalog.Entry> visible = all[0].stream().filter(entry -> entry.matches(query))
                    .collect(java.util.stream.Collectors.toList());
            matchingAdapter.pack = adapter.pack;
            matchingAdapter.entries = visible.stream().filter(entry -> preferred[0].contains(entry.drawable()))
                    .collect(java.util.stream.Collectors.toList());
            adapter.entries = visible.stream().filter(entry -> !preferred[0].contains(entry.drawable()))
                    .collect(java.util.stream.Collectors.toList());
            matchingAdapter.notifyDataSetChanged();
            matching.setVisibility(matchingAdapter.entries.isEmpty() ? View.GONE : View.VISIBLE);
            matching.getLayoutParams().height = dp(110) * Math.max(1,
                    (matchingAdapter.entries.size() + matching.getNumColumns() - 1) / matching.getNumColumns());
            matching.requestLayout();
            adapter.notifyDataSetChanged();
            status.setText(visible.isEmpty() ? "未找到图标" : visible.size() + " 个图标");
        };
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                main.removeCallbacks(filter); main.postDelayed(filter, 120);
            }
            @Override public void afterTextChanged(Editable value) {}
        });
        dialog.setOnDismissListener(ignored -> {
            generation[0]++; main.removeCallbacks(filter); activePicker = null;
        });
        matching.setOnItemClickListener((parent, view, position, id) -> {
            if (matchingAdapter.pack != null && position < matchingAdapter.entries.size()) {
                selection.select(matchingAdapter.pack, matchingAdapter.entries.get(position), applyMask.isChecked()); dialog.dismiss();
            }
        });
        grid.setOnItemClickListener((parent, view, position, id) -> {
            if (adapter.pack != null && position < adapter.entries.size()) {
                selection.select(adapter.pack, adapter.entries.get(position), applyMask.isChecked()); dialog.dismiss();
            }
        });
        ArrayAdapter<ColorOsIconPackCatalog.Pack> packAdapter = new ArrayAdapter<>(ctx,
                android.R.layout.simple_spinner_item, available) {
            @Override public View getView(int position, View recycled, ViewGroup parent) {
                View view = super.getView(position, recycled, parent);
                view.setPaddingRelative(0, view.getPaddingTop(), view.getPaddingEnd(), view.getPaddingBottom());
                if (view instanceof TextView label) {
                    label.setSingleLine(true);
                    label.setEllipsize(android.text.TextUtils.TruncateAt.END);
                }
                return view;
            }
            @Override public View getDropDownView(int position, View recycled, ViewGroup parent) {
                View view = super.getDropDownView(position, recycled, parent);
                view.setPaddingRelative(0, view.getPaddingTop(), view.getPaddingEnd(), view.getPaddingBottom());
                return view;
            }
        };
        packAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        packs.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onNothingSelected(AdapterView<?> parent) {}
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                int load = ++generation[0];
                ColorOsIconPackCatalog.Pack pack = available.get(position);
                adapter.pack = pack; adapter.entries = List.of(); all[0] = List.of(); preferred[0] = java.util.Set.of();
                adapter.notifyDataSetChanged(); status.setText("加载中…");
                matchingAdapter.entries = List.of(); matchingAdapter.notifyDataSetChanged();
                matching.setVisibility(View.GONE);
                worker.execute(() -> {
                    try {
                        List<ColorOsIconPackCatalog.Entry> entries = catalog.entries(ctx, pack.pkg());
                        if (pack.pkg().startsWith(FlymeThemeIconCatalog.PREFIX)) {
                            entries = FlymeThemeIconCatalog.withApplication(entries, applicationPackage);
                        }
                        List<ColorOsIconPackCatalog.Entry> loaded = entries;
                        java.util.Set<String> featured = pack.pkg().startsWith(FlymeThemeIconCatalog.PREFIX)
                                ? loaded.stream().filter(entry -> FlymeThemeIconCatalog.featured(entry, applicationPackage))
                                        .map(ColorOsIconPackCatalog.Entry::drawable).collect(java.util.stream.Collectors.toSet())
                                : ColorOsIconPackCatalog.matching(loaded, applicationComponent);
                        main.post(() -> {
                            if (!dialog.isShowing() || load != generation[0]) return;
                            all[0] = loaded; preferred[0] = featured; filter.run(); grid.setSelection(0);
                        });
                    } catch (Exception error) {
                        main.post(() -> { if (dialog.isShowing() && load == generation[0]) status.setText("读取图标包失败"); });
                        error("读取图标包失败", error);
                    }
                });
            }
        });
        packs.setAdapter(packAdapter);
    }

    private final class IconAdapter extends BaseAdapter {
        final Dialog dialog;
        List<ColorOsIconPackCatalog.Entry> entries = List.of();
        ColorOsIconPackCatalog.Pack pack;
        boolean mask;
        IconAdapter(Dialog dialog, boolean mask) { this.dialog = dialog; this.mask = mask; }
        @Override public int getCount() { return entries.size(); }
        @Override public Object getItem(int position) { return entries.get(position); }
        @Override public long getItemId(int position) { return position; }
        @Override public View getView(int position, View reused, ViewGroup parent) {
            LinearLayout cell;
            if (reused instanceof LinearLayout layout) cell = layout;
            else {
                cell = column(); cell.setGravity(Gravity.CENTER_HORIZONTAL);
                ImageView image = new ImageView(ctx); image.setScaleType(ImageView.ScaleType.FIT_CENTER);
                cell.addView(image, new LinearLayout.LayoutParams(dp(56), dp(56)));
                TextView label = text("", 11, muted); label.setGravity(Gravity.TOP | Gravity.CENTER_HORIZONTAL); label.setMaxLines(2);
                label.setEllipsize(android.text.TextUtils.TruncateAt.END);
                LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(-1, dp(36));
                labelParams.topMargin = dp(6); cell.addView(label, labelParams);
            }
            ColorOsIconPackCatalog.Entry entry = entries.get(position);
            boolean useMask = mask;
            String pkg = pack.pkg(), tag = pkg + ":" + entry.drawable() + ":" + useMask;
            cell.setTag(tag);
            ImageView image = (ImageView) cell.getChildAt(0);
            image.setImageDrawable(null);
            ((TextView) cell.getChildAt(1)).setText(entry.name());
            LinearLayout target = cell;
            if (!images.isShutdown()) images.execute(() -> {
                try {
                    Bitmap bitmap = catalog.bitmap(ctx, pkg, entry.drawable(), dp(56), useMask);
                    main.post(() -> {
                        if (dialog.isShowing() && tag.equals(target.getTag())) image.setImageBitmap(bitmap);
                    });
                } catch (Exception error) { log.accept("Cannot load icon " + tag, error); }
            });
            return cell;
        }
    }

    private LinearLayout column() { LinearLayout view = new LinearLayout(ctx); view.setOrientation(LinearLayout.VERTICAL); return view; }
    static android.graphics.drawable.Drawable flymeDrawable(Context context, String name) {
        int id = context.getResources().getIdentifier(name, "drawable", context.getPackageName());
        if (id == 0) throw new IllegalStateException("Missing Flyme drawable " + name);
        return context.getDrawable(id);
    }
    private TextView text(String value, float size, int color) {
        TextView view = new TextView(ctx); view.setText(value); view.setTextSize(size); view.setTextColor(color); return view;
    }
    private int dp(float value) { return Math.round(value * ctx.getResources().getDisplayMetrics().density); }
    private void error(String operation, Exception error) {
        log.accept(operation, error);
        main.post(() -> Toast.makeText(ctx, operation + "：" + error.getMessage(), Toast.LENGTH_LONG).show());
    }
}

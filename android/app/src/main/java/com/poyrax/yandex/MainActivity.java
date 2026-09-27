package com.poyrax.yandex;

import android.Manifest;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.documentfile.provider.DocumentFile;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    static final int BACKGROUND = Color.rgb(11, 16, 25);
    static final int SURFACE = Color.rgb(20, 28, 41);
    static final int TEXT = Color.rgb(237, 242, 250);
    static final int MUTED = Color.rgb(147, 161, 183);
    static final int ACCENT = Color.rgb(91, 140, 255);
    private final ExecutorService listingExecutor = Executors.newSingleThreadExecutor();
    private DiskClient.Cancellation listingCancellation = new DiskClient.Cancellation();
    private final List<MediaFile> files = new ArrayList<>();
    private EditText linkInput;
    private Button selectedButton;
    private Button allButton;
    private Button folderButton;
    private Button cancelButton;
    private TextView folderText;
    private TextView status;
    private ProgressBar totalProgress;
    private RecyclerView gallery;
    private MediaAdapter adapter;
    private Uri target;
    private String loadedLink;
    private boolean listing;
    private boolean destroyed;
    private boolean bound;
    private int generation;
    private List<MediaFile> pendingDownload;
    DiskClient client = new DiskClient();
    DownloadService downloadService;

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            downloadService = ((DownloadService.LocalBinder) binder).getService();
            downloadService.setListener(MainActivity.this::refreshTransfer);
            if (downloadService.running && files.isEmpty() && !listing) {
                files.addAll(downloadService.gallery);
                loadedLink = downloadService.link;
                target = downloadService.target;
                linkInput.setText(loadedLink);
                showTarget();
                adapter.setFiles(files);
                refreshTransfer(null);
            }
            updateButtons();
        }
        @Override
        public void onServiceDisconnected(ComponentName name) { downloadService = null; updateButtons(); }
    };

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        buildUi();
        String saved = getPreferences(MODE_PRIVATE).getString("folder", null);
        if (saved != null) {
            target = Uri.parse(saved);
            showTarget();
        }
        bound = bindService(new Intent(this, DownloadService.class), connection, BIND_AUTO_CREATE);
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BACKGROUND);
        root.setPadding(dp(20), dp(20), dp(20), dp(16));
        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
            root.setOnApplyWindowInsetsListener((view, insets) -> {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
                android.graphics.Insets keyboard = insets.getInsets(WindowInsets.Type.ime());
                view.setPadding(dp(20) + bars.left, dp(16) + bars.top, dp(20) + bars.right, dp(12) + Math.max(bars.bottom, keyboard.bottom));
                return insets;
            });
        }
        LinearLayout heading = row();
        TextView title = text("Yandexdisk Downloader", 24, TEXT);
        title.setSingleLine(true);
        title.setAutoSizeTextTypeUniformWithConfiguration(14, 24, 1, android.util.TypedValue.COMPLEX_UNIT_SP);
        title.setTypeface(null, Typeface.BOLD);
        heading.addView(title, new LinearLayout.LayoutParams(0, dp(44), 1));
        TextView signature = text("Poyrax", 13, MUTED);
        signature.setTypeface(null, Typeface.BOLD);
        heading.addView(signature);
        root.addView(heading);

        LinearLayout linkRow = row();
        linkInput = new EditText(this);
        linkInput.setSingleLine(true);
        linkInput.setTextSize(14);
        linkInput.setTextColor(TEXT);
        linkInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        linkInput.setContentDescription("Bağlantı");
        linkInput.setPadding(dp(12), 0, dp(12), 0);
        linkInput.setBackground(surface(SURFACE, Color.rgb(42, 53, 73), 10));
        linkRow.addView(linkInput, new LinearLayout.LayoutParams(0, dp(48), 1));
        Button listButton = button("Listele", true);
        LinearLayout.LayoutParams listParams = new LinearLayout.LayoutParams(dp(82), dp(48));
        listParams.leftMargin = dp(8);
        linkRow.addView(listButton, listParams);
        add(root, linkRow, 12);
        listButton.setOnClickListener(view -> listFiles());
        linkInput.setOnEditorActionListener((view, action, event) -> { listFiles(); return true; });

        LinearLayout folderRow = row();
        folderButton = button("Klasör seç", false);
        folderRow.addView(folderButton, new LinearLayout.LayoutParams(dp(108), dp(44)));
        folderText = text("", 12, MUTED);
        folderText.setMaxLines(1);
        folderText.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        LinearLayout.LayoutParams pathParams = new LinearLayout.LayoutParams(0, dp(44), 1);
        pathParams.leftMargin = dp(12);
        folderRow.addView(folderText, pathParams);
        add(root, folderRow, 12);
        folderButton.setOnClickListener(view -> chooseFolder());

        LinearLayout downloadRow = row();
        selectedButton = button("Seçilenleri indir", false);
        allButton = button("Tümünü indir", true);
        downloadRow.addView(selectedButton, new LinearLayout.LayoutParams(0, dp(46), 1));
        LinearLayout.LayoutParams allParams = new LinearLayout.LayoutParams(0, dp(46), 1);
        allParams.leftMargin = dp(10);
        downloadRow.addView(allButton, allParams);
        add(root, downloadRow, 12);
        selectedButton.setOnClickListener(view -> {
            List<MediaFile> selected = new ArrayList<>();
            for (MediaFile file : files) if (file.selected) selected.add(file);
            download(selected);
        });
        allButton.setOnClickListener(view -> download(new ArrayList<>(files)));

        gallery = new RecyclerView(this);
        gallery.setLayoutManager(new GridLayoutManager(this, columns()));
        gallery.setClipToPadding(false);
        gallery.setPadding(0, dp(16), 0, dp(6));
        gallery.setItemAnimator(null);
        adapter = new MediaAdapter(this, () -> client, this::updateButtons);
        gallery.setAdapter(adapter);
        root.addView(gallery, new LinearLayout.LayoutParams(-1, 0, 1));

        LinearLayout footer = row();
        status = text("", 12, MUTED);
        status.setGravity(Gravity.CENTER_VERTICAL);
        footer.addView(status, new LinearLayout.LayoutParams(0, -2, 1));
        cancelButton = button("İptal", false);
        footer.addView(cancelButton, new LinearLayout.LayoutParams(dp(78), dp(42)));
        cancelButton.setOnClickListener(view -> {
            listingCancellation.cancel();
            if (downloadService != null) downloadService.cancel();
            cancelButton.setEnabled(false);
        });
        root.addView(footer);
        totalProgress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        totalProgress.setProgressTintList(android.content.res.ColorStateList.valueOf(ACCENT));
        totalProgress.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.rgb(38, 50, 71)));
        root.addView(totalProgress, new LinearLayout.LayoutParams(-1, dp(4)));
        totalProgress.setVisibility(View.GONE);
        setContentView(root);
        updateButtons();
    }

    private void listFiles() {
        if (destroyed) return;
        listingCancellation.cancel();
        listingCancellation = new DiskClient.Cancellation();
        DiskClient.Cancellation cancel = listingCancellation;
        int current = ++generation;
        if (downloadService != null && downloadService.running) downloadService.cancel();
        files.clear();
        loadedLink = null;
        adapter.setFiles(files);
        totalProgress.setVisibility(View.GONE);
        String link;
        try { link = DiskClient.validateLink(linkInput.getText().toString()); }
        catch (IOException e) { listing = false; status.setText(e.getMessage()); updateButtons(); return; }
        linkInput.setText(link);
        listing = true;
        status.setText("Listeleniyor…");
        totalProgress.setIndeterminate(true);
        totalProgress.setVisibility(View.VISIBLE);
        updateButtons();
        getSystemService(InputMethodManager.class).hideSoftInputFromWindow(linkInput.getWindowToken(), 0);
        listingExecutor.execute(() -> {
            try {
                while (downloadService != null && downloadService.running) cancel.pause(30);
                List<MediaFile> result = client.list(link, cancel, count -> runOnUiThread(() -> {
                    if (current == generation && !destroyed && listing && !cancel.isCancelled()) status.setText("Listeleniyor… " + count + " dosya");
                }));
                cancel.check();
                runOnUiThread(() -> {
                    if (current != generation || destroyed || cancel.isCancelled()) return;
                    files.addAll(result);
                    loadedLink = link;
                    adapter.setFiles(files);
                    finishListing(result.isEmpty() ? "Video veya görsel bulunamadı." : result.size() + " dosya");
                });
            } catch (IOException e) {
                runOnUiThread(() -> {
                    if (current == generation && !destroyed) finishListing(cancel.isCancelled() ? "İptal edildi." : DiskClient.message(e));
                });
            }
        });
    }

    private void finishListing(String message) {
        listing = false;
        status.setText(message);
        totalProgress.setIndeterminate(false);
        totalProgress.setVisibility(View.GONE);
        updateButtons();
    }

    private void chooseFolder() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        if (target != null) intent.putExtra(android.provider.DocumentsContract.EXTRA_INITIAL_URI, target);
        startActivityForResult(intent, 1);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != 1) return;
        if (resultCode != RESULT_OK || data == null || data.getData() == null) { pendingDownload = null; return; }
        try {
            target = data.getData();
            int permissions = 0;
            if ((data.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0) permissions |= Intent.FLAG_GRANT_READ_URI_PERMISSION;
            if ((data.getFlags() & Intent.FLAG_GRANT_WRITE_URI_PERMISSION) != 0) permissions |= Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
            getContentResolver().takePersistableUriPermission(target, permissions);
            getPreferences(MODE_PRIVATE).edit().putString("folder", target.toString()).apply();
            showTarget();
            if (pendingDownload != null) {
                List<MediaFile> pending = pendingDownload;
                pendingDownload = null;
                download(pending);
            }
        } catch (SecurityException e) {
            target = null;
            pendingDownload = null;
            status.setText("Klasöre erişilemiyor. Klasörü yeniden seçin.");
        }
    }

    private void showTarget() {
        try {
            DocumentFile folder = target == null ? null : DocumentFile.fromTreeUri(this, target);
            folderText.setText(folder == null ? "" : folder.getName());
        } catch (Exception e) { target = null; folderText.setText(""); }
    }

    private void download(List<MediaFile> selected) {
        if (listing || selected.isEmpty() || loadedLink == null || downloadService == null || downloadService.running) return;
        if (target == null) { pendingDownload = selected; chooseFolder(); return; }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 2);
        }
        downloadService.start(loadedLink, target, selected, files);
        refreshTransfer(null);
    }

    private void refreshTransfer(MediaFile file) {
        if (destroyed || listing || downloadService == null || loadedLink == null || !loadedLink.equals(downloadService.link)) return;
        status.setText(downloadService.summary);
        totalProgress.setIndeterminate(false);
        totalProgress.setVisibility(View.VISIBLE);
        totalProgress.setProgress(downloadService.percent);
        adapter.updateTransfer(file);
        updateButtons();
    }

    private void updateButtons() {
        boolean downloading = downloadService != null && downloadService.running;
        boolean ready = !listing && !downloading && loadedLink != null && !files.isEmpty() && downloadService != null;
        boolean any = false;
        for (MediaFile file : files) if (file.selected) { any = true; break; }
        selectedButton.setEnabled(ready && any);
        allButton.setEnabled(ready);
        selectedButton.setAlpha(ready && any ? 1 : 0.38f);
        allButton.setAlpha(ready ? 1 : 0.38f);
        folderButton.setEnabled(!listing && !downloading);
        folderButton.setAlpha(folderButton.isEnabled() ? 1 : 0.38f);
        cancelButton.setVisibility(listing || downloading ? View.VISIBLE : View.GONE);
        cancelButton.setEnabled(true);
    }

    private int columns() { return Math.max(1, (getResources().getConfiguration().screenWidthDp - 40) / 160); }

    @Override
    public void onConfigurationChanged(Configuration config) {
        super.onConfigurationChanged(config);
        ((GridLayoutManager) gallery.getLayoutManager()).setSpanCount(columns());
    }

    static GradientDrawable surface(int fill, int border, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fill);
        drawable.setCornerRadius(radius * android.content.res.Resources.getSystem().getDisplayMetrics().density);
        drawable.setStroke(1, border);
        return drawable;
    }

    private LinearLayout row() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        layout.setGravity(Gravity.CENTER_VERTICAL);
        return layout;
    }

    private Button button(String title, boolean primary) {
        Button button = new Button(this);
        button.setText(title);
        button.setTextSize(13);
        button.setAllCaps(false);
        button.setTextColor(primary ? Color.WHITE : TEXT);
        button.setTypeface(null, Typeface.BOLD);
        button.setPadding(dp(10), 0, dp(10), 0);
        button.setMinHeight(0);
        button.setMinimumHeight(0);
        button.setStateListAnimator(null);
        button.setBackground(surface(primary ? ACCENT : SURFACE, primary ? ACCENT : Color.rgb(42, 53, 73), 10));
        return button;
    }

    private TextView text(String value, int size, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setGravity(Gravity.CENTER_VERTICAL);
        return view;
    }

    private void add(LinearLayout parent, View child, int top) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(top);
        parent.addView(child, params);
    }

    int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    @Override
    protected void onDestroy() {
        destroyed = true;
        listingCancellation.cancel();
        listingExecutor.shutdownNow();
        adapter.close();
        if (downloadService != null) downloadService.setListener(null);
        if (bound) unbindService(connection);
        super.onDestroy();
    }
}

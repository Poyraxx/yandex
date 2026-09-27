package com.poyrax.yandex;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.LruCache;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

final class MediaAdapter extends RecyclerView.Adapter<MediaAdapter.Holder> {
    private final MainActivity activity;
    private final Supplier<DiskClient> client;
    private final Runnable selectionChanged;
    private final ExecutorService previews = Executors.newFixedThreadPool(4);
    private final List<MediaFile> files = new ArrayList<>();
    private final LruCache<String, Bitmap> cache = new LruCache<String, Bitmap>(12 * 1024 * 1024) {
        @Override
        protected int sizeOf(String key, Bitmap value) { return value.getByteCount(); }
    };
    private boolean closed;

    MediaAdapter(MainActivity activity, Supplier<DiskClient> client, Runnable selectionChanged) {
        this.activity = activity;
        this.client = client;
        this.selectionChanged = selectionChanged;
    }

    void setFiles(List<MediaFile> next) {
        files.clear();
        files.addAll(next);
        cache.evictAll();
        notifyDataSetChanged();
    }

    void updateTransfer(MediaFile file) {
        if (file == null) notifyItemRangeChanged(0, files.size(), "transfer");
        else {
            int position = files.indexOf(file);
            if (position >= 0) notifyItemChanged(position, "transfer");
        }
    }

    @Override
    public int getItemCount() { return files.size(); }

    @Override
    public Holder onCreateViewHolder(ViewGroup parent, int type) { return new Holder(); }

    @Override
    public void onBindViewHolder(Holder holder, int position) {
        if (holder.cancel != null) holder.cancel.cancel();
        MediaFile file = files.get(position);
        holder.file = file;
        holder.image.setImageBitmap(null);
        holder.icon.video = file.video;
        holder.icon.invalidate();
        holder.checkbox.setOnCheckedChangeListener(null);
        holder.checkbox.setText(file.name);
        holder.checkbox.setChecked(file.selected);
        holder.checkbox.setOnCheckedChangeListener((view, checked) -> {
            file.selected = checked;
            holder.updateBorder();
            selectionChanged.run();
        });
        holder.folder.setText(file.folder());
        holder.folder.setVisibility(file.folder().isEmpty() ? View.GONE : View.VISIBLE);
        holder.size.setText(MediaFile.formatSize(file.size));
        holder.updateBorder();
        holder.updateStatus();
        loadPreview(holder);
    }

    @Override
    public void onBindViewHolder(Holder holder, int position, List<Object> payloads) {
        if (payloads.isEmpty()) onBindViewHolder(holder, position);
        else holder.updateStatus();
    }

    private void loadPreview(Holder holder) {
        MediaFile file = holder.file;
        if (file == null || file.preview == null || file.preview.isEmpty() || closed) return;
        Bitmap cached = cache.get(file.preview);
        if (cached != null) { holder.image.setImageBitmap(cached); return; }
        DiskClient.Cancellation cancel = new DiskClient.Cancellation();
        holder.cancel = cancel;
        previews.execute(() -> {
            if (cancel.isCancelled()) return;
            byte[] bytes = client.get().preview(file.preview, cancel);
            if (bytes == null || cancel.isCancelled()) return;
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = 1;
            while (bounds.outWidth / options.inSampleSize > 512 || bounds.outHeight / options.inSampleSize > 512) options.inSampleSize *= 2;
            Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
            if (bitmap == null || cancel.isCancelled()) return;
            cache.put(file.preview, bitmap);
            activity.runOnUiThread(() -> {
                if (!closed && !cancel.isCancelled() && holder.file == file) holder.image.setImageBitmap(bitmap);
            });
        });
    }

    @Override
    public void onViewDetachedFromWindow(Holder holder) {
        if (holder.cancel != null) holder.cancel.cancel();
    }

    @Override
    public void onViewAttachedToWindow(Holder holder) {
        if (holder.cancel == null || holder.cancel.isCancelled()) loadPreview(holder);
    }

    @Override
    public void onViewRecycled(Holder holder) {
        if (holder.cancel != null) holder.cancel.cancel();
        holder.file = null;
        holder.image.setImageBitmap(null);
    }

    void close() {
        closed = true;
        previews.shutdownNow();
        cache.evictAll();
    }

    final class Holder extends RecyclerView.ViewHolder {
        final LinearLayout card;
        final ImageView image;
        final MediaIcon icon;
        final CheckBox checkbox;
        final TextView folder;
        final TextView size;
        final TextView status;
        final ProgressBar progress;
        MediaFile file;
        DiskClient.Cancellation cancel;

        Holder() {
            super(new LinearLayout(activity));
            card = (LinearLayout) itemView;
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(dp(10), dp(10), dp(10), dp(12));
            RecyclerView.LayoutParams params = new RecyclerView.LayoutParams(-1, -2);
            params.setMargins(dp(4), 0, dp(4), dp(10));
            card.setLayoutParams(params);
            FrameLayout preview = new FrameLayout(activity);
            preview.setBackground(MainActivity.surface(android.graphics.Color.rgb(13, 20, 32), android.graphics.Color.TRANSPARENT, 8));
            preview.setClipToOutline(true);
            icon = new MediaIcon();
            preview.addView(icon, new FrameLayout.LayoutParams(-1, -1));
            image = new ImageView(activity);
            image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            preview.addView(image, new FrameLayout.LayoutParams(-1, -1));
            card.addView(preview, new LinearLayout.LayoutParams(-1, dp(112)));
            checkbox = new CheckBox(activity);
            checkbox.setTextColor(MainActivity.TEXT);
            checkbox.setTextSize(12);
            checkbox.setTypeface(null, Typeface.BOLD);
            checkbox.setMaxLines(1);
            checkbox.setEllipsize(TextUtils.TruncateAt.END);
            checkbox.setPadding(0, dp(6), 0, 0);
            checkbox.setButtonTintList(android.content.res.ColorStateList.valueOf(MainActivity.ACCENT));
            card.addView(checkbox, new LinearLayout.LayoutParams(-1, dp(42)));
            folder = label(11, MainActivity.MUTED);
            folder.setMaxLines(1);
            folder.setEllipsize(TextUtils.TruncateAt.MIDDLE);
            card.addView(folder);
            size = label(11, MainActivity.MUTED);
            card.addView(size);
            progress = new ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal);
            progress.setProgressTintList(android.content.res.ColorStateList.valueOf(MainActivity.ACCENT));
            card.addView(progress, new LinearLayout.LayoutParams(-1, dp(4)));
            status = label(11, MainActivity.MUTED);
            status.setPadding(0, dp(4), 0, 0);
            card.addView(status);
        }

        void updateBorder() {
            card.setBackground(MainActivity.surface(MainActivity.SURFACE, file.selected ? MainActivity.ACCENT : android.graphics.Color.rgb(38, 50, 71), 12));
        }

        void updateStatus() {
            if (file == null) return;
            boolean downloading = file.state.equals("İndiriliyor");
            progress.setVisibility(downloading ? View.VISIBLE : View.GONE);
            progress.setIndeterminate(downloading && file.total <= 0);
            int percent = file.total > 0 ? (int) Math.min(100, file.bytes * 100.0 / file.total) : 0;
            progress.setProgress(percent);
            status.setVisibility(file.state.isEmpty() ? View.GONE : View.VISIBLE);
            status.setText(file.error != null ? file.error : downloading ? (file.total > 0 ? "%" + percent : MediaFile.formatSize(file.bytes)) : file.state);
            status.setTextColor(file.error != null ? android.graphics.Color.rgb(255, 142, 145) : file.state.equals("İndirildi") ? android.graphics.Color.rgb(112, 215, 174) : MainActivity.MUTED);
        }
    }

    private TextView label(int size, int color) {
        TextView view = new TextView(activity);
        view.setTextSize(size);
        view.setTextColor(color);
        return view;
    }

    private int dp(int value) { return activity.dp(value); }

    private final class MediaIcon extends View {
        boolean video;
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Path path = new Path();
        MediaIcon() { super(activity); }
        @Override
        protected void onDraw(Canvas canvas) {
            float x = getWidth() / 2f;
            float y = getHeight() / 2f;
            paint.setColor(android.graphics.Color.rgb(101, 118, 145));
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(2));
            path.reset();
            if (video) {
                canvas.drawRoundRect(x - dp(15), y - dp(10), x + dp(8), y + dp(10), dp(2), dp(2), paint);
                path.moveTo(x + dp(8), y - dp(4));
                path.lineTo(x + dp(18), y - dp(10));
                path.lineTo(x + dp(18), y + dp(10));
                path.lineTo(x + dp(8), y + dp(4));
                canvas.drawPath(path, paint);
            } else {
                canvas.drawRoundRect(x - dp(17), y - dp(13), x + dp(17), y + dp(13), dp(2), dp(2), paint);
                canvas.drawCircle(x + dp(7), y - dp(5), dp(3), paint);
                path.moveTo(x - dp(16), y + dp(10));
                path.lineTo(x - dp(4), y - dp(3));
                path.lineTo(x + dp(7), y + dp(12));
                canvas.drawPath(path, paint);
            }
        }
    }
}

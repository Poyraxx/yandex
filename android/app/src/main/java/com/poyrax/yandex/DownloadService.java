package com.poyrax.yandex;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.net.Uri;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

import androidx.documentfile.provider.DocumentFile;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public final class DownloadService extends Service {
    static final String START = "com.poyrax.yandex.START";
    static final String CANCEL = "com.poyrax.yandex.CANCEL";
    private static final String CHANNEL = "downloads";
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Object storageLock = new Object();
    private final LocalBinder binder = new LocalBinder();
    private DiskClient.Cancellation cancellation;
    private PowerManager.WakeLock wakeLock;
    private volatile Listener listener;
    private boolean started;
    private long lastNotification;
    private List<MediaFile> selected = Collections.emptyList();
    private Map<String, List<String>> folderNames;
    private final Map<String, DocumentFile> folders = new HashMap<>();
    private DocumentFile root;
    DiskClient client = new DiskClient();
    volatile boolean running;
    volatile String summary = "";
    volatile int percent;
    String link;
    Uri target;
    List<MediaFile> gallery = Collections.emptyList();

    @Override
    public void onCreate() {
        super.onCreate();
        getSystemService(NotificationManager.class).createNotificationChannel(
                new NotificationChannel(CHANNEL, "İndirmeler", NotificationManager.IMPORTANCE_LOW));
    }

    @Override
    public IBinder onBind(Intent intent) { return binder; }

    public final class LocalBinder extends Binder {
        DownloadService getService() { return DownloadService.this; }
    }

    void setListener(Listener value) { listener = value; }

    void start(String publicLink, Uri folder, List<MediaFile> files, List<MediaFile> all) {
        if (running) return;
        link = publicLink;
        target = folder;
        gallery = new ArrayList<>(all);
        selected = new ArrayList<>(files);
        cancellation = new DiskClient.Cancellation();
        for (MediaFile file : selected) {
            file.state = "";
            file.error = null;
            file.bytes = 0;
            file.total = file.size;
        }
        percent = 0;
        summary = "İndiriliyor… 0 / " + selected.size();
        started = false;
        running = true;
        startForegroundService(new Intent(this, DownloadService.class).setAction(START));
        notifyChanged(null);
    }

    void cancel() {
        if (cancellation != null) cancellation.cancel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && CANCEL.equals(intent.getAction())) {
            cancel();
            return START_NOT_STICKY;
        }
        if (!running || started) return START_NOT_STICKY;
        started = true;
        startForeground(1, notification());
        wakeLock = getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Yandex:download");
        wakeLock.acquire(6 * 60 * 60 * 1000L);
        new Thread(this::runDownloads, "Yandex-download").start();
        return START_NOT_STICKY;
    }

    private void runDownloads() {
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            root = DocumentFile.fromTreeUri(this, target);
            if (root == null || !root.isDirectory()) throw new IOException("Klasöre erişilemiyor. Klasörü yeniden seçin.");
            folderNames = mapFolders(gallery);
            folders.clear();
            folders.put("", root);
            List<Future<?>> tasks = new ArrayList<>();
            for (MediaFile file : selected) tasks.add(workers.submit(() -> downloadFile(file)));
            for (Future<?> task : tasks) task.get();
        } catch (Exception e) {
            for (MediaFile file : selected) {
                if (!file.state.equals("İndirildi")) {
                    file.state = cancellation.isCancelled() ? "İptal edildi" : "İndirilemedi";
                    file.error = cancellation.isCancelled() ? null : e instanceof IOException ? DiskClient.message((IOException) e) : "Dosya kaydedilemedi. Klasörü kontrol edin.";
                }
            }
        } finally {
            workers.shutdown();
            int completed = 0;
            int failed = 0;
            for (MediaFile file : selected) {
                if (file.state.equals("İndirildi")) completed++;
                if (file.state.equals("İndirilemedi")) failed++;
            }
            summary = (cancellation.isCancelled() ? "İptal edildi. " : "") + completed + " indirildi" + (failed > 0 ? " · " + failed + " indirilemedi" : "");
            if (!cancellation.isCancelled()) percent = 100;
            if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
            main.post(() -> {
                stopForeground(STOP_FOREGROUND_REMOVE);
                stopSelf();
                running = false;
                if (listener != null) listener.changed(null);
            });
        }
    }

    private void downloadFile(MediaFile file) {
        try {
            cancellation.check();
            DocumentFile parent = resolveFolder(file.segments.subList(0, file.segments.size() - 1));
            file.state = "İndiriliyor";
            client.download(link, file, new DocumentDestination(parent, file), cancellation, (bytes, total) -> {
                file.bytes = bytes;
                file.total = total;
                updateProgress(file);
            });
            file.state = "İndirildi";
        } catch (IOException | SecurityException e) {
            file.state = cancellation.isCancelled() ? "İptal edildi" : "İndirilemedi";
            file.error = cancellation.isCancelled() ? null : e instanceof IOException ? DiskClient.message((IOException) e) : "Klasöre yazma izni yok. Klasörü yeniden seçin.";
        }
        updateProgress(file);
    }

    private synchronized void updateProgress(MediaFile changed) {
        int finished = 0;
        double weight = 0;
        double transferred = 0;
        for (MediaFile file : selected) {
            double size = Math.max(1, file.total);
            weight += size;
            if (file.state.equals("İndirildi") || file.state.equals("İndirilemedi") || file.state.equals("İptal edildi")) {
                finished++;
                transferred += size;
            } else transferred += Math.min(size, file.bytes);
        }
        percent = weight > 0 ? (int) Math.min(100, transferred * 100 / weight) : 0;
        summary = "İndiriliyor… " + finished + " / " + selected.size() + " · %" + percent;
        notifyChanged(changed);
        long now = System.currentTimeMillis();
        if (running && now - lastNotification > 800) {
            lastNotification = now;
            getSystemService(NotificationManager.class).notify(1, notification());
        }
    }

    private void notifyChanged(MediaFile file) {
        main.post(() -> { if (listener != null) listener.changed(file); });
    }

    private Notification notification() {
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent cancel = PendingIntent.getService(this, 1, new Intent(this, DownloadService.class).setAction(CANCEL), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_download).setContentTitle("Yandexdisk Downloader")
                .setContentText(summary).setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
                .setProgress(100, percent, false).addAction(new Notification.Action.Builder(null, "İptal", cancel).build()).build();
    }

    private static String folderKey(List<String> segments) { return String.join("\u0000", segments); }

    private static Map<String, List<String>> mapFolders(List<MediaFile> gallery) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        Map<String, Set<String>> used = new HashMap<>();
        result.put("", new ArrayList<>());
        List<MediaFile> ordered = new ArrayList<>(gallery);
        ordered.sort((a, b) -> String.join("/", a.segments).compareTo(String.join("/", b.segments)));
        for (MediaFile file : ordered) {
            for (int depth = 1; depth < file.segments.size(); depth++) {
                String key = folderKey(file.segments.subList(0, depth));
                if (result.containsKey(key)) continue;
                String parentKey = folderKey(file.segments.subList(0, depth - 1));
                Set<String> names = used.computeIfAbsent(parentKey, ignored -> new HashSet<>());
                String original = DiskClient.safeName(file.segments.get(depth - 1));
                String name = original;
                int number = 1;
                while (!names.add(name.toLowerCase(Locale.ROOT))) name = original + " (" + number++ + ")";
                List<String> local = new ArrayList<>(result.get(parentKey));
                local.add(name);
                result.put(key, local);
            }
        }
        return result;
    }

    private DocumentFile resolveFolder(List<String> segments) throws IOException {
        synchronized (storageLock) {
            String key = folderKey(segments);
            if (folders.containsKey(key)) return folders.get(key);
            DocumentFile current = root;
            for (String name : folderNames.get(key)) {
                DocumentFile next = current.findFile(name);
                if (next != null && !next.isDirectory()) throw new IOException("Klasör oluşturulamadı. Aynı adda bir dosya var.");
                if (next == null) next = current.createDirectory(name);
                if (next == null) throw new IOException("Klasör oluşturulamadı.");
                current = next;
            }
            folders.put(key, current);
            return current;
        }
    }

    private final class DocumentDestination implements DiskClient.Destination {
        private final DocumentFile parent;
        private final MediaFile file;
        private DocumentFile temporary;

        DocumentDestination(DocumentFile parent, MediaFile file) { this.parent = parent; this.file = file; }

        @Override
        public OutputStream open() throws IOException {
            temporary = parent.createFile(file.mime, "." + UUID.randomUUID() + ".part");
            if (temporary == null) throw new DiskClient.PermanentException("Dosya oluşturulamadı. Klasörü kontrol edin.");
            OutputStream output = getContentResolver().openOutputStream(temporary.getUri(), "w");
            if (output == null) throw new DiskClient.PermanentException("Dosya kaydedilemedi.");
            return output;
        }

        @Override
        public void complete() throws IOException {
            synchronized (storageLock) {
                String original = DiskClient.safeName(file.name);
                int dot = original.lastIndexOf('.');
                String stem = dot > 0 ? original.substring(0, dot) : original;
                String extension = dot > 0 ? original.substring(dot) : "";
                String name = original;
                int number = 1;
                while (parent.findFile(name) != null) name = stem + " (" + number++ + ")" + extension;
                if (temporary == null || !temporary.renameTo(name)) throw new DiskClient.PermanentException("Dosya kaydedilemedi.");
                temporary = null;
            }
        }

        @Override
        public void abort() throws IOException {
            if (temporary != null) {
                if (temporary.exists() && !temporary.delete()) throw new IOException("Yarım dosya temizlenemedi.");
                temporary = null;
            }
        }
    }

    @Override
    public void onTimeout(int startId, int foregroundServiceType) { cancel(); }

    @Override
    public void onDestroy() {
        cancel();
        listener = null;
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        super.onDestroy();
    }

    interface Listener { void changed(MediaFile file); }
}

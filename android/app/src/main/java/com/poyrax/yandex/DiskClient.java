package com.poyrax.yandex;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.IntConsumer;

public class DiskClient {
    private static final String API = "https://cloud-api.yandex.net/v1/disk/public/resources";
    private static final Set<String> IMAGES = new HashSet<>(Arrays.asList("jpg", "jpeg", "png", "gif", "webp", "bmp", "tif", "tiff", "svg", "avif", "heic", "heif", "ico", "jxl"));
    private static final Set<String> VIDEOS = new HashSet<>(Arrays.asList("mp4", "mov", "mkv", "avi", "webm", "m4v", "wmv", "mpeg", "mpg", "3gp", "mts", "m2ts", "ts", "vob", "ogv", "flv"));

    public static String validateLink(String input) throws IOException {
        try {
            URI uri = new URI(input.trim());
            String host = uri.getHost();
            String[] hosts = {"disk.yandex.com", "disk.yandex.ru", "disk.yandex.com.tr", "disk.yandex.kz", "disk.yandex.by", "disk.yandex.uz", "yadi.sk"};
            String path = uri.getRawPath();
            if (!"https".equalsIgnoreCase(uri.getScheme()) || host == null || uri.getUserInfo() != null ||
                    (uri.getPort() != -1 && uri.getPort() != 443) || !Arrays.asList(hosts).contains(host.toLowerCase(Locale.ROOT)) ||
                    path == null || !path.matches("/(d|i)/[^/]+.*")) {
                throw new IOException("Geçerli bir Yandex Disk bağlantısı girin.");
            }
            return "https://" + host.toLowerCase(Locale.ROOT) + path;
        } catch (java.net.URISyntaxException e) {
            throw new IOException("Geçerli bir Yandex Disk bağlantısı girin.");
        }
    }

    public List<MediaFile> list(String link, Cancellation cancellation, IntConsumer progress) throws IOException {
        link = validateLink(link);
        List<MediaFile> files = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        ArrayDeque<Folder> folders = new ArrayDeque<>();
        JSONObject root = json(resourceUrl(link, null, 0), cancellation);
        if ("file".equals(root.optString("type"))) {
            addFile(root, new ArrayList<>(Arrays.asList(required(root, "name"))), "/", files, progress);
            return files;
        }
        if (!"dir".equals(root.optString("type"))) {
            throw new IOException("Dosya listesi alınamadı.");
        }
        seen.add("/");
        readPages(link, root, "/", new ArrayList<>(), files, seen, folders, cancellation, progress);
        while (!folders.isEmpty()) {
            cancellation.check();
            Folder folder = folders.remove();
            JSONObject page = json(resourceUrl(link, folder.path, 0), cancellation);
            if (!"dir".equals(page.optString("type"))) {
                throw new IOException("Klasörün tamamı listelenemedi.");
            }
            readPages(link, page, folder.path, folder.segments, files, seen, folders, cancellation, progress);
        }
        files.sort((a, b) -> String.join("/", a.segments).compareToIgnoreCase(String.join("/", b.segments)));
        return files;
    }

    private void readPages(String link, JSONObject first, String path, List<String> parent, List<MediaFile> files,
                           Set<String> seen, ArrayDeque<Folder> folders, Cancellation cancel, IntConsumer progress) throws IOException {
        JSONObject page = first;
        int offset = 0;
        JSONObject embedded = first.optJSONObject("_embedded");
        if (embedded == null) throw new IOException("Klasörün tamamı listelenemedi.");
        long total = embedded.optLong("total", -1);
        while (true) {
            cancel.check();
            embedded = page.optJSONObject("_embedded");
            JSONArray items = embedded == null ? null : embedded.optJSONArray("items");
            if (items == null) throw new IOException("Klasörün tamamı listelenemedi.");
            for (int index = 0; index < items.length(); index++) {
                cancel.check();
                JSONObject item = items.optJSONObject(index);
                if (item == null) throw new IOException("Yandex Disk yanıtı okunamadı.");
                String name = required(item, "name");
                List<String> segments = new ArrayList<>(parent);
                segments.add(name);
                String itemPath = item.optString("path", "/" + String.join("/", segments));
                if (!seen.add(itemPath)) throw new IOException("Dosya listesi tutarsız. Yeniden deneyin.");
                if ("dir".equals(item.optString("type"))) folders.add(new Folder(itemPath, segments));
                else if ("file".equals(item.optString("type"))) addFile(item, segments, itemPath, files, progress);
                else throw new IOException("Dosya listesi alınamadı.");
            }
            offset += items.length();
            if (total >= 0 && offset < total && items.length() == 0) throw new IOException("Klasörün tamamı listelenemedi.");
            boolean more = total >= 0 ? offset < total : items.length() >= 100;
            if (!more) return;
            page = json(resourceUrl(link, path, offset), cancel);
        }
    }

    private static void addFile(JSONObject item, List<String> segments, String fallback, List<MediaFile> files, IntConsumer progress) throws IOException {
        String name = required(item, "name");
        String mime = item.optString("mime_type", "").toLowerCase(Locale.ROOT);
        boolean video = mime.startsWith("video/");
        boolean image = mime.startsWith("image/");
        if (mime.isEmpty() || mime.equals("application/octet-stream")) {
            String extension = name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
            video = "video".equals(item.optString("media_type")) || VIDEOS.contains(extension);
            image = "image".equals(item.optString("media_type")) || IMAGES.contains(extension);
        }
        if (!image && !video) return;
        files.add(new MediaFile(name, item.optString("path", fallback), segments, Math.max(0, item.optLong("size", 0)),
                video, item.optString("preview", ""), mime.isEmpty() ? "application/octet-stream" : mime));
        if (progress != null) progress.accept(files.size());
    }

    public void download(String link, MediaFile file, Destination destination, Cancellation cancel, TransferProgress progress) throws IOException {
        for (int attempt = 0; attempt < 3; attempt++) {
            HttpURLConnection connection = null;
            try {
                cancel.check();
                progress.update(0, file.size);
                JSONObject address = json(API + "/download?public_key=" + encode(link) + "&path=" + encode(file.path), cancel);
                Object href = address.opt("href");
                if (!(href instanceof String) || ((String) href).trim().isEmpty())
                    throw new PermanentException("İndirme adresi verilmedi. Paylaşımın indirme iznini kontrol edin.");
                connection = open((String) href, cancel);
                int code = connection.getResponseCode();
                if (transientStatus(code) || code == 403 || code == 401) throw new RetryException(delay(connection, attempt));
                checkStatus(code);
                long length = connection.getContentLengthLong();
                long expected = length >= 0 ? length : file.size;
                long bytes = 0;
                long lastUpdate = System.nanoTime();
                try (InputStream source = connection.getInputStream(); OutputStream output = destination.open()) {
                    byte[] buffer = new byte[131072];
                    while (true) {
                        cancel.check();
                        int count = source.read(buffer);
                        if (count < 0) break;
                        cancel.check();
                        output.write(buffer, 0, count);
                        bytes += count;
                        if (System.nanoTime() - lastUpdate > 100_000_000L) {
                            progress.update(bytes, expected);
                            lastUpdate = System.nanoTime();
                        }
                    }
                    output.flush();
                }
                if ((length >= 0 && bytes != length) || (file.size > 0 && bytes != file.size)) throw new RetryException(1000L * (attempt + 1));
                cancel.check();
                destination.complete();
                progress.update(bytes, bytes);
                return;
            } catch (IOException e) {
                destination.abort();
                cancel.check();
                if (attempt == 2 || e instanceof PermanentException) throw new IOException(message(e));
                cancel.pause(e instanceof RetryException ? ((RetryException) e).delay : 1000L * (attempt + 1));
            } finally {
                if (connection != null) cancel.release(connection);
            }
        }
    }

    public byte[] preview(String url, Cancellation cancel) {
        HttpURLConnection connection = null;
        try {
            if (url == null || url.isEmpty()) return null;
            connection = open(url, cancel);
            String type = connection.getContentType();
            if (connection.getResponseCode() != 200 || type == null || !type.startsWith("image/") || connection.getContentLengthLong() > 4 * 1024 * 1024) return null;
            try (InputStream source = connection.getInputStream()) {
                return readLimited(source, 4 * 1024 * 1024, cancel);
            }
        } catch (IOException e) {
            return null;
        } finally {
            if (connection != null) cancel.release(connection);
        }
    }

    private JSONObject json(String url, Cancellation cancel) throws IOException {
        for (int attempt = 0; ; attempt++) {
            HttpURLConnection connection = null;
            try {
                cancel.check();
                connection = open(url, cancel);
                int code = connection.getResponseCode();
                if (transientStatus(code)) throw new RetryException(delay(connection, attempt));
                checkStatus(code);
                try (InputStream stream = connection.getInputStream()) {
                    return new JSONObject(new String(readLimited(stream, 8 * 1024 * 1024, cancel), StandardCharsets.UTF_8));
                } catch (JSONException e) {
                    throw new PermanentException("Yandex Disk yanıtı okunamadı.");
                }
            } catch (IOException e) {
                cancel.check();
                if (e instanceof PermanentException) throw e;
                if (attempt >= 2) throw new IOException(message(e));
                cancel.pause(e instanceof RetryException ? ((RetryException) e).delay : 1000L * (attempt + 1));
            } finally {
                if (connection != null) cancel.release(connection);
            }
        }
    }

    protected HttpURLConnection open(String url, Cancellation cancel) throws IOException {
        URL address = new URL(url);
        if (!address.getProtocol().equals("https") || address.getUserInfo() != null) throw new PermanentException("İndirme bağlantısı alınamadı.");
        HttpURLConnection connection = (HttpURLConnection) address.openConnection();
        connection.setConnectTimeout(20000);
        connection.setReadTimeout(45000);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("Accept-Encoding", "identity");
        cancel.track(connection);
        return connection;
    }

    private static byte[] readLimited(InputStream source, int maximum, Cancellation cancel) throws IOException {
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        byte[] buffer = new byte[16384];
        int count;
        while ((count = source.read(buffer)) >= 0) {
            cancel.check();
            if (result.size() + count > maximum) throw new PermanentException("Yandex Disk yanıtı okunamadı.");
            result.write(buffer, 0, count);
        }
        return result.toByteArray();
    }

    private static String resourceUrl(String link, String path, int offset) {
        return API + "?public_key=" + encode(link) + "&limit=100&offset=" + offset + "&preview_size=M&preview_crop=false" +
                (path == null ? "" : "&path=" + encode(path));
    }

    private static String encode(String value) {
        try { return URLEncoder.encode(value, "UTF-8"); }
        catch (java.io.UnsupportedEncodingException e) { throw new IllegalStateException(e); }
    }

    private static String required(JSONObject item, String key) throws IOException {
        String value = item.optString(key, "");
        if (value.isEmpty()) throw new PermanentException("Yandex Disk yanıtı okunamadı.");
        return value;
    }

    private static boolean transientStatus(int code) {
        return code == 429 || code == 408 || code >= 500;
    }

    private static long delay(HttpURLConnection connection, int attempt) {
        try {
            return Math.max(0, Math.min(60000, Long.parseLong(connection.getHeaderField("Retry-After")) * 1000));
        } catch (Exception e) {
            return 1000L * (attempt + 1);
        }
    }

    private static void checkStatus(int code) throws IOException {
        if (code >= 200 && code < 300) return;
        if (code == 404) throw new PermanentException("Bağlantı bulunamadı veya paylaşım kapalı.");
        if (code == 403 || code == 401) throw new PermanentException("Bu bağlantıya erişilemiyor veya indirmeye izin verilmiyor.");
        if (code == 400) throw new PermanentException("Bağlantı okunamadı. Paylaşım bağlantısını kontrol edin.");
        throw new PermanentException("Yandex Disk isteği tamamlanamadı. Yeniden deneyin.");
    }

    public static String message(IOException e) {
        if (e instanceof PermanentException || e.getMessage() != null && e.getMessage().matches(".*[çğıöşüÇĞİÖŞÜ].*")) return e.getMessage();
        return "İndirme kesildi. Bağlantıyı ve klasörü kontrol edin.";
    }

    public static String safeName(String value) {
        String clean = value.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").replaceAll("[ .]+$", "");
        if (clean.isEmpty()) return "_";
        String stem = clean.split("\\.", 2)[0].trim();
        if (stem.matches("(?i)(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])")) clean = "_" + clean;
        if (clean.length() > 180) {
            int dot = clean.lastIndexOf('.');
            String extension = dot >= 0 && clean.length() - dot <= 20 ? clean.substring(dot) : "";
            clean = clean.substring(0, 180 - extension.length()) + extension;
        }
        return clean;
    }

    public interface Destination {
        OutputStream open() throws IOException;
        void complete() throws IOException;
        void abort() throws IOException;
    }

    public interface TransferProgress {
        void update(long bytes, long total);
    }

    public static final class Cancellation {
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final Set<HttpURLConnection> connections = ConcurrentHashMap.newKeySet();

        public void cancel() {
            cancelled.set(true);
            for (HttpURLConnection connection : connections) connection.disconnect();
            synchronized (this) { notifyAll(); }
        }

        public boolean isCancelled() { return cancelled.get(); }

        public void check() throws InterruptedIOException {
            if (cancelled.get()) throw new InterruptedIOException("İptal edildi.");
        }

        public void pause(long milliseconds) throws IOException {
            check();
            synchronized (this) {
                check();
                try { wait(Math.max(1, milliseconds)); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new InterruptedIOException("İptal edildi."); }
            }
            check();
        }

        public void track(HttpURLConnection connection) throws IOException {
            connections.add(connection);
            if (isCancelled()) { connection.disconnect(); connections.remove(connection); check(); }
        }

        public void release(HttpURLConnection connection) {
            connections.remove(connection);
            connection.disconnect();
        }
    }

    private static final class Folder {
        final String path;
        final List<String> segments;
        Folder(String path, List<String> segments) { this.path = path; this.segments = segments; }
    }

    public static class PermanentException extends IOException {
        public PermanentException(String message) { super(message); }
    }

    private static final class RetryException extends IOException {
        final long delay;
        RetryException(long delay) { this.delay = delay; }
    }
}

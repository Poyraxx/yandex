package com.poyrax.yandex;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

public class DiskClientTest {
    private static final String LINK = "https://disk.yandex.com/d/test";
    private static final byte[] PAYLOAD = "media-fixture".getBytes(StandardCharsets.UTF_8);

    @Test
    public void linksAreLimitedToYandexShares() throws Exception {
        assertEquals(LINK, DiskClient.validateLink(" " + LINK + "?utm_source=copy#x "));
        for (String value : Arrays.asList("", "https://example.com/d/test", "https://disk.yandex.com.evil/d/test", "http://disk.yandex.com/d/test", "https://x@disk.yandex.com/d/test", "https://disk.yandex.com:80/d/test", "https://disk.yandex.com/d")) {
            assertThrows(IOException.class, () -> DiskClient.validateLink(value));
        }
    }

    @Test
    public void singleImageAndVideoAreListed() throws Exception {
        for (String mime : Arrays.asList("image/jpeg", "video/mp4")) {
            Fake client = new Fake(url -> response(resource("file", "/", mime).toString()));
            List<MediaFile> files = client.list(LINK, new DiskClient.Cancellation(), null);
            assertEquals(1, files.size());
            assertEquals(mime.startsWith("video"), files.get(0).video);
            assertEquals("", files.get(0).folder());
        }
    }

    @Test
    public void foldersAndMissingMimeTypesAreHandled() throws Exception {
        JSONArray root = new JSONArray().put(resource("foto.jpg", "/foto.jpg", "image/jpeg"))
                .put(resource("fake.jpg", "/fake.jpg", "text/plain"))
                .put(new JSONObject().put("name", "Alt").put("type", "dir").put("path", "/Alt"));
        Fake client = new Fake(url -> response(url.contains("path=%2FAlt")
                ? directory(new JSONArray().put(resource("video.mkv", "/Alt/video.mkv", "")), 1).toString()
                : directory(root, 3).toString()));
        List<MediaFile> files = client.list(LINK, new DiskClient.Cancellation(), null);
        assertEquals(2, files.size());
        assertTrue(files.get(0).video);
        assertEquals("Alt", files.get(0).folder());
    }

    @Test
    public void allPagesAreRead() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        Fake client = new Fake(url -> {
            int page = requests.getAndIncrement();
            JSONArray items = new JSONArray();
            for (int i = page * 100; i < Math.min(203, (page + 1) * 100); i++) items.put(resource(i + ".jpg", "/" + i + ".jpg", "image/jpeg"));
            return response(directory(items, 203).toString());
        });
        assertEquals(203, client.list(LINK, new DiskClient.Cancellation(), null).size());
        assertEquals(3, requests.get());
    }

    @Test
    public void incompletePagesAndMalformedResponsesFail() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        Fake client = new Fake(url -> response(directory(requests.getAndIncrement() == 0
                ? new JSONArray().put(resource("a.jpg", "/a.jpg", "image/jpeg")) : new JSONArray(), 2).toString()));
        assertThrows(IOException.class, () -> client.list(LINK, new DiskClient.Cancellation(), null));
        assertThrows(IOException.class, () -> new Fake(url -> response("[]")).list(LINK, new DiskClient.Cancellation(), null));
    }

    @Test
    public void forbiddenLinksDoNotReturnPartialResults() throws Exception {
        Fake client = new Fake(url -> new Reply(403, new byte[0], "application/json"));
        IOException error = assertThrows(IOException.class, () -> client.list(LINK, new DiskClient.Cancellation(), null));
        assertTrue(error.getMessage().contains("erişilemiyor"));
    }

    @Test
    public void temporaryApiFailuresAreRetried() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        Fake client = new Fake(url -> attempts.incrementAndGet() < 3
                ? new Reply(429, new byte[0], "application/json") : response(resource("a.jpg", "/", "image/jpeg").toString()));
        assertEquals(1, client.list(LINK, new DiskClient.Cancellation(), null).size());
        assertEquals(3, attempts.get());
    }

    @Test
    public void listingCancellationStopsTraversal() throws Exception {
        DiskClient.Cancellation cancel = new DiskClient.Cancellation();
        Fake client = new Fake(url -> response(directory(new JSONArray().put(resource("a.jpg", "/a.jpg", "image/jpeg"))
                .put(resource("b.jpg", "/b.jpg", "image/jpeg")), 2).toString()));
        assertThrows(InterruptedIOException.class, () -> client.list(LINK, cancel, count -> cancel.cancel()));
    }

    @Test
    public void downloadsKeepOriginalBytes() throws Exception {
        Fake client = new Fake(url -> url.contains("/resources/download") ? response("{\"href\":\"https://download.test/file\"}")
                : new Reply(200, PAYLOAD, "image/jpeg"));
        MemoryDestination destination = new MemoryDestination();
        client.download(LINK, file(), destination, new DiskClient.Cancellation(), (bytes, total) -> {});
        assertTrue(destination.completed);
        assertArrayEquals(PAYLOAD, destination.bytes);
    }

    @Test
    public void emptyDownloadAddressesFailWithoutReadingFileData() throws Exception {
        for (String json : Arrays.asList("{\"method\":\"GET\",\"href\":\"\",\"templated\":false}", "{\"href\":\"   \"}", "{\"href\":null}", "{}")) {
            AtomicInteger requests = new AtomicInteger();
            Fake client = new Fake(url -> {
                requests.incrementAndGet();
                assertTrue(url.contains("/resources"));
                return response(json);
            });
            MemoryDestination destination = new MemoryDestination();
            IOException error = assertThrows(IOException.class, () -> client.download(LINK, file(), destination,
                    new DiskClient.Cancellation(), (bytes, total) -> {}));
            assertTrue(error.getMessage().contains("İndirme adresi verilmedi"));
            assertEquals(2, requests.get());
            assertFalse(destination.completed);
            assertNull(destination.output);
        }
    }

    @Test
    public void originalAddressesAreVerifiedBeforeCompletion() throws Exception {
        for (boolean direct : Arrays.asList(false, true)) {
            JSONObject metadata = resource("a.jpg", "/a.jpg", "image/jpeg").put("sha256", payloadHash())
                    .put("file", direct ? "https://download.test/original" : "")
                    .put("sizes", new JSONArray().put(new JSONObject().put("name", "ORIGINAL").put("url", "https://download.test/original")));
            Fake client = new Fake(url -> url.contains("/resources/download") ? response("{\"href\":\"\"}") :
                    url.contains("/resources?") ? response(metadata.toString()) : new Reply(200, PAYLOAD, "image/jpeg"));
            MemoryDestination destination = new MemoryDestination();
            MediaFile file = direct ? new MediaFile("film.mp4", "/film.mp4", Arrays.asList("film.mp4"), PAYLOAD.length, true, "", "video/mp4") : file();
            client.download(LINK, file, destination, new DiskClient.Cancellation(), (bytes, total) -> {});
            assertTrue(destination.completed);
            assertArrayEquals(PAYLOAD, destination.bytes);
        }
    }

    @Test
    public void equalSizedFilesWithWrongHashAreRemoved() throws Exception {
        JSONObject metadata = resource("a.jpg", "/a.jpg", "image/jpeg").put("sha256", "0".repeat(64)).put("file", "https://download.test/original");
        Fake client = new Fake(url -> url.contains("/resources/download") ? response("{\"href\":\"\"}") :
                url.contains("/resources?") ? response(metadata.toString()) : new Reply(200, PAYLOAD, "image/jpeg"));
        MemoryDestination destination = new MemoryDestination();
        IOException error = assertThrows(IOException.class, () -> client.download(LINK, file(), destination, new DiskClient.Cancellation(), (bytes, total) -> {}));
        assertTrue(error.getMessage().contains("Dosya doğrulanamadı"));
        assertFalse(destination.completed);
        assertNull(destination.output);
    }

    @Test
    public void previewsAndUnverifiedAddressesAreNotSavedAsOriginals() throws Exception {
        for (String variant : Arrays.asList("no-hash", "preview", "video")) {
            JSONObject metadata = resource("a.jpg", "/a.jpg", "image/jpeg").put("sha256", variant.equals("no-hash") ? "" : payloadHash())
                    .put("preview", "https://download.test/preview")
                    .put("sizes", new JSONArray().put(new JSONObject().put("name", variant.equals("preview") ? "M" : "ORIGINAL").put("url", "https://download.test/preview")));
            AtomicInteger transfers = new AtomicInteger();
            Fake client = new Fake(url -> {
                if (url.contains("/resources/download")) return response("{\"href\":\"\"}");
                if (url.contains("/resources?")) return response(metadata.toString());
                if (url.startsWith("https://disk.yandex.com/")) return response("<html></html>");
                transfers.incrementAndGet();
                return new Reply(200, PAYLOAD, "image/jpeg");
            });
            MemoryDestination destination = new MemoryDestination();
            MediaFile file = variant.equals("video") ? new MediaFile("film.mp4", "/film.mp4", Arrays.asList("film.mp4"), PAYLOAD.length, true, "", "video/mp4") : file();
            assertThrows(IOException.class, () -> client.download(LINK, file, destination, new DiskClient.Cancellation(), (bytes, total) -> {}));
            assertEquals(0, transfers.get());
            assertFalse(destination.completed);
            assertNull(destination.output);
        }
    }

    @Test
    public void expiredOriginalAddressesAreRefreshed() throws Exception {
        AtomicInteger metadataCalls = new AtomicInteger();
        Fake client = new Fake(url -> {
            if (url.contains("/resources/download")) return response("{\"href\":\"\"}");
            if (url.contains("/resources?")) return response(resource("a.jpg", "/a.jpg", "image/jpeg").put("sha256", payloadHash())
                    .put("file", "https://download.test/" + metadataCalls.incrementAndGet()).toString());
            return url.endsWith("/1") ? new Reply(403, new byte[0], "text/plain") : new Reply(200, PAYLOAD, "image/jpeg");
        });
        MemoryDestination destination = new MemoryDestination();
        client.download(LINK, file(), destination, new DiskClient.Cancellation(), (bytes, total) -> {});
        assertEquals(2, metadataCalls.get());
        assertTrue(destination.completed);
    }

    private static String payloadHash() throws Exception {
        StringBuilder result = new StringBuilder(64);
        for (byte value : MessageDigest.getInstance("SHA-256").digest(PAYLOAD)) result.append(String.format("%02x", value & 0xff));
        return result.toString();
    }

    private static final byte[] VIDEO_PAYLOAD = videoPayload();

    private static byte[] videoPayload() {
        byte[] bytes = new byte[188 * 3];
        for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) (i % 188 == 0 ? 0x47 : 1);
        return bytes;
    }

    private static MediaFile video() { return new MediaFile("film.mp4", "/film.mp4", Arrays.asList("film.mp4"), PAYLOAD.length, true, "", "video/mp4"); }

    private static Fake videoClient(Route route) {
        return new Fake(url -> {
            if (url.contains("cloud-api.yandex.net")) return response("{\"href\":\"\"}");
            if (url.equals(LINK)) {
                JSONObject store = new JSONObject().put("rootResourceId", "root").put("resources", new JSONObject().put("root", new JSONObject().put("type", "dir").put("hash", "public-hash")))
                        .put("environment", new JSONObject().put("sk", "public-sk").put("yandexuid", "123"));
                return response("<script id=\"store-prefetch\">" + store + "</script>");
            }
            return route.get(url);
        });
    }

    private static Reply videoStreams() throws Exception {
        JSONArray videos = new JSONArray();
        for (String dimension : Arrays.asList("240p", "adaptive", "1080p")) {
            videos.put(new JSONObject().put("dimension", dimension).put("size", new JSONObject().put("height", dimension.equals("1080p") ? 1080 : dimension.equals("240p") ? 240 : 0))
                    .put("url", dimension.equals("1080p") ? "https://video.test/high/list.m3u8" : "https://video.test/low/list.m3u8"));
        }
        return response(new JSONObject().put("data", new JSONObject().put("videos", videos)).toString());
    }

    @Test
    public void highestPlaybackQualityKeepsAllSegmentsAndUsesTsName() throws Exception {
        Fake client = videoClient(url -> url.endsWith("get-video-streams") ? videoStreams() :
                url.equals("https://video.test/high/list.m3u8") ? response("#EXTM3U\n#EXTINF:4,\n1.ts\n#EXTINF:2,\n2.ts\n#EXT-X-ENDLIST\n") :
                url.equals("https://video.test/high/1.ts") || url.equals("https://video.test/high/2.ts") ? new Reply(200, VIDEO_PAYLOAD, "video/mp2t") : throwUnexpectedUrl(url));
        MemoryDestination destination = new MemoryDestination();
        List<Long> transferred = new ArrayList<>();
        client.download(LINK, video(), destination, new DiskClient.Cancellation(), (bytes, total) -> transferred.add(bytes));
        assertTrue(destination.completed);
        assertEquals("film.ts", destination.name);
        assertEquals("video/mp2t", destination.mime);
        assertEquals(VIDEO_PAYLOAD.length * 2, destination.bytes.length);
        assertArrayEquals(VIDEO_PAYLOAD, Arrays.copyOfRange(destination.bytes, 0, VIDEO_PAYLOAD.length));
        assertArrayEquals(VIDEO_PAYLOAD, Arrays.copyOfRange(destination.bytes, VIDEO_PAYLOAD.length, destination.bytes.length));
        assertTrue(transferred.contains((long) VIDEO_PAYLOAD.length));
        assertEquals("public-hash:/film.mp4", new JSONObject(client.posts.get(0)).getString("hash"));
        assertTrue(client.cookieHeaders.get(0).contains("i=anon-session-"));
    }

    private static Reply throwUnexpectedUrl(String url) { throw new AssertionError("Yanlış video adresi: " + url); }

    @Test
    public void parallelPlaybackDownloadsKeepTheirOwnAnonymousSessions() throws Exception {
        Fake client = videoClient(url -> url.endsWith("get-video-streams") ? videoStreams() :
                url.endsWith(".m3u8") ? response("#EXTM3U\n1.ts\n#EXT-X-ENDLIST\n") : new Reply(200, VIDEO_PAYLOAD, "video/mp2t"));
        client.pageReady = new CyclicBarrier(2);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            List<Future<MemoryDestination>> transfers = new ArrayList<>();
            for (int i = 0; i < 2; i++) transfers.add(workers.submit(() -> {
                MemoryDestination destination = new MemoryDestination();
                client.download(LINK, video(), destination, new DiskClient.Cancellation(), (bytes, total) -> {});
                return destination;
            }));
            for (Future<MemoryDestination> transfer : transfers) {
                MemoryDestination destination = transfer.get(10, TimeUnit.SECONDS);
                assertTrue(destination.completed);
                assertArrayEquals(VIDEO_PAYLOAD, destination.bytes);
            }
        } finally { workers.shutdownNow(); }
    }

    @Test
    public void failedVideoSegmentsRefreshThePlaybackAddress() throws Exception {
        AtomicInteger addresses = new AtomicInteger();
        AtomicInteger attempts = new AtomicInteger();
        Fake client = videoClient(url -> {
            if (url.endsWith("get-video-streams")) { addresses.incrementAndGet(); return videoStreams(); }
            if (url.endsWith(".m3u8")) return response("#EXTM3U\n1.ts\n#EXT-X-ENDLIST\n");
            return attempts.incrementAndGet() == 1 ? new Reply(403, new byte[0], "text/plain") : new Reply(200, VIDEO_PAYLOAD, "video/mp2t");
        });
        MemoryDestination destination = new MemoryDestination();
        client.download(LINK, video(), destination, new DiskClient.Cancellation(), (bytes, total) -> {});
        assertEquals(2, addresses.get());
        assertTrue(destination.completed);
        assertArrayEquals(VIDEO_PAYLOAD, destination.bytes);
    }

    @Test
    public void cancelledVideoSegmentsAreNotCompleted() throws Exception {
        Fake client = videoClient(url -> url.endsWith("get-video-streams") ? videoStreams() :
                url.endsWith(".m3u8") ? response("#EXTM3U\n1.ts\n2.ts\n#EXT-X-ENDLIST\n") : new Reply(200, VIDEO_PAYLOAD, "video/mp2t"));
        MemoryDestination destination = new MemoryDestination();
        DiskClient.Cancellation cancel = new DiskClient.Cancellation();
        assertThrows(IOException.class, () -> client.download(LINK, video(), destination, cancel, (bytes, total) -> { if (bytes > 0) cancel.cancel(); }));
        assertFalse(destination.completed);
        assertNull(destination.output);
    }

    @Test
    public void incompleteEncryptedAndInvalidPlaybackStreamsAreRejected() throws Exception {
        for (String playlist : Arrays.asList("#EXTM3U\n1.ts", "#EXTM3U\n#EXT-X-KEY:METHOD=SAMPLE-AES\n1.ts\n#EXT-X-ENDLIST", "#EXTM3U\n#EXT-X-MAP:URI=init.mp4\n1.ts\n#EXT-X-ENDLIST", "#EXTM3U\nfile:///outside\n#EXT-X-ENDLIST", "#EXTM3U\n1.ts\n#EXT-X-ENDLIST")) {
            Fake client = videoClient(url -> url.endsWith("get-video-streams") ? videoStreams() :
                    url.endsWith(".m3u8") ? response(playlist) : url.startsWith("https://video.test/") ? new Reply(200, PAYLOAD, "video/mp2t") : throwUnexpectedUrl(url));
            MemoryDestination destination = new MemoryDestination();
            assertThrows(IOException.class, () -> client.download(LINK, video(), destination, new DiskClient.Cancellation(), (bytes, total) -> {}));
            assertFalse(destination.completed);
            assertNull(destination.output);
        }
    }

    @Test
    public void expiredDownloadAddressIsRefreshed() throws Exception {
        AtomicInteger links = new AtomicInteger();
        AtomicInteger transfers = new AtomicInteger();
        Fake client = new Fake(url -> {
            if (url.contains("/resources/download")) { links.incrementAndGet(); return response("{\"href\":\"https://download.test/file\"}"); }
            return transfers.incrementAndGet() == 1 ? new Reply(403, new byte[0], "text/plain") : new Reply(200, PAYLOAD, "image/jpeg");
        });
        MemoryDestination destination = new MemoryDestination();
        client.download(LINK, file(), destination, new DiskClient.Cancellation(), (bytes, total) -> {});
        assertEquals(2, links.get());
        assertTrue(destination.completed);
    }

    @Test
    public void incompleteTransfersAreNeverCompleted() throws Exception {
        Fake client = new Fake(url -> url.contains("/resources/download") ? response("{\"href\":\"https://download.test/file\"}")
                : new Reply(200, new byte[2], "image/jpeg"));
        MemoryDestination destination = new MemoryDestination();
        assertThrows(IOException.class, () -> client.download(LINK, file(), destination, new DiskClient.Cancellation(), (bytes, total) -> {}));
        assertFalse(destination.completed);
        assertNull(destination.output);
    }

    @Test
    public void previewDoesNotReadVideoOrOversizedBodies() {
        AtomicInteger reads = new AtomicInteger();
        Fake client = new Fake(url -> new Reply(200, new byte[5 * 1024 * 1024], url.endsWith("video") ? "video/mp4" : "image/png")) {
            @Override
            protected HttpURLConnection open(String url, DiskClient.Cancellation cancel) throws IOException {
                HttpURLConnection connection = super.open(url, cancel);
                return new HttpURLConnection(connection.getURL()) {
                    @Override public int getResponseCode() throws IOException { return connection.getResponseCode(); }
                    @Override public String getContentType() { return connection.getContentType(); }
                    @Override public long getContentLengthLong() { return connection.getContentLengthLong(); }
                    @Override public InputStream getInputStream() { reads.incrementAndGet(); return new ByteArrayInputStream(new byte[0]); }
                    @Override public void disconnect() { connection.disconnect(); }
                    @Override public boolean usingProxy() { return false; }
                    @Override public void connect() {}
                };
            }
        };
        assertNull(client.preview("https://preview.test/video", new DiskClient.Cancellation()));
        assertNull(client.preview("https://preview.test/image", new DiskClient.Cancellation()));
        assertEquals(0, reads.get());
        assertNull(new DiskClient().preview("file:///sample", new DiskClient.Cancellation()));
    }

    @Test
    public void pathsCannotEscapeTheChosenFolder() {
        assertEquals("_", DiskClient.safeName(".."));
        assertEquals("C__outside", DiskClient.safeName("C:\\outside"));
        assertEquals("_CON.jpg", DiskClient.safeName("CON.jpg"));
        assertEquals("a_b.jpg", DiskClient.safeName("a/b.jpg"));
        assertTrue(DiskClient.safeName("a".repeat(300) + ".mp4").endsWith(".mp4"));
    }

    private static JSONObject resource(String name, String path, String mime) throws Exception {
        return new JSONObject().put("name", name).put("path", path).put("type", "file").put("mime_type", mime).put("size", PAYLOAD.length);
    }

    private static JSONObject directory(JSONArray items, int total) throws Exception {
        return new JSONObject().put("type", "dir").put("_embedded", new JSONObject().put("items", items).put("total", total));
    }

    private static MediaFile file() { return new MediaFile("a.jpg", "/a.jpg", Arrays.asList("a.jpg"), PAYLOAD.length, false, "", "image/jpeg"); }
    private static Reply response(String json) { return new Reply(200, json.getBytes(StandardCharsets.UTF_8), "application/json"); }

    private interface Route { Reply get(String url) throws Exception; }

    private static class Fake extends DiskClient {
        final Route route;
        final List<String> posts = java.util.Collections.synchronizedList(new ArrayList<>());
        final List<String> cookieHeaders = java.util.Collections.synchronizedList(new ArrayList<>());
        CyclicBarrier pageReady;
        Fake(Route route) { this.route = route; }
        @Override
        protected HttpURLConnection open(String url, Cancellation cancel) throws IOException {
            Reply reply;
            try { reply = route.get(url); }
            catch (Exception e) { throw new IOException(e); }
            HttpURLConnection connection = new HttpURLConnection(new URL(url)) {
                @Override public int getResponseCode() {
                    if (getURL().toString().endsWith("get-video-streams")) {
                        String cookie = getRequestProperty("Cookie");
                        cookieHeaders.add(cookie);
                        assertNotNull(cookie);
                        assertTrue(cookie.contains("i=anon-session-" + Thread.currentThread().getId()));
                    }
                    return reply.code;
                }
                @Override public String getContentType() { return reply.type; }
                @Override public long getContentLengthLong() { return reply.body.length; }
                @Override public String getHeaderField(String name) { return name.equals("Retry-After") ? "0" : null; }
                @Override public InputStream getInputStream() throws IOException {
                    if (getURL().toString().equals(LINK) && pageReady != null) {
                        try { pageReady.await(5, TimeUnit.SECONDS); }
                        catch (Exception e) { throw new IOException(e); }
                    }
                    return new ByteArrayInputStream(reply.body);
                }
                @Override public java.util.Map<String, List<String>> getHeaderFields() { return getURL().toString().equals(LINK)
                        ? java.util.Collections.singletonMap("Set-Cookie", Arrays.asList("i=anon-session-" + Thread.currentThread().getId() + "; Path=/; Secure")) : java.util.Collections.emptyMap(); }
                @Override public OutputStream getOutputStream() { return new ByteArrayOutputStream() { @Override public void close() { posts.add(new String(toByteArray(), StandardCharsets.UTF_8)); } }; }
                @Override public void disconnect() {}
                @Override public boolean usingProxy() { return false; }
                @Override public void connect() {}
            };
            cancel.track(connection);
            return connection;
        }
    }

    private static final class Reply {
        final int code;
        final byte[] body;
        final String type;
        Reply(int code, byte[] body, String type) { this.code = code; this.body = body; this.type = type; }
    }

    private static final class MemoryDestination implements DiskClient.Destination {
        ByteArrayOutputStream output;
        byte[] bytes;
        boolean completed;
        String name;
        String mime;
        @Override public OutputStream open() { output = new ByteArrayOutputStream(); return output; }
        @Override public OutputStream open(String mime) { this.mime = mime; return open(); }
        @Override public void complete() { bytes = output.toByteArray(); completed = true; }
        @Override public void complete(String name) { this.name = name; complete(); }
        @Override public void abort() { output = null; }
    }
}

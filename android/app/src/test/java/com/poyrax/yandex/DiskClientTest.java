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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

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
        Fake(Route route) { this.route = route; }
        @Override
        protected HttpURLConnection open(String url, Cancellation cancel) throws IOException {
            Reply reply;
            try { reply = route.get(url); }
            catch (Exception e) { throw new IOException(e); }
            HttpURLConnection connection = new HttpURLConnection(new URL(url)) {
                @Override public int getResponseCode() { return reply.code; }
                @Override public String getContentType() { return reply.type; }
                @Override public long getContentLengthLong() { return reply.body.length; }
                @Override public String getHeaderField(String name) { return name.equals("Retry-After") ? "0" : null; }
                @Override public InputStream getInputStream() { return new ByteArrayInputStream(reply.body); }
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
        @Override public OutputStream open() { output = new ByteArrayOutputStream(); return output; }
        @Override public void complete() { bytes = output.toByteArray(); completed = true; }
        @Override public void abort() { output = null; }
    }
}

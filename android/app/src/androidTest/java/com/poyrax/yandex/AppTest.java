package com.poyrax.yandex;

import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.TextView;

import androidx.documentfile.provider.DocumentFile;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class AppTest {
    private static final String LINK = "https://disk.yandex.com/d/test";
    private static final byte[] PAYLOAD = "android-media-fixture".getBytes(StandardCharsets.UTF_8);
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();

    @Test
    public void selectedDownloadAndDarkGalleryWork() throws Exception {
        Session session = launch(false, false);
        try {
            list(session.activity);
            until(() -> session.activity.downloadService != null && findText(session.activity, "3 dosya") != null);
            until(() -> children(session.activity.getWindow().getDecorView(), android.widget.ImageView.class).stream().anyMatch(view -> view.getDrawable() != null));
            instrumentation.runOnMainSync(() -> {
                assertNotNull(findText(session.activity, "Poyrax"));
                List<CheckBox> choices = children(session.activity.getWindow().getDecorView(), CheckBox.class);
                assertFalse(choices.isEmpty());
                choices.get(0).setChecked(true);
                Button selected = (Button) findText(session.activity, "Seçilenleri indir");
                assertTrue(selected.isEnabled());
                selected.performClick();
            });
            until(() -> findText(session.activity, "1 indirildi") != null);
            assertEquals(1, countFiles(session.folder));
            screenshot("android.png");
        } finally { close(session.activity); }
    }

    @Test
    public void allDownloadsPreserveFoldersAndIsolateFailures() throws Exception {
        Session session = launch(false, true);
        try {
            list(session.activity);
            until(() -> findText(session.activity, "4 dosya") != null);
            instrumentation.runOnMainSync(() -> ((Button) findText(session.activity, "Tümünü indir")).performClick());
            until(() -> findText(session.activity, "3 indirildi · 1 indirilemedi") != null);
            assertEquals(3, countFiles(session.folder));
            assertNotNull(session.folder.findFile("Alt"));
            assertNotNull(session.folder.findFile("Alt").findFile("Resim.png"));
            instrumentation.runOnMainSync(() -> ((Button) findText(session.activity, "Tümünü indir")).performClick());
            until(() -> findText(session.activity, "3 indirildi · 1 indirilemedi") != null && !session.activity.downloadService.running);
            assertEquals(6, countFiles(session.folder));
            assertNotNull(session.folder.findFile("Film (1).mp4"));
        } finally { close(session.activity); }
    }

    @Test
    public void cancelRemovesPartialFiles() throws Exception {
        Session session = launch(true, false);
        try {
            list(session.activity);
            until(() -> findText(session.activity, "3 dosya") != null);
            instrumentation.runOnMainSync(() -> ((Button) findText(session.activity, "Tümünü indir")).performClick());
            until(() -> session.activity.downloadService.running && countFiles(session.folder) > 0);
            instrumentation.runOnMainSync(() -> ((Button) findText(session.activity, "İptal")).performClick());
            until(() -> !session.activity.downloadService.running);
            assertEquals(0, countFiles(session.folder));
        } finally { close(session.activity); }
    }

    private Session launch(boolean slow, boolean blocked) throws Exception {
        Context target = instrumentation.getTargetContext();
        if (android.os.Build.VERSION.SDK_INT >= 33) instrumentation.getUiAutomation().grantRuntimePermission(target.getPackageName(), "android.permission.POST_NOTIFICATIONS");
        Intent intent = new Intent(target, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        MainActivity activity = (MainActivity) instrumentation.startActivitySync(intent);
        until(() -> activity.downloadService != null);
        Uri rootUri = DocumentsContract.buildTreeDocumentUri("com.poyrax.yandex.test.documents", "root");
        instrumentation.getContext().grantUriPermission(target.getPackageName(), rootUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        DocumentFile root = DocumentFile.fromTreeUri(target, rootUri);
        DocumentFile folder = root.createDirectory(UUID.randomUUID().toString());
        assertNotNull(folder);
        Uri folderUri = DocumentsContract.buildTreeDocumentUri("com.poyrax.yandex.test.documents", DocumentsContract.getDocumentId(folder.getUri()));
        instrumentation.getTargetContext().grantUriPermission(target.getPackageName(), folderUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        instrumentation.getTargetContext().grantUriPermission(target.getPackageName(), DocumentsContract.buildDocumentUriUsingTree(folderUri, DocumentsContract.getTreeDocumentId(folderUri)),
                Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        FakeClient client = new FakeClient(slow, blocked);
        instrumentation.runOnMainSync(() -> {
            activity.client = client;
            activity.downloadService.client = client;
            try {
                Field field = MainActivity.class.getDeclaredField("target");
                field.setAccessible(true);
                field.set(activity, folderUri);
                java.lang.reflect.Method showTarget = MainActivity.class.getDeclaredMethod("showTarget");
                showTarget.setAccessible(true);
                showTarget.invoke(activity);
            } catch (Exception e) { throw new RuntimeException(e); }
        });
        return new Session(activity, folder);
    }

    private void list(MainActivity activity) {
        instrumentation.runOnMainSync(() -> {
            children(activity.getWindow().getDecorView(), EditText.class).get(0).setText(LINK);
            ((Button) findText(activity, "Listele")).performClick();
        });
    }

    private void close(MainActivity activity) { instrumentation.runOnMainSync(activity::finish); instrumentation.waitForIdleSync(); }

    private void until(Condition condition) throws Exception {
        long deadline = System.currentTimeMillis() + 30000;
        while (System.currentTimeMillis() < deadline) {
            AtomicBoolean done = new AtomicBoolean();
            instrumentation.runOnMainSync(() -> done.set(condition.ready()));
            if (done.get()) return;
            Thread.sleep(40);
        }
        throw new AssertionError("İşlem zamanında tamamlanmadı");
    }

    private static View findText(MainActivity activity, String text) {
        for (TextView view : children(activity.getWindow().getDecorView(), TextView.class)) if (text.contentEquals(view.getText())) return view;
        return null;
    }

    private static <T> List<T> children(View parent, Class<T> type) {
        List<T> result = new ArrayList<>();
        if (type.isInstance(parent)) result.add(type.cast(parent));
        if (parent instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) parent;
            for (int i = 0; i < group.getChildCount(); i++) result.addAll(children(group.getChildAt(i), type));
        }
        return result;
    }

    private static int countFiles(DocumentFile folder) {
        int count = 0;
        for (DocumentFile file : folder.listFiles()) count += file.isDirectory() ? countFiles(file) : 1;
        return count;
    }

    private void screenshot(String name) throws IOException {
        File directory = new File(instrumentation.getTargetContext().getExternalFilesDir(null), "screenshots");
        directory.mkdirs();
        Bitmap bitmap = instrumentation.getUiAutomation().takeScreenshot();
        try (FileOutputStream output = new FileOutputStream(new File(directory, name))) { bitmap.compress(Bitmap.CompressFormat.PNG, 100, output); }
    }

    private interface Condition { boolean ready(); }
    private static final class Session {
        final MainActivity activity;
        final DocumentFile folder;
        Session(MainActivity activity, DocumentFile folder) { this.activity = activity; this.folder = folder; }
    }

    private static final class FakeClient extends DiskClient {
        final boolean slow;
        final boolean blocked;
        final byte[] preview = createPreview();
        FakeClient(boolean slow, boolean blocked) { this.slow = slow; this.blocked = blocked; }

        @Override
        protected HttpURLConnection open(String address, Cancellation cancel) throws IOException {
            byte[] body = PAYLOAD;
            int status = 200;
            boolean thumbnail = address.startsWith("https://preview.test");
            if (thumbnail) body = preview;
            else if (address.contains("/resources/download")) {
                String path = URLDecoder.decode(address.substring(address.indexOf("&path=") + 6), "UTF-8");
                if (blocked && path.equals("/Kapali.jpg")) status = 403;
                body = ("{\"href\":\"https://download.test/file\"}").getBytes(StandardCharsets.UTF_8);
            } else if (address.contains("/resources?")) {
                try {
                    JSONArray items = new JSONArray();
                    if (address.contains("&path=%2FAlt")) items.put(resource("Resim.png", "/Alt/Resim.png", "image/png"));
                    else {
                        items.put(resource("Film.mp4", "/Film.mp4", "video/mp4"));
                        items.put(resource("Foto.jpg", "/Foto.jpg", "image/jpeg"));
                        items.put(new JSONObject().put("name", "Alt").put("type", "dir").put("path", "/Alt"));
                        if (blocked) items.put(resource("Kapali.jpg", "/Kapali.jpg", "image/jpeg"));
                    }
                    body = new JSONObject().put("type", "dir").put("_embedded", new JSONObject().put("items", items).put("total", items.length())).toString().getBytes(StandardCharsets.UTF_8);
                } catch (Exception e) { throw new IOException(e); }
            }
            byte[] reply = body;
            int code = status;
            boolean transfer = address.startsWith("https://download.test");
            HttpURLConnection connection = new HttpURLConnection(new URL(address)) {
                volatile boolean disconnected;
                @Override public int getResponseCode() { return code; }
                @Override public String getContentType() { return thumbnail ? "image/png" : "application/octet-stream"; }
                @Override public long getContentLengthLong() { return slow && transfer ? 16 * 1024 * 1024 : reply.length; }
                @Override public InputStream getInputStream() {
                    if (!slow || !transfer) return new ByteArrayInputStream(reply);
                    return new InputStream() {
                        int position;
                        @Override public int read() { return -1; }
                        @Override public int read(byte[] buffer, int offset, int count) throws IOException {
                            try { Thread.sleep(50); } catch (InterruptedException e) { throw new IOException(); }
                            if (disconnected) throw new IOException();
                            if (position >= 16 * 1024 * 1024) return -1;
                            int length = Math.min(count, 16 * 1024 * 1024 - position);
                            java.util.Arrays.fill(buffer, offset, offset + length, (byte) 77);
                            position += length;
                            return length;
                        }
                    };
                }
                @Override public void disconnect() { disconnected = true; }
                @Override public boolean usingProxy() { return false; }
                @Override public void connect() {}
            };
            cancel.track(connection);
            return connection;
        }

        private JSONObject resource(String name, String path, String mime) throws Exception {
            JSONObject item = new JSONObject().put("name", name).put("path", path).put("type", "file").put("mime_type", mime)
                    .put("size", slow ? 16 * 1024 * 1024 : PAYLOAD.length);
            if (mime.startsWith("image/")) item.put("preview", "https://preview.test/image");
            return item;
        }

        private static byte[] createPreview() {
            Bitmap bitmap = Bitmap.createBitmap(320, 200, Bitmap.Config.ARGB_8888);
            android.graphics.Canvas canvas = new android.graphics.Canvas(bitmap);
            android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
            paint.setShader(new android.graphics.LinearGradient(0, 0, 320, 200, 0xFFB1D3E4, 0xFF489CB0, android.graphics.Shader.TileMode.CLAMP));
            canvas.drawRect(0, 0, 320, 200, paint);
            paint.setShader(null);
            paint.setColor(0xFFFFD47C);
            canvas.drawCircle(245, 48, 19, paint);
            paint.setColor(0xFF2D6174);
            android.graphics.Path mountains = new android.graphics.Path();
            mountains.moveTo(0, 200);
            mountains.lineTo(95, 60);
            mountains.lineTo(205, 200);
            mountains.lineTo(225, 90);
            mountains.lineTo(320, 200);
            mountains.close();
            canvas.drawPath(mountains, paint);
            java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output);
            bitmap.recycle();
            return output.toByteArray();
        }
    }
}

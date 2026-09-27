package com.poyrax.yandex;

import android.os.Bundle;
import android.media.MediaExtractor;
import android.media.MediaFormat;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;
import java.util.Arrays;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class ApiTest {
    @Test
    public void publicShareWorks() throws Exception {
        Bundle arguments = InstrumentationRegistry.getArguments();
        String link = arguments.getString("publicLink");
        Assume.assumeTrue(link != null && !link.isEmpty());
        boolean playback = "true".equals(arguments.getString("publicVideo"));
        DiskClient client = playback ? new PlaybackClient() : new DiskClient();
        String path = arguments.getString("publicPath");
        if (path == null) {
            List<MediaFile> files = client.list(link, new DiskClient.Cancellation(), null);
            assertFalse(files.isEmpty());
            System.out.println("API media count: " + files.size());
            return;
        }
        File output = File.createTempFile("download-", ".part", InstrumentationRegistry.getInstrumentation().getTargetContext().getCacheDir());
        try {
            String name = playback ? "test.mp4" : "test.jpg";
            MediaFile file = new MediaFile(name, path, Arrays.asList(name), 0, playback, "", playback ? "video/mp4" : "image/jpeg");
            client.download(link, file, new DiskClient.Destination() {
                @Override public OutputStream open() throws IOException { return new FileOutputStream(output); }
                @Override public void complete() { assertTrue(output.length() > 0); }
                @Override public void complete(String name) { assertTrue(name.endsWith(".ts")); complete(); }
                @Override public void abort() { output.delete(); }
            }, new DiskClient.Cancellation(), (bytes, total) -> {});
            System.out.println("API downloaded bytes: " + output.length());
            if (playback) {
                MediaExtractor extractor = new MediaExtractor();
                try {
                    extractor.setDataSource(output.getAbsolutePath());
                    boolean video = false;
                    boolean audio = false;
                    for (int track = 0; track < extractor.getTrackCount(); track++) {
                        MediaFormat format = extractor.getTrackFormat(track);
                        String mime = format.getString(MediaFormat.KEY_MIME);
                        if (mime != null && mime.startsWith("video/")) {
                            video = true;
                            String height = arguments.getString("publicHeight");
                            if (height != null) assertEquals(Integer.parseInt(height), format.getInteger(MediaFormat.KEY_HEIGHT));
                        }
                        if (mime != null && mime.startsWith("audio/")) audio = true;
                        extractor.selectTrack(track);
                    }
                    assertTrue(video);
                    if ("true".equals(arguments.getString("publicAudio"))) assertTrue(audio);
                    long lastTime = -1;
                    int samples = 0;
                    do {
                        lastTime = Math.max(lastTime, extractor.getSampleTime());
                        samples++;
                    } while (extractor.advance());
                    assertTrue(samples > 1);
                    assertTrue(lastTime > 0);
                    System.out.println("API video samples: " + samples + ", last time: " + lastTime);
                } finally { extractor.release(); }
            }
        } finally { output.delete(); }
    }

    private static final class PlaybackClient extends DiskClient {
        @Override
        protected HttpURLConnection open(String address, Cancellation cancel) throws IOException {
            if (!address.startsWith("https://cloud-api.yandex.net/")) return super.open(address, cancel);
            byte[] body = "{\"href\":\"\"}".getBytes(StandardCharsets.UTF_8);
            HttpURLConnection connection = new HttpURLConnection(new URL(address)) {
                @Override public int getResponseCode() { return 200; }
                @Override public InputStream getInputStream() { return new ByteArrayInputStream(body); }
                @Override public void disconnect() {}
                @Override public boolean usingProxy() { return false; }
                @Override public void connect() {}
            };
            cancel.track(connection);
            return connection;
        }
    }
}

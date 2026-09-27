package com.poyrax.yandex;

import android.os.Bundle;

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

import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class ApiTest {
    @Test
    public void publicShareWorks() throws Exception {
        Bundle arguments = InstrumentationRegistry.getArguments();
        String link = arguments.getString("publicLink");
        Assume.assumeTrue(link != null && !link.isEmpty());
        DiskClient client = new DiskClient();
        String path = arguments.getString("publicPath");
        if (path == null) {
            List<MediaFile> files = client.list(link, new DiskClient.Cancellation(), null);
            assertFalse(files.isEmpty());
            System.out.println("API media count: " + files.size());
            return;
        }
        File output = File.createTempFile("download-", ".part", InstrumentationRegistry.getInstrumentation().getTargetContext().getCacheDir());
        try {
            MediaFile file = new MediaFile("test.jpg", path, Arrays.asList("test.jpg"), 0, false, "", "image/jpeg");
            client.download(link, file, new DiskClient.Destination() {
                @Override public OutputStream open() throws IOException { return new FileOutputStream(output); }
                @Override public void complete() { assertTrue(output.length() > 0); }
                @Override public void abort() { output.delete(); }
            }, new DiskClient.Cancellation(), (bytes, total) -> {});
            System.out.println("API downloaded bytes: " + output.length());
        } finally { output.delete(); }
    }
}

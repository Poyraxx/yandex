package com.poyrax.yandex;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class MediaFile {
    public final String name;
    public final String path;
    public final List<String> segments;
    public final long size;
    public final boolean video;
    public final String preview;
    public final String mime;
    public boolean selected;
    public volatile String state = "";
    public volatile String error;
    public volatile long bytes;
    public volatile long total;

    public MediaFile(String name, String path, List<String> segments, long size, boolean video, String preview, String mime) {
        this.name = name;
        this.path = path;
        this.segments = new ArrayList<>(segments);
        this.size = size;
        this.total = size;
        this.video = video;
        this.preview = preview;
        this.mime = mime;
    }

    public String folder() {
        return String.join(" / ", segments.subList(0, segments.size() - 1));
    }

    public static String formatSize(long bytes) {
        String[] units = {"B", "KB", "MB", "GB", "TB"};
        double value = Math.max(0, bytes);
        int unit = 0;
        while (value >= 1024 && unit < units.length - 1) {
            value /= 1024;
            unit++;
        }
        return String.format(Locale.forLanguageTag("tr-TR"), value < 10 && unit > 0 ? "%.1f %s" : "%.0f %s", value, units[unit]);
    }
}

namespace Yandex;

public sealed record MediaFile(string Name, string Path, string[] Segments, long Size, bool IsVideo, string? PreviewUrl)
{
    public string Folder => string.Join(" / ", Segments.SkipLast(1));
    public string SizeText => FormatSize(Size);

    public static string FormatSize(long size)
    {
        string[] units = ["B", "KB", "MB", "GB", "TB"];
        double value = Math.Max(0, size);
        int unit = 0;
        while (value >= 1024 && unit < units.Length - 1)
        {
            value /= 1024;
            unit++;
        }
        return $"{value:0.#} {units[unit]}";
    }
}

public enum TransferState
{
    Waiting,
    Downloading,
    Completed,
    Failed,
    Cancelled
}

public sealed record DownloadUpdate(MediaFile File, TransferState State, long Bytes, long Total, string? Error = null);
public sealed record DownloadResult(int Completed, int Failed, int Cancelled, IReadOnlyList<DownloadUpdate> Updates);

public sealed class DiskException(string message) : Exception(message);

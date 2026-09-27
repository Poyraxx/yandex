using System.ComponentModel;
using System.Runtime.CompilerServices;
using System.Windows.Media.Imaging;

namespace Yandex;

public sealed class FileItem(MediaFile file) : INotifyPropertyChanged
{
    private bool selected;
    private BitmapSource? thumbnail;
    private TransferState state;
    private long bytes;
    private long total = file.Size;
    private string? error;

    public MediaFile File { get; } = file;
    public string Name => File.Name;
    public string Folder => File.Folder;
    public string SizeText => File.SizeText;
    public string Icon => File.IsVideo ? "\uE714" : "\uEB9F";
    public bool PreviewRequested { get; set; }
    public bool IsSelected
    {
        get => selected;
        set { selected = value; Changed(); }
    }
    public BitmapSource? Thumbnail
    {
        get => thumbnail;
        set { thumbnail = value; Changed(); }
    }
    public TransferState State => state;
    public long Bytes => bytes;
    public long Total => total;
    public double Percent => total > 0 ? Math.Clamp(100.0 * bytes / total, 0, 100) : 0;
    public bool IsIndeterminate => state == TransferState.Downloading && total <= 0;
    public string Status => state switch
    {
        TransferState.Downloading => total > 0 ? $"%{Percent:0}" : MediaFile.FormatSize(bytes),
        TransferState.Completed => "İndirildi",
        TransferState.Failed => error ?? "İndirilemedi",
        TransferState.Cancelled => "İptal edildi",
        _ => ""
    };

    public void Apply(DownloadUpdate update)
    {
        state = update.State;
        bytes = update.Bytes;
        total = update.Total;
        error = update.Error;
        Changed(nameof(State));
        Changed(nameof(Percent));
        Changed(nameof(IsIndeterminate));
        Changed(nameof(Status));
    }

    public event PropertyChangedEventHandler? PropertyChanged;

    private void Changed([CallerMemberName] string? name = null) =>
        PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(name));
}

public sealed record FileRow(IReadOnlyList<FileItem> Items);

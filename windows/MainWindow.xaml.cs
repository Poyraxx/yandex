using System.ComponentModel;
using System.Globalization;
using System.IO;
using System.Runtime.InteropServices;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Interop;
using System.Windows.Media.Imaging;
using Microsoft.Win32;

namespace Yandex;

public partial class MainWindow : Window
{
    private readonly DiskClient client;
    private readonly SemaphoreSlim previewSlots = new(4);
    private readonly List<FileItem> items = [];
    private CancellationTokenSource previewCancellation = new();
    private CancellationTokenSource? operationCancellation;
    private Task? operation;
    private string? loadedLink;
    private string? targetFolder;
    private bool busy;
    private bool transition;
    private bool closeRequested;
    private bool allowClose;
    private int columns;

    public MainWindow() : this(new DiskClient())
    {
    }

    public MainWindow(DiskClient client)
    {
        this.client = client;
        CultureInfo.CurrentCulture = CultureInfo.GetCultureInfo("tr-TR");
        CultureInfo.CurrentUICulture = CultureInfo.GetCultureInfo("tr-TR");
        InitializeComponent();
        LinkBox.Focus();
    }

    protected override void OnSourceInitialized(EventArgs e)
    {
        base.OnSourceInitialized(e);
        int enabled = 1;
        DwmSetWindowAttribute(new WindowInteropHelper(this).Handle, 20, ref enabled, sizeof(int));
    }

    [DllImport("dwmapi.dll")]
    private static extern int DwmSetWindowAttribute(IntPtr window, int attribute, ref int value, int size);

    private async void ListButton_Click(object sender, RoutedEventArgs e) => await ListAsync();

    private async void LinkBox_KeyDown(object sender, KeyEventArgs e)
    {
        if (e.Key == Key.Enter)
        {
            e.Handled = true;
            await ListAsync();
        }
    }

    private async Task ListAsync()
    {
        if (transition || closeRequested)
            return;
        transition = true;
        ListButton.IsEnabled = false;
        UpdateButtons();
        await CancelCurrentAsync();
        previewCancellation.Cancel();
        previewCancellation.Dispose();
        previewCancellation = new CancellationTokenSource();
        operationCancellation?.Dispose();
        operationCancellation = new CancellationTokenSource();
        items.Clear();
        loadedLink = null;
        columns = 0;
        RebuildRows();
        TotalProgress.Visibility = Visibility.Collapsed;
        try
        {
            string link = DiskClient.ValidateLink(LinkBox.Text);
            LinkBox.Text = link;
            SetBusy(true);
            StatusText.Text = "Listeleniyor…";
            TotalProgress.IsIndeterminate = true;
            TotalProgress.Visibility = Visibility.Visible;
            operation = LoadFilesAsync(link, operationCancellation.Token);
        }
        catch (DiskException ex)
        {
            StatusText.Text = ex.Message;
            SetBusy(false);
            operation = Task.CompletedTask;
        }
        finally
        {
            transition = false;
            ListButton.IsEnabled = true;
            UpdateButtons();
        }
        await operation;
    }

    private async Task LoadFilesAsync(string link, CancellationToken token)
    {
        try
        {
            var progress = new Progress<int>(count =>
            {
                if (!token.IsCancellationRequested && busy && loadedLink is null)
                    StatusText.Text = $"Listeleniyor… {count} dosya";
            });
            var files = await client.ListAsync(link, progress, token);
            token.ThrowIfCancellationRequested();
            items.AddRange(files.Select(f => new FileItem(f)));
            loadedLink = link;
            RebuildRows();
            StatusText.Text = files.Count == 0 ? "Video veya görsel bulunamadı." : $"{files.Count} dosya";
        }
        catch (OperationCanceledException) when (token.IsCancellationRequested)
        {
            StatusText.Text = "İptal edildi.";
        }
        catch (Exception ex) when (ex is DiskException or IOException or System.Net.Http.HttpRequestException)
        {
            StatusText.Text = DiskClient.ErrorMessage(ex);
        }
        finally
        {
            SetBusy(false);
            TotalProgress.Visibility = Visibility.Collapsed;
            TotalProgress.IsIndeterminate = false;
        }
    }

    private void FolderButton_Click(object sender, RoutedEventArgs e) => ChooseFolder();

    private bool ChooseFolder()
    {
        var dialog = new OpenFolderDialog();
        if (targetFolder is not null)
            dialog.InitialDirectory = targetFolder;
        if (dialog.ShowDialog(this) != true)
            return false;
        targetFolder = dialog.FolderName;
        FolderText.Text = targetFolder;
        return true;
    }

    private async void SelectedButton_Click(object sender, RoutedEventArgs e) =>
        await StartDownloadAsync(items.Where(i => i.IsSelected).ToArray());

    private async void AllButton_Click(object sender, RoutedEventArgs e) => await StartDownloadAsync(items.ToArray());

    private async Task StartDownloadAsync(IReadOnlyList<FileItem> selected)
    {
        if (busy || transition || closeRequested || loadedLink is null || selected.Count == 0)
            return;
        if (targetFolder is null && !ChooseFolder())
            return;
        operationCancellation?.Dispose();
        operationCancellation = new CancellationTokenSource();
        SetBusy(true);
        operation = DownloadFilesAsync(loadedLink, selected, targetFolder!, operationCancellation.Token);
        await operation;
    }

    private async Task DownloadFilesAsync(string link, IReadOnlyList<FileItem> selected, string folder, CancellationToken token)
    {
        TotalProgress.IsIndeterminate = false;
        TotalProgress.Value = 0;
        TotalProgress.Visibility = Visibility.Visible;
        var lookup = selected.ToDictionary(i => i.File);
        foreach (var item in selected)
            item.Apply(new(item.File, TransferState.Waiting, 0, item.File.Size));
        StatusText.Text = $"İndiriliyor… 0 / {selected.Count}";
        try
        {
            var progress = new Progress<DownloadUpdate>(update =>
            {
                if (token.IsCancellationRequested || !busy)
                    return;
                lookup[update.File].Apply(update);
                int finished = selected.Count(i => i.State is TransferState.Completed or TransferState.Failed);
                double totalWeight = selected.Sum(i => Math.Max(1.0, i.Total));
                double transferred = selected.Sum(i => i.State is TransferState.Completed or TransferState.Failed
                    ? Math.Max(1.0, i.Total) : Math.Min(i.Bytes, Math.Max(1.0, i.Total)));
                TotalProgress.Value = totalWeight > 0 ? Math.Clamp(transferred * 100 / totalWeight, 0, 100) : 0;
                StatusText.Text = $"İndiriliyor… {finished} / {selected.Count} · %{TotalProgress.Value:0}";
            });
            var result = await client.DownloadAsync(link, selected.Select(i => i.File).ToArray(), folder, progress, token);
            foreach (var update in result.Updates)
                lookup[update.File].Apply(update);
            if (result.Cancelled > 0 || token.IsCancellationRequested)
            {
                foreach (var item in selected.Where(i => i.State is TransferState.Waiting or TransferState.Downloading))
                    item.Apply(new(item.File, TransferState.Cancelled, 0, item.Total));
                StatusText.Text = $"İptal edildi. {result.Completed} indirildi" + (result.Failed > 0 ? $" · {result.Failed} indirilemedi" : "");
            }
            else
            {
                TotalProgress.Value = 100;
                StatusText.Text = $"{result.Completed} indirildi" + (result.Failed > 0 ? $" · {result.Failed} indirilemedi" : "");
            }
        }
        catch (Exception ex) when (ex is DiskException or IOException or UnauthorizedAccessException)
        {
            StatusText.Text = DiskClient.ErrorMessage(ex);
        }
        finally
        {
            SetBusy(false);
        }
    }

    private void CancelButton_Click(object sender, RoutedEventArgs e)
    {
        operationCancellation?.Cancel();
        CancelButton.IsEnabled = false;
    }

    private async Task CancelCurrentAsync()
    {
        operationCancellation?.Cancel();
        if (operation is not null)
            await operation;
    }

    private void SetBusy(bool value)
    {
        busy = value;
        CancelButton.Visibility = value ? Visibility.Visible : Visibility.Collapsed;
        CancelButton.IsEnabled = value;
        UpdateButtons();
    }

    private void UpdateButtons()
    {
        if (!IsInitialized)
            return;
        bool available = !busy && !transition && !closeRequested && loadedLink is not null && items.Count > 0;
        AllButton.IsEnabled = available;
        SelectedButton.IsEnabled = available && items.Any(i => i.IsSelected);
        FolderButton.IsEnabled = !busy && !transition && !closeRequested;
    }

    private void Selection_Changed(object sender, RoutedEventArgs e) => UpdateButtons();

    private void Gallery_SizeChanged(object sender, SizeChangedEventArgs e) => RebuildRows();

    private void RebuildRows()
    {
        if (Gallery is null)
            return;
        int nextColumns = Math.Max(1, (int)Math.Max(0, Gallery.ActualWidth - 20) / 246);
        if (columns == nextColumns && Gallery.Items.Count > 0)
            return;
        columns = nextColumns;
        Gallery.ItemsSource = items.Chunk(columns).Select(row => new FileRow(row)).ToArray();
    }

    private async void Card_Loaded(object sender, RoutedEventArgs e)
    {
        if (sender is FrameworkElement { DataContext: FileItem item })
            await LoadPreviewAsync(item);
    }

    private async void Card_DataContextChanged(object sender, DependencyPropertyChangedEventArgs e)
    {
        if (sender is FrameworkElement { IsLoaded: true } && e.NewValue is FileItem item)
            await LoadPreviewAsync(item);
    }

    private async Task LoadPreviewAsync(FileItem item)
    {
        if (item.PreviewRequested || item.File.PreviewUrl is null)
            return;
        item.PreviewRequested = true;
        CancellationToken token = previewCancellation.Token;
        bool acquired = false;
        try
        {
            await previewSlots.WaitAsync(token);
            acquired = true;
            if (!items.Contains(item) || token.IsCancellationRequested)
                return;
            byte[]? data = await client.GetPreviewAsync(item.File.PreviewUrl, token);
            if (data is null || token.IsCancellationRequested)
                return;
            var bitmap = await Task.Run(() =>
            {
                using var stream = new MemoryStream(data);
                var image = new BitmapImage();
                image.BeginInit();
                image.CacheOption = BitmapCacheOption.OnLoad;
                image.DecodePixelWidth = 424;
                image.StreamSource = stream;
                image.EndInit();
                image.Freeze();
                return image;
            }, token);
            if (!token.IsCancellationRequested)
                item.Thumbnail = bitmap;
        }
        catch (Exception ex) when (ex is OperationCanceledException or IOException or FormatException or InvalidOperationException or NotSupportedException or ArgumentException or System.Runtime.InteropServices.COMException)
        {
        }
        finally
        {
            if (acquired)
                previewSlots.Release();
        }
    }

    private async void Window_Closing(object? sender, CancelEventArgs e)
    {
        if (allowClose)
            return;
        e.Cancel = true;
        if (closeRequested)
            return;
        closeRequested = true;
        ListButton.IsEnabled = false;
        UpdateButtons();
        previewCancellation.Cancel();
        await CancelCurrentAsync();
        operationCancellation?.Dispose();
        allowClose = true;
        _ = Dispatcher.BeginInvoke(new Action(Close));
    }
}

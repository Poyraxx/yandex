using System.Buffers;
using System.Collections.Concurrent;
using System.Diagnostics;
using System.IO;
using System.Net;
using System.Net.Http;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Text.RegularExpressions;

namespace Yandex;

public sealed class DiskClient : IDisposable
{
    private const string Api = "https://cloud-api.yandex.net/v1/disk/public/resources";
    private readonly HttpClient http;
    private readonly bool ownsHttp;
    private readonly TimeSpan retryDelay;
    private readonly TimeSpan idleTimeout;
    private static readonly HashSet<string> ImageExtensions = new(StringComparer.OrdinalIgnoreCase)
    {
        ".jpg", ".jpeg", ".png", ".gif", ".webp", ".bmp", ".tif", ".tiff", ".svg", ".avif", ".heic", ".heif", ".ico", ".jxl"
    };
    private static readonly HashSet<string> VideoExtensions = new(StringComparer.OrdinalIgnoreCase)
    {
        ".mp4", ".mov", ".mkv", ".avi", ".webm", ".m4v", ".wmv", ".mpeg", ".mpg", ".3gp", ".mts", ".m2ts", ".ts", ".vob", ".ogv", ".flv"
    };

    public DiskClient(HttpClient? http = null, TimeSpan? retryDelay = null, TimeSpan? idleTimeout = null)
    {
        ownsHttp = http is null;
        this.http = http ?? new HttpClient(new SocketsHttpHandler
        {
            PooledConnectionLifetime = TimeSpan.FromMinutes(5),
            ConnectTimeout = TimeSpan.FromSeconds(20)
        }) { Timeout = Timeout.InfiniteTimeSpan };
        this.retryDelay = retryDelay ?? TimeSpan.FromSeconds(1);
        this.idleTimeout = idleTimeout ?? TimeSpan.FromSeconds(45);
    }

    public static string ValidateLink(string value)
    {
        if (!Uri.TryCreate(value.Trim(), UriKind.Absolute, out var uri) ||
            uri.Scheme != Uri.UriSchemeHttps || !string.IsNullOrEmpty(uri.UserInfo) || !uri.IsDefaultPort)
            throw new DiskException("Geçerli bir Yandex Disk bağlantısı girin.");
        string[] hosts = ["disk.yandex.ru", "disk.yandex.com", "disk.yandex.com.tr", "disk.yandex.kz", "disk.yandex.by", "disk.yandex.uz", "yadi.sk"];
        var segments = uri.AbsolutePath.Split('/', StringSplitOptions.RemoveEmptyEntries);
        if (!hosts.Contains(uri.IdnHost, StringComparer.OrdinalIgnoreCase) || segments.Length < 2 ||
            segments[0] is not ("d" or "i"))
            throw new DiskException("Geçerli bir Yandex Disk bağlantısı girin.");
        return uri.GetLeftPart(UriPartial.Path);
    }

    public async Task<IReadOnlyList<MediaFile>> ListAsync(string link, IProgress<int>? progress, CancellationToken token)
    {
        link = ValidateLink(link);
        var files = new List<MediaFile>();
        var seen = new HashSet<string>(StringComparer.Ordinal);
        var directories = new Queue<(string Path, string[] Segments)>();
        using var root = await GetJsonAsync(ResourceUrl(link, null, 0), token).ConfigureAwait(false);
        var resource = root.RootElement;
        if (GetString(resource, "type") == "file")
        {
            AddFile(resource, [RequiredString(resource, "name")], "/");
            return files;
        }
        if (GetString(resource, "type") != "dir")
            throw new DiskException("Dosya listesi alınamadı.");

        seen.Add("/");
        ReadPage(resource, []);
        await ReadRemainingAsync(resource, "/", []).ConfigureAwait(false);
        while (directories.TryDequeue(out var directory))
        {
            token.ThrowIfCancellationRequested();
            using var page = await GetJsonAsync(ResourceUrl(link, directory.Path, 0), token).ConfigureAwait(false);
            if (GetString(page.RootElement, "type") != "dir")
                throw new DiskException("Klasörün tamamı listelenemedi.");
            ReadPage(page.RootElement, directory.Segments);
            await ReadRemainingAsync(page.RootElement, directory.Path, directory.Segments).ConfigureAwait(false);
        }
        return files.OrderBy(f => string.Join('/', f.Segments), StringComparer.OrdinalIgnoreCase).ToArray();

        void AddFile(JsonElement item, string[] segments, string fallbackPath)
        {
            string name = RequiredString(item, "name");
            string mime = GetString(item, "mime_type") ?? "";
            string mediaType = GetString(item, "media_type") ?? "";
            string extension = System.IO.Path.GetExtension(name);
            bool video = mime.StartsWith("video/", StringComparison.OrdinalIgnoreCase);
            bool image = mime.StartsWith("image/", StringComparison.OrdinalIgnoreCase);
            if (string.IsNullOrWhiteSpace(mime) || mime.Equals("application/octet-stream", StringComparison.OrdinalIgnoreCase))
            {
                video = mediaType == "video" || VideoExtensions.Contains(extension);
                image = mediaType == "image" || ImageExtensions.Contains(extension);
            }
            if (!video && !image)
                return;
            long size = item.TryGetProperty("size", out var sizeProperty) && sizeProperty.TryGetInt64(out var bytes) ? Math.Max(0, bytes) : 0;
            files.Add(new MediaFile(name, GetString(item, "path") ?? fallbackPath, segments, size, video, GetString(item, "preview")));
            progress?.Report(files.Count);
        }

        int ReadPage(JsonElement page, string[] parentSegments)
        {
            token.ThrowIfCancellationRequested();
            if (page.ValueKind != JsonValueKind.Object || !page.TryGetProperty("_embedded", out var embedded) ||
                embedded.ValueKind != JsonValueKind.Object || !embedded.TryGetProperty("items", out var items) || items.ValueKind != JsonValueKind.Array)
                throw new DiskException("Klasörün tamamı listelenemedi.");
            foreach (var item in items.EnumerateArray())
            {
                token.ThrowIfCancellationRequested();
                string name = RequiredString(item, "name");
                string[] segments = [.. parentSegments, name];
                string path = GetString(item, "path") ?? "/" + string.Join('/', segments);
                if (!seen.Add(path))
                    throw new DiskException("Dosya listesi tutarsız. Yeniden deneyin.");
                switch (GetString(item, "type"))
                {
                    case "dir":
                        directories.Enqueue((path, segments));
                        break;
                    case "file":
                        AddFile(item, segments, path);
                        break;
                    default:
                        throw new DiskException("Dosya listesi alınamadı.");
                }
            }
            return items.GetArrayLength();
        }

        async Task ReadRemainingAsync(JsonElement firstPage, string path, string[] segments)
        {
            var embedded = firstPage.GetProperty("_embedded");
            int offset = embedded.GetProperty("items").GetArrayLength();
            long? total = embedded.TryGetProperty("total", out var value) && value.TryGetInt64(out var count) ? count : null;
            bool hasMore = total is not null ? offset < total : offset >= 100;
            while (hasMore)
            {
                token.ThrowIfCancellationRequested();
                using var next = await GetJsonAsync(ResourceUrl(link, path, offset), token).ConfigureAwait(false);
                int received = ReadPage(next.RootElement, segments);
                if (received == 0)
                {
                    if (total is not null && offset < total)
                        throw new DiskException("Klasörün tamamı listelenemedi.");
                    break;
                }
                offset += received;
                hasMore = total is not null ? offset < total : received >= 100;
            }
        }
    }

    public async Task<DownloadResult> DownloadAsync(string link, IReadOnlyList<MediaFile> files, string target,
        IProgress<DownloadUpdate>? progress, CancellationToken token)
    {
        link = ValidateLink(link);
        target = System.IO.Path.GetFullPath(target);
        if (!Directory.Exists(target))
            throw new DiskException("İndirme klasörü bulunamadı.");
        var folders = BuildFolders(files, target);
        int completed = 0;
        int failed = 0;
        int cancelled = 0;
        var outcomes = new ConcurrentDictionary<MediaFile, DownloadUpdate>();
        var updates = new InlineProgress<DownloadUpdate>(update =>
        {
            if (update.State is TransferState.Completed or TransferState.Failed or TransferState.Cancelled)
                outcomes[update.File] = update;
            progress?.Report(update);
        });
        await Parallel.ForEachAsync(files, new ParallelOptions { MaxDegreeOfParallelism = 2 }, async (file, _) =>
        {
            if (token.IsCancellationRequested)
            {
                Interlocked.Increment(ref cancelled);
                updates.Report(new(file, TransferState.Cancelled, 0, file.Size));
                return;
            }
            try
            {
                string folder = folders[FolderKey(file.Segments.SkipLast(1))];
                EnsureFolder(target, folder);
                await DownloadFileAsync(link, file, target, folder, updates, token).ConfigureAwait(false);
                Interlocked.Increment(ref completed);
            }
            catch (OperationCanceledException) when (token.IsCancellationRequested)
            {
                Interlocked.Increment(ref cancelled);
                updates.Report(new(file, TransferState.Cancelled, 0, file.Size));
            }
            catch (Exception ex) when (ex is DiskException or HttpRequestException or IOException or UnauthorizedAccessException or OperationCanceledException)
            {
                Interlocked.Increment(ref failed);
                updates.Report(new(file, TransferState.Failed, 0, file.Size, ErrorMessage(ex)));
            }
        }).ConfigureAwait(false);
        return new(completed, failed, cancelled, outcomes.Values.ToArray());
    }

    private async Task DownloadFileAsync(string link, MediaFile file, string target, string folder, IProgress<DownloadUpdate>? progress, CancellationToken token)
    {
        for (int attempt = 0; attempt < 3; attempt++)
        {
            string temporary = System.IO.Path.Combine(folder, $".{Guid.NewGuid():N}.part");
            try
            {
                token.ThrowIfCancellationRequested();
                progress?.Report(new(file, TransferState.Downloading, 0, file.Size));
                using var address = await GetJsonAsync($"{Api}/download?public_key={Uri.EscapeDataString(link)}&path={Uri.EscapeDataString(file.Path)}", token).ConfigureAwait(false);
                string? href = GetString(address.RootElement, "href");
                string? expectedHash = null;
                long? originalSize = null;
                if (string.IsNullOrWhiteSpace(href))
                {
                    using var metadata = await GetJsonAsync(ResourceUrl(link, file.Path, 0), token).ConfigureAwait(false);
                    var resource = metadata.RootElement;
                    string? hash = GetString(resource, "sha256");
                    if (GetString(resource, "type") == "file" && hash is { Length: 64 } && hash.All(Uri.IsHexDigit) &&
                        resource.TryGetProperty("size", out var size) && size.TryGetInt64(out long length) && length >= 0)
                    {
                        href = GetString(resource, "file");
                        if (string.IsNullOrWhiteSpace(href) && !file.IsVideo &&
                            resource.TryGetProperty("sizes", out var sizes) && sizes.ValueKind == JsonValueKind.Array)
                            href = sizes.EnumerateArray().Where(item => GetString(item, "name") == "ORIGINAL")
                                .Select(item => GetString(item, "url")).FirstOrDefault(url => !string.IsNullOrWhiteSpace(url));
                        expectedHash = hash;
                        originalSize = length;
                    }
                    if (string.IsNullOrWhiteSpace(href))
                    {
                        if (!file.IsVideo)
                            throw new DiskException("İndirme adresi verilmedi. Paylaşımın indirme iznini kontrol edin.");
                        long videoBytes = await DownloadVideoAsync(link, file, temporary, progress, token).ConfigureAwait(false);
                        token.ThrowIfCancellationRequested();
                        EnsureFolder(target, folder);
                        MoveToUniqueName(temporary, folder, System.IO.Path.ChangeExtension(SafeName(file.Name), ".ts"));
                        progress?.Report(new(file, TransferState.Completed, videoBytes, videoBytes));
                        return;
                    }
                }
                ValidateHttps(href);
                using var digest = expectedHash is null ? null : IncrementalHash.CreateHash(HashAlgorithmName.SHA256);
                using var response = await SendAsync(href, token).ConfigureAwait(false);
                if (IsTransient(response.StatusCode) || response.StatusCode is HttpStatusCode.Forbidden or HttpStatusCode.Unauthorized)
                    throw new TransferException(RetryAfter(response));
                ThrowIfError(response.StatusCode);
                long? contentLength = response.Content.Headers.ContentLength;
                long expected = contentLength ?? file.Size;
                long bytes = 0;
                await using (var source = await response.Content.ReadAsStreamAsync(token).ConfigureAwait(false))
                await using (var destination = new FileStream(temporary, FileMode.CreateNew, FileAccess.Write, FileShare.None, 131072, FileOptions.Asynchronous | FileOptions.SequentialScan))
                {
                    byte[] buffer = ArrayPool<byte>.Shared.Rent(131072);
                    var timer = Stopwatch.StartNew();
                    try
                    {
                        while (true)
                        {
                            int length;
                            using (var idle = CancellationTokenSource.CreateLinkedTokenSource(token))
                            {
                                idle.CancelAfter(idleTimeout);
                                try
                                {
                                    length = await source.ReadAsync(buffer.AsMemory(), idle.Token).ConfigureAwait(false);
                                }
                                catch (IOException)
                                {
                                    throw new TransferException();
                                }
                            }
                            if (length == 0)
                                break;
                            await destination.WriteAsync(buffer.AsMemory(0, length), token).ConfigureAwait(false);
                            digest?.AppendData(buffer, 0, length);
                            bytes += length;
                            if (timer.ElapsedMilliseconds >= 100)
                            {
                                progress?.Report(new(file, TransferState.Downloading, bytes, expected));
                                timer.Restart();
                            }
                        }
                        if ((contentLength is not null && bytes != contentLength) || (file.Size > 0 && bytes != file.Size) ||
                            (originalSize is not null && bytes != originalSize))
                            throw new TransferException();
                        if (digest is not null && !Convert.ToHexString(digest.GetHashAndReset()).Equals(expectedHash, StringComparison.OrdinalIgnoreCase))
                            throw new DiskException("Dosya doğrulanamadı. Yeniden deneyin.");
                        await destination.FlushAsync(token).ConfigureAwait(false);
                    }
                    finally
                    {
                        ArrayPool<byte>.Shared.Return(buffer);
                    }
                }
                token.ThrowIfCancellationRequested();
                EnsureFolder(target, folder);
                MoveToUniqueName(temporary, folder, SafeName(file.Name));
                progress?.Report(new(file, TransferState.Completed, bytes, bytes));
                return;
            }
            catch (Exception ex) when (attempt < 2 && !token.IsCancellationRequested && ex is TransferException or HttpRequestException or OperationCanceledException)
            {
                TimeSpan delay = ex is TransferException transfer && transfer.Delay is { } specified ? specified : retryDelay * (attempt + 1);
                await Task.Delay(delay, token).ConfigureAwait(false);
            }
            catch (TransferException)
            {
                throw new DiskException("İndirme kesildi. Yeniden deneyin.");
            }
            finally
            {
                if (File.Exists(temporary))
                    File.Delete(temporary);
            }
        }
    }

    private async Task<long> DownloadVideoAsync(string link, MediaFile file, string temporary, IProgress<DownloadUpdate>? progress, CancellationToken token)
    {
        using var session = ownsHttp ? new DiskClient(retryDelay: retryDelay, idleTimeout: idleTimeout) : null;
        var webClient = session ?? this;
        string page = await webClient.GetTextAsync(link, token).ConfigureAwait(false);
        string playlistUrl;
        try
        {
            var match = Regex.Match(page, "<script[^>]+id=[\"']store-prefetch[\"'][^>]*>\\s*(\\{.+?\\})\\s*</script>", RegexOptions.Singleline, TimeSpan.FromSeconds(1));
            if (!match.Success)
                throw new DiskException("Video adresi alınamadı.");
            using var store = JsonDocument.Parse(match.Groups[1].Value);
            var resource = store.RootElement.GetProperty("resources").GetProperty(RequiredString(store.RootElement, "rootResourceId"));
            var environment = store.RootElement.GetProperty("environment");
            string hash = GetString(resource, "type") == "file" ? GetString(resource, "path") ?? RequiredString(resource, "hash")
                : RequiredString(resource, "hash") + ":" + file.Path;
            string? uid = GetString(environment, "yandexuid");
            string? cookie = uid is not null && uid.Length <= 32 && uid.All(char.IsAsciiDigit) ? "yandexuid=" + uid : null;
            string body = JsonSerializer.Serialize(new { hash, sk = RequiredString(environment, "sk") });
            string apiUrl = new Uri(link).GetLeftPart(UriPartial.Authority) + "/public/api/get-video-streams";
            using var streams = await webClient.GetJsonAsync(apiUrl, token, body, cookie).ConfigureAwait(false);
            var data = streams.RootElement.GetProperty("data");
            playlistUrl = data.GetProperty("videos").EnumerateArray()
                .Where(video => GetString(video, "dimension") != "adaptive" && !string.IsNullOrWhiteSpace(GetString(video, "url")))
                .OrderByDescending(video => video.TryGetProperty("size", out var size) && size.TryGetProperty("height", out var height) && height.TryGetInt64(out var value) ? value : 0)
                .Select(video => GetString(video, "url")).FirstOrDefault() ?? throw new DiskException("Video adresi alınamadı.");
        }
        catch (Exception ex) when (ex is JsonException or KeyNotFoundException or InvalidOperationException or RegexMatchTimeoutException)
        {
            throw new DiskException("Video adresi alınamadı.");
        }
        ValidateHttps(playlistUrl);
        string playlist = (await GetTextAsync(playlistUrl, token).ConfigureAwait(false)).TrimStart('\uFEFF');
        string[] lines = playlist.Split('\n').Select(line => line.Trim()).Where(line => line.Length > 0).ToArray();
        if (lines.Length == 0 || lines[0] != "#EXTM3U" || !lines.Contains("#EXT-X-ENDLIST") ||
            lines.Any(line => line.StartsWith("#EXT-X-MAP:") || line.StartsWith("#EXT-X-BYTERANGE:") || line == "#EXT-X-DISCONTINUITY" ||
                line.StartsWith("#EXT-X-KEY:") && line != "#EXT-X-KEY:METHOD=NONE"))
            throw new DiskException("Bu video akışı desteklenmiyor.");
        string[] segments = lines.Where(line => !line.StartsWith('#')).ToArray();
        if (segments.Length == 0)
            throw new DiskException("Video adresi alınamadı.");
        long bytes = 0;
        await using var destination = new FileStream(temporary, FileMode.CreateNew, FileAccess.Write, FileShare.None, 131072, FileOptions.Asynchronous | FileOptions.SequentialScan);
        byte[] buffer = ArrayPool<byte>.Shared.Rent(131072);
        var timer = Stopwatch.StartNew();
        try
        {
            for (int index = 0; index < segments.Length; index++)
            {
                token.ThrowIfCancellationRequested();
                string segmentUrl = new Uri(new Uri(playlistUrl), segments[index]).AbsoluteUri;
                ValidateHttps(segmentUrl);
                using var response = await SendAsync(segmentUrl, token).ConfigureAwait(false);
                if (IsTransient(response.StatusCode) || response.StatusCode is HttpStatusCode.Forbidden or HttpStatusCode.Unauthorized)
                    throw new TransferException(RetryAfter(response));
                ThrowIfError(response.StatusCode);
                long? length = response.Content.Headers.ContentLength;
                long expected = Math.Max(bytes + 1, (long)((bytes + Math.Max(0, length ?? 0)) * (double)segments.Length / (index + 1)));
                long segmentBytes = 0;
                await using var source = await response.Content.ReadAsStreamAsync(token).ConfigureAwait(false);
                while (true)
                {
                    using var idle = CancellationTokenSource.CreateLinkedTokenSource(token);
                    idle.CancelAfter(idleTimeout);
                    int count;
                    try { count = await source.ReadAsync(buffer.AsMemory(), idle.Token).ConfigureAwait(false); }
                    catch (IOException) { throw new TransferException(); }
                    if (count == 0)
                        break;
                    for (int offset = (int)((188 - segmentBytes % 188) % 188); offset < count; offset += 188)
                        if (buffer[offset] != 0x47)
                            throw new DiskException("Bu video akışı desteklenmiyor.");
                    await destination.WriteAsync(buffer.AsMemory(0, count), token).ConfigureAwait(false);
                    segmentBytes += count;
                    bytes += count;
                    if (timer.ElapsedMilliseconds >= 100)
                    {
                        progress?.Report(new(file, TransferState.Downloading, bytes, Math.Max(bytes + 1, expected)));
                        timer.Restart();
                    }
                }
                if (segmentBytes == 0 || segmentBytes % 188 != 0 || length is not null && segmentBytes != length)
                    throw new TransferException();
                progress?.Report(new(file, TransferState.Downloading, bytes, Math.Max(bytes, expected)));
            }
            await destination.FlushAsync(token).ConfigureAwait(false);
            return bytes;
        }
        finally
        {
            ArrayPool<byte>.Shared.Return(buffer);
        }
    }

    private async Task<string> GetTextAsync(string url, CancellationToken token)
    {
        ValidateHttps(url);
        using var response = await SendAsync(url, token).ConfigureAwait(false);
        if (IsTransient(response.StatusCode) || response.StatusCode is HttpStatusCode.Forbidden or HttpStatusCode.Unauthorized)
            throw new TransferException(RetryAfter(response));
        ThrowIfError(response.StatusCode);
        await using var source = await response.Content.ReadAsStreamAsync(token).ConfigureAwait(false);
        using var result = new MemoryStream();
        byte[] buffer = new byte[16384];
        while (true)
        {
            using var idle = CancellationTokenSource.CreateLinkedTokenSource(token);
            idle.CancelAfter(idleTimeout);
            int count;
            try { count = await source.ReadAsync(buffer.AsMemory(), idle.Token).ConfigureAwait(false); }
            catch (IOException) { throw new TransferException(); }
            if (count == 0)
                return Encoding.UTF8.GetString(result.ToArray());
            if (result.Length + count > 8 * 1024 * 1024)
                throw new DiskException("Video adresi alınamadı.");
            result.Write(buffer, 0, count);
        }
    }

    public async Task<byte[]?> GetPreviewAsync(string url, CancellationToken token)
    {
        try
        {
            ValidateHttps(url);
            using var response = await SendAsync(url, token).ConfigureAwait(false);
            if (!response.IsSuccessStatusCode || response.Content.Headers.ContentLength > 4 * 1024 * 1024 ||
                response.Content.Headers.ContentType?.MediaType?.StartsWith("image/", StringComparison.OrdinalIgnoreCase) != true)
                return null;
            await using var source = await response.Content.ReadAsStreamAsync(token).ConfigureAwait(false);
            using var result = new MemoryStream();
            byte[] buffer = new byte[16384];
            while (true)
            {
                using var idle = CancellationTokenSource.CreateLinkedTokenSource(token);
                idle.CancelAfter(idleTimeout);
                int count = await source.ReadAsync(buffer.AsMemory(), idle.Token).ConfigureAwait(false);
                if (count == 0)
                    return result.ToArray();
                if (result.Length + count > 4 * 1024 * 1024)
                    return null;
                result.Write(buffer, 0, count);
            }
        }
        catch (Exception ex) when (ex is HttpRequestException or IOException or OperationCanceledException or DiskException)
        {
            return null;
        }
    }

    private async Task<JsonDocument> GetJsonAsync(string url, CancellationToken token, string? body = null, string? cookie = null)
    {
        for (int attempt = 0; ; attempt++)
        {
            try
            {
                using var response = await SendAsync(url, token, body, cookie).ConfigureAwait(false);
                if (IsTransient(response.StatusCode))
                {
                    if (attempt >= 2)
                        throw new DiskException("Yandex Disk şu anda yanıt vermiyor. Yeniden deneyin.");
                    await Task.Delay(RetryAfter(response) ?? retryDelay * (attempt + 1), token).ConfigureAwait(false);
                    continue;
                }
                ThrowIfError(response.StatusCode);
                using var timeout = CancellationTokenSource.CreateLinkedTokenSource(token);
                timeout.CancelAfter(idleTimeout);
                await using var stream = await response.Content.ReadAsStreamAsync(timeout.Token).ConfigureAwait(false);
                return await JsonDocument.ParseAsync(stream, cancellationToken: timeout.Token).ConfigureAwait(false);
            }
            catch (Exception ex) when (!token.IsCancellationRequested && ex is HttpRequestException or IOException or OperationCanceledException)
            {
                if (attempt >= 2)
                    throw new DiskException("Bağlantı kurulamadı. Yeniden deneyin.");
                await Task.Delay(retryDelay * (attempt + 1), token).ConfigureAwait(false);
            }
            catch (JsonException)
            {
                throw new DiskException("Yandex Disk yanıtı okunamadı.");
            }
        }
    }

    private async Task<HttpResponseMessage> SendAsync(string url, CancellationToken token, string? body = null, string? cookie = null)
    {
        using var timeout = CancellationTokenSource.CreateLinkedTokenSource(token);
        timeout.CancelAfter(TimeSpan.FromSeconds(30));
        using var request = new HttpRequestMessage(body is null ? HttpMethod.Get : HttpMethod.Post, url);
        request.Headers.UserAgent.ParseAdd("Mozilla/5.0");
        if (body is not null)
            request.Content = new StringContent(body, Encoding.UTF8, "text/plain");
        if (cookie is not null)
            request.Headers.Add("Cookie", cookie);
        return await http.SendAsync(request, HttpCompletionOption.ResponseHeadersRead, timeout.Token).ConfigureAwait(false);
    }

    private static string ResourceUrl(string link, string? path, int offset) =>
        $"{Api}?public_key={Uri.EscapeDataString(link)}&limit=100&offset={offset}&preview_size=M&preview_crop=false" +
        (path is null ? "" : $"&path={Uri.EscapeDataString(path)}");

    private static string? GetString(JsonElement item, string property) =>
        item.ValueKind == JsonValueKind.Object && item.TryGetProperty(property, out var value) && value.ValueKind == JsonValueKind.String ? value.GetString() : null;

    private static string RequiredString(JsonElement item, string property) =>
        GetString(item, property) is { Length: > 0 } value ? value : throw new DiskException("Yandex Disk yanıtı okunamadı.");

    private static void ValidateHttps(string value)
    {
        if (!Uri.TryCreate(value, UriKind.Absolute, out var uri) || uri.Scheme != Uri.UriSchemeHttps || !string.IsNullOrEmpty(uri.UserInfo))
            throw new DiskException("İndirme bağlantısı alınamadı.");
    }

    private static bool IsTransient(HttpStatusCode code) => code is HttpStatusCode.TooManyRequests or HttpStatusCode.RequestTimeout || (int)code >= 500;

    private static TimeSpan? RetryAfter(HttpResponseMessage response)
    {
        var header = response.Headers.RetryAfter;
        TimeSpan? delay = header?.Delta ?? (header?.Date - DateTimeOffset.UtcNow);
        return delay is null ? null : TimeSpan.FromSeconds(Math.Clamp(delay.Value.TotalSeconds, 0, 60));
    }

    private static void ThrowIfError(HttpStatusCode code)
    {
        if ((int)code is >= 200 and < 300)
            return;
        throw new DiskException(code switch
        {
            HttpStatusCode.NotFound => "Bağlantı bulunamadı veya paylaşım kapalı.",
            HttpStatusCode.Forbidden or HttpStatusCode.Unauthorized => "Bu bağlantıya erişilemiyor veya indirmeye izin verilmiyor.",
            HttpStatusCode.BadRequest => "Bağlantı okunamadı. Paylaşım bağlantısını kontrol edin.",
            _ => "Yandex Disk isteği tamamlanamadı. Yeniden deneyin."
        });
    }

    public static string ErrorMessage(Exception ex) => ex switch
    {
        DiskException => ex.Message,
        UnauthorizedAccessException => "Klasöre yazma izni yok.",
        IOException => "Dosya kaydedilemedi. Disk alanını ve klasörü kontrol edin.",
        _ => "Bağlantı kesildi. Yeniden deneyin."
    };

    public static string SafeName(string name)
    {
        var invalid = System.IO.Path.GetInvalidFileNameChars();
        string clean = new(name.Select(c => invalid.Contains(c) || char.IsControl(c) ? '_' : c).ToArray());
        clean = clean.TrimEnd(' ', '.');
        if (string.IsNullOrEmpty(clean) || clean is "." or "..")
            clean = "_";
        string stem = clean.Split('.')[0].TrimEnd(' ');
        string[] reserved = ["CON", "PRN", "AUX", "NUL", "CONIN$", "CONOUT$", "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9", "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9", "COM¹", "COM²", "COM³", "LPT¹", "LPT²", "LPT³"];
        if (reserved.Contains(stem, StringComparer.OrdinalIgnoreCase))
            clean = "_" + clean;
        if (clean.Length > 180)
        {
            string extension = System.IO.Path.GetExtension(clean);
            if (extension.Length > 20)
                extension = "";
            clean = clean[..(180 - extension.Length)] + extension;
        }
        return clean;
    }

    private static string FolderKey(IEnumerable<string> segments) => string.Join('\0', segments);

    private static Dictionary<string, string> BuildFolders(IReadOnlyList<MediaFile> files, string target)
    {
        var folders = new Dictionary<string, string>(StringComparer.Ordinal) { [""] = target };
        var used = new Dictionary<string, HashSet<string>>(StringComparer.OrdinalIgnoreCase);
        foreach (var file in files.OrderBy(f => string.Join('/', f.Segments), StringComparer.Ordinal))
        {
            for (int depth = 1; depth < file.Segments.Length; depth++)
            {
                string key = FolderKey(file.Segments.Take(depth));
                if (folders.ContainsKey(key))
                    continue;
                string parent = folders[FolderKey(file.Segments.Take(depth - 1))];
                if (!used.TryGetValue(parent, out var names))
                    used[parent] = names = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
                string original = SafeName(file.Segments[depth - 1]);
                string name = original;
                int number = 1;
                while (!names.Add(name) || File.Exists(System.IO.Path.Combine(parent, name)))
                    name = $"{original} ({number++})";
                folders[key] = System.IO.Path.Combine(parent, name);
            }
        }
        return folders;
    }

    private static void EnsureFolder(string root, string folder)
    {
        string fullRoot = System.IO.Path.TrimEndingDirectorySeparator(System.IO.Path.GetFullPath(root));
        string fullFolder = System.IO.Path.GetFullPath(folder);
        string prefix = System.IO.Path.EndsInDirectorySeparator(fullRoot) ? fullRoot : fullRoot + System.IO.Path.DirectorySeparatorChar;
        if (!fullFolder.Equals(fullRoot, StringComparison.OrdinalIgnoreCase) &&
            !fullFolder.StartsWith(prefix, StringComparison.OrdinalIgnoreCase))
            throw new DiskException("Dosya yolu geçersiz.");
        var current = new DirectoryInfo(fullFolder);
        while (current is not null)
        {
            if (current.Exists && (current.Attributes & FileAttributes.ReparsePoint) != 0)
                throw new DiskException("Bağlantılı klasöre indirme yapılamıyor.");
            if (current.FullName.Equals(fullRoot, StringComparison.OrdinalIgnoreCase))
                break;
            current = current.Parent;
        }
        Directory.CreateDirectory(fullFolder);
    }

    private static void MoveToUniqueName(string temporary, string folder, string name)
    {
        string stem = System.IO.Path.GetFileNameWithoutExtension(name);
        string extension = System.IO.Path.GetExtension(name);
        for (int index = 0; ; index++)
        {
            string destination = System.IO.Path.Combine(folder, index == 0 ? name : $"{stem} ({index}){extension}");
            try
            {
                File.Move(temporary, destination, false);
                return;
            }
            catch (IOException) when (File.Exists(destination) || Directory.Exists(destination))
            {
            }
        }
    }

    public void Dispose()
    {
        if (ownsHttp)
            http.Dispose();
    }

    private sealed class TransferException(TimeSpan? delay = null) : Exception
    {
        public TimeSpan? Delay { get; } = delay;
    }

    private sealed class InlineProgress<T>(Action<T> report) : IProgress<T>
    {
        public void Report(T value) => report(value);
    }
}

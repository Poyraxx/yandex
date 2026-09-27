using System.Collections.Concurrent;
using System.IO;
using System.Net;
using System.Net.Http;
using System.Reflection;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Markup;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using System.Xml.Linq;
using Yandex;

internal static class Program
{
    private const string Link = "https://disk.yandex.com/d/test";
    private static readonly byte[] Payload = Encoding.UTF8.GetBytes("video-and-image-fixture");

    [STAThread]
    private static int Main(string[] args)
    {
        if (args is ["--public-link", var link, "--public-path", var path])
            return VerifyPublicDownloadAsync(link, path).GetAwaiter().GetResult();
        if (args is ["--public-video", var videoLink, "--public-path", var videoPath])
            return VerifyPublicDownloadAsync(videoLink, videoPath, true).GetAwaiter().GetResult();
        if (args.Contains("--ui") || args.Contains("--ui-check"))
        {
            return ShowFixture(args);
        }
        return RunTestsAsync().GetAwaiter().GetResult();
    }

    private static async Task<int> RunTestsAsync()
    {
        (string Name, Func<Task> Run)[] tests =
        [
            ("Bağlantı doğrulama", ValidateLinksAsync),
            ("Tek görsel ve tek video", SingleFilesAsync),
            ("Alt klasörler ve medya süzme", NestedFilesAsync),
            ("Çok sayfalı klasör", PaginationAsync),
            ("Eksik taramanın reddi", IncompleteListingAsync),
            ("Kapalı ve engellenmiş bağlantılar", InaccessibleLinksAsync),
            ("Geçici API hataları", MetadataRetriesAsync),
            ("Bozuk API yanıtı", MalformedJsonAsync),
            ("Listeleme iptali", ListingCancellationAsync),
            ("Yalnız seçilen dosyalar", SelectionAsync),
            ("Mevcut dosya ve isim çakışmaları", NameCollisionsAsync),
            ("Windows adları ve klasör sınırı", SafePathsAsync),
            ("Klasör adı çakışmaları", FolderCollisionsAsync),
            ("İndirme bağlantısını yenileme", DownloadRetriesAsync),
            ("Boş indirme adresi ve devam eden kuyruk", EmptyDownloadAddressAsync),
            ("Orijinal adres ve SHA-256 doğrulama", OriginalAddressAsync),
            ("Aynı boyutta farklı dosyanın reddi", OriginalHashMismatchAsync),
            ("Önizlemenin orijinal yerine kaydedilmemesi", OriginalAddressRequirementsAsync),
            ("Süresi dolan orijinal adresin yenilenmesi", OriginalAddressRefreshAsync),
            ("En yüksek video akışı ve TS dosya adı", VideoStreamAsync),
            ("Kesilen video akışının yenilenmesi", VideoStreamRetryAsync),
            ("Video akışı iptali ve temizlik", VideoStreamCancellationAsync),
            ("Eksik ve desteklenmeyen video akışları", VideoStreamErrorsAsync),
            ("Eksik dosya ve devam eden kuyruk", FailedTransferAsync),
            ("Büyük dosya, akış ve eşzamanlılık", LargeStreamingAsync),
            ("İptal ve yarım dosya temizliği", DownloadCancellationAsync),
            ("Boşta kalan bağlantı zaman aşımı", IdleTimeoutAsync),
            ("Önizleme boyutu ve video engeli", PreviewLimitsAsync),
            ("İndirme hata durumları", DestinationErrorsAsync)
        ];
        int failed = 0;
        foreach (var test in tests)
        {
            try
            {
                await test.Run();
                Console.WriteLine($"PASS {test.Name}");
            }
            catch (Exception ex)
            {
                failed++;
                Console.WriteLine($"FAIL {test.Name}: {ex}");
            }
        }
        Console.WriteLine($"{tests.Length - failed}/{tests.Length} başarılı");
        return failed == 0 ? 0 : 1;
    }

    private static async Task<int> VerifyPublicDownloadAsync(string link, string path, bool playback = false)
    {
        using var folder = new TemporaryFolder();
        using var http = new HttpClient(playback ? new PlaybackHandler() : new SocketsHttpHandler());
        using var client = new DiskClient(http);
        string name = playback ? "test.mp4" : "test.jpg";
        var file = new MediaFile(name, path, [name], 0, playback, null);
        var result = await client.DownloadAsync(link, [file], folder.Path, null, default);
        if (result.Completed != 1)
        {
            Console.WriteLine(result.Updates.Single().Error);
            return 1;
        }
        string saved = Directory.GetFiles(folder.Path).Single();
        if (playback) Equal(".ts", System.IO.Path.GetExtension(saved));
        Console.WriteLine($"API downloaded bytes: {new FileInfo(saved).Length}");
        using var savedStream = System.IO.File.OpenRead(saved);
        Console.WriteLine($"API SHA-256: {Convert.ToHexString(SHA256.HashData(savedStream))}");
        return 0;
    }

    private static Task ValidateLinksAsync()
    {
        foreach (string host in new[] { "disk.yandex.com", "disk.yandex.ru", "disk.yandex.com.tr", "yadi.sk" })
            Equal($"https://{host}/d/test", DiskClient.ValidateLink($" https://{host}/d/test?utm_source=copy#x "));
        foreach (string link in new[] { "", "http://disk.yandex.com/d/test", "https://example.com/d/test", "https://disk.yandex.com.evil/d/test", "https://x@disk.yandex.com/d/test", "https://disk.yandex.com:123/d/test", "https://disk.yandex.com/", "https://disk.yandex.com/d" })
            Throws<DiskException>(() => DiskClient.ValidateLink(link));
        return Task.CompletedTask;
    }

    private static async Task SingleFilesAsync()
    {
        foreach (var item in new[] { Resource("foto.jpg", "/", "image/jpeg"), Resource("video.mp4", "/", "video/mp4") })
        {
            using var http = Mock(_ => Json(item));
            using var client = Client(http);
            var files = await client.ListAsync(Link, null, default);
            Equal(1, files.Count);
            Equal("/", files[0].Path);
            Equal("", files[0].Folder);
            Equal(item["mime_type"]!.ToString() == "video/mp4", files[0].IsVideo);
        }
    }

    private static async Task NestedFilesAsync()
    {
        var root = DirectoryResource([
            Resource("foto.jpg", "/foto.jpg", "image/jpeg"),
            Resource("document.jpg", "/document.jpg", "text/plain"),
            Resource("doc.pdf", "/doc.pdf", "application/pdf"),
            DirectoryItem("Alt", "/Alt")
        ]);
        using var http = Mock(request => Query(request, "path") switch
        {
            "/Alt" => Json(DirectoryResource([Resource("film.mkv", "/Alt/film.mkv", null), DirectoryItem("İç", "/Alt/İç")])),
            "/Alt/İç" => Json(DirectoryResource([Resource("resim.PNG", "/Alt/İç/resim.PNG", "application/octet-stream")])),
            _ => Json(root)
        });
        using var client = Client(http);
        var files = await client.ListAsync(Link, null, default);
        Equal(3, files.Count);
        Equal("Alt / İç", files.Single(f => f.Name == "resim.PNG").Folder);
        Check(files.Single(f => f.Name == "film.mkv").IsVideo, "Uzantı ile video tanınmadı");
    }

    private static async Task PaginationAsync()
    {
        var calls = new List<int>();
        using var http = Mock(request =>
        {
            int offset = int.Parse(Query(request, "offset")!);
            calls.Add(offset);
            var page = Enumerable.Range(offset, Math.Min(100, 205 - offset))
                .Select(i => Resource($"{i}.jpg", $"/{i}.jpg", "image/jpeg")).ToArray();
            return Json(DirectoryResource(page, 205));
        });
        using var client = Client(http);
        var files = await client.ListAsync(Link, null, default);
        Equal(205, files.Count);
        Equal("0,100,200", string.Join(',', calls));
        Equal(205, files.Select(f => f.Path).Distinct().Count());
    }

    private static async Task IncompleteListingAsync()
    {
        using var http = Mock(request => Query(request, "offset") == "0"
            ? Json(DirectoryResource([Resource("a.jpg", "/a.jpg", "image/jpeg")], 2))
            : Json(DirectoryResource([], 2)));
        using var client = Client(http);
        await ThrowsAsync<DiskException>(() => client.ListAsync(Link, null, default));
        using var denied = Mock(request => Query(request, "path") is null
            ? Json(DirectoryResource([Resource("a.jpg", "/a.jpg", "image/jpeg"), DirectoryItem("Alt", "/Alt")]))
            : new HttpResponseMessage(HttpStatusCode.Forbidden));
        using var second = Client(denied);
        await ThrowsAsync<DiskException>(() => second.ListAsync(Link, null, default));
    }

    private static async Task InaccessibleLinksAsync()
    {
        foreach (var code in new[] { HttpStatusCode.Forbidden, HttpStatusCode.NotFound, HttpStatusCode.Unauthorized })
        {
            using var http = Mock(_ => new HttpResponseMessage(code));
            using var client = Client(http);
            var ex = await ThrowsAsync<DiskException>(() => client.ListAsync(Link, null, default));
            Check(ex.Message.Contains("Bağlantı") || ex.Message.Contains("erişilemiyor"), "Türkçe hata eksik");
        }
    }

    private static async Task MetadataRetriesAsync()
    {
        int calls = 0;
        using var http = Mock(_ => ++calls switch
        {
            1 => new HttpResponseMessage(HttpStatusCode.ServiceUnavailable),
            2 => RetryResponse(),
            _ => Json(Resource("a.jpg", "/", "image/jpeg"))
        });
        using var client = Client(http);
        Equal(1, (await client.ListAsync(Link, null, default)).Count);
        Equal(3, calls);
        int failures = 0;
        using var broken = Mock(_ => { failures++; throw new HttpRequestException(); });
        using var offline = Client(broken);
        await ThrowsAsync<DiskException>(() => offline.ListAsync(Link, null, default));
        Equal(3, failures);
    }

    private static async Task MalformedJsonAsync()
    {
        foreach (string body in new[] { "{", "[]", "null", "{\"type\":\"dir\",\"_embedded\":null}", "{\"type\":\"file\"}" })
        {
            using var http = Mock(_ => new HttpResponseMessage(HttpStatusCode.OK) { Content = new StringContent(body) });
            using var client = Client(http);
            await ThrowsAsync<DiskException>(() => client.ListAsync(Link, null, default));
        }
    }

    private static async Task ListingCancellationAsync()
    {
        using var cancel = new CancellationTokenSource();
        using var http = Mock(_ => Json(DirectoryResource([
            Resource("a.jpg", "/a.jpg", "image/jpeg"), Resource("b.jpg", "/b.jpg", "image/jpeg")
        ])));
        using var client = Client(http);
        var progress = new Capture<int>(_ => cancel.Cancel());
        await ThrowsAsync<OperationCanceledException>(() => client.ListAsync(Link, progress, cancel.Token));
    }

    private static async Task SelectionAsync()
    {
        using var folder = new TemporaryFolder();
        var requested = new ConcurrentBag<string>();
        using var http = DownloadMock(request =>
        {
            requested.Add(Query(request, "path")!);
            return Bytes(Payload);
        });
        using var client = Client(http);
        var result = await client.DownloadAsync(Link, [File("a.jpg"), File("film.mp4", ["Alt", "film.mp4"])], folder.Path, null, default);
        Equal(2, result.Completed);
        Equal(2, requested.Count);
        Check(System.IO.File.Exists(System.IO.Path.Combine(folder.Path, "Alt", "film.mp4")), "Alt klasör korunmadı");
        Check(!System.IO.File.Exists(System.IO.Path.Combine(folder.Path, "b.jpg")), "Seçilmeyen dosya indirildi");
        Equal(Convert.ToHexString(SHA256.HashData(Payload)), Convert.ToHexString(SHA256.HashData(await System.IO.File.ReadAllBytesAsync(System.IO.Path.Combine(folder.Path, "a.jpg")))));
    }

    private static async Task NameCollisionsAsync()
    {
        using var folder = new TemporaryFolder();
        await System.IO.File.WriteAllTextAsync(System.IO.Path.Combine(folder.Path, "a.jpg"), "existing");
        using var http = DownloadMock(_ => Bytes(Payload));
        using var client = Client(http);
        var result = await client.DownloadAsync(Link, [File("a.jpg"), File("a.jpg", ["a.jpg"], "/second/a.jpg")], folder.Path, null, default);
        Equal(2, result.Completed);
        Equal("existing", await System.IO.File.ReadAllTextAsync(System.IO.Path.Combine(folder.Path, "a.jpg")));
        Check(System.IO.File.Exists(System.IO.Path.Combine(folder.Path, "a (1).jpg")), "İlk çakışma korunmadı");
        Check(System.IO.File.Exists(System.IO.Path.Combine(folder.Path, "a (2).jpg")), "Eşzamanlı çakışma korunmadı");
        Equal(0, Directory.GetFiles(folder.Path, "*.part", SearchOption.AllDirectories).Length);
    }

    private static async Task SafePathsAsync()
    {
        using var folder = new TemporaryFolder();
        using var http = DownloadMock(_ => Bytes(Payload));
        using var client = Client(http);
        string[] segments = ["..", "C:\\outside", "Alt/İç", "CON.jpg"];
        var result = await client.DownloadAsync(Link, [File("CON.jpg", segments)], folder.Path, null, default);
        Equal(1, result.Completed);
        var saved = Directory.GetFiles(folder.Path, "*", SearchOption.AllDirectories).Single();
        Check(saved.StartsWith(folder.Path + System.IO.Path.DirectorySeparatorChar, StringComparison.OrdinalIgnoreCase), "Klasör sınırı aşıldı");
        Equal("_CON.jpg", System.IO.Path.GetFileName(saved));
        Check(!System.IO.Path.GetRelativePath(folder.Path, saved).Split(System.IO.Path.DirectorySeparatorChar).Contains(".."), "Üst klasöre çıkıldı");
        foreach (string name in new[] { "NUL.png", "COM1", "LPT9.jpg", "PRN", "AUX.txt", "con.jpg" })
            Check(DiskClient.SafeName(name).StartsWith('_'), "Ayrılmış Windows adı kullanılabiliyor");
        Check(DiskClient.SafeName(new string('a', 300) + ".mp4").EndsWith(".mp4"), "Uzantı kaybedildi");
    }

    private static async Task FolderCollisionsAsync()
    {
        using var folder = new TemporaryFolder();
        using var http = DownloadMock(_ => Bytes(Payload));
        using var client = Client(http);
        var result = await client.DownloadAsync(Link, [File("a.jpg", ["a?", "a.jpg"]), File("b.jpg", ["a*", "b.jpg"])], folder.Path, null, default);
        Equal(2, result.Completed);
        Equal(2, Directory.GetDirectories(folder.Path).Length);
        Check(Directory.GetDirectories(folder.Path).All(d => Directory.GetFiles(d).Length == 1), "Farklı klasörler birleşti");
    }

    private static async Task DownloadRetriesAsync()
    {
        using var folder = new TemporaryFolder();
        int links = 0;
        int transfers = 0;
        using var http = Mock(request =>
        {
            if (request.RequestUri!.Host == "cloud-api.yandex.net")
            {
                links++;
                return Json(new { href = $"https://download.test/{links}" });
            }
            return ++transfers switch
            {
                1 => new HttpResponseMessage(HttpStatusCode.Forbidden),
                2 => new HttpResponseMessage(HttpStatusCode.OK) { Content = new StreamContent(new GeneratedStream(Payload.Length - 1)) },
                _ => Bytes(Payload)
            };
        });
        using var client = Client(http);
        var result = await client.DownloadAsync(Link, [File("a.jpg")], folder.Path, null, default);
        Equal(1, result.Completed);
        Equal(3, links);
        Equal(3, transfers);
        Equal(0, Directory.GetFiles(folder.Path, "*.part").Length);
    }

    private static async Task EmptyDownloadAddressAsync()
    {
        foreach (var body in new[] { "{\"method\":\"GET\",\"href\":\"\",\"templated\":false}", "{\"href\":\"   \"}", "{\"href\":null}", "{}" })
        {
            using var folder = new TemporaryFolder();
            using var http = Mock(request => request.RequestUri!.Host != "cloud-api.yandex.net" ? Bytes(Payload) :
                Query(request, "path") == "/bad.jpg" ? new HttpResponseMessage(HttpStatusCode.OK) { Content = new StringContent(body) } :
                Json(new { href = "https://download.test/file" }));
            using var client = Client(http);
            var result = await client.DownloadAsync(Link, [File("bad.jpg"), File("good.jpg")], folder.Path, null, default);
            Equal(1, result.Completed);
            Equal(1, result.Failed);
            Check(result.Updates.Any(u => u.State == TransferState.Failed && u.Error!.Contains("İndirme adresi verilmedi")), "Boş adres için hata eksik");
            Equal(1, Directory.GetFiles(folder.Path).Length);
            Check(System.IO.File.Exists(System.IO.Path.Combine(folder.Path, "good.jpg")), "Diğer dosya indirilmedi");
        }
    }

    private static async Task OriginalAddressAsync()
    {
        foreach (bool direct in new[] { false, true })
        {
            using var folder = new TemporaryFolder();
            using var http = Mock(request => request.RequestUri!.Host != "cloud-api.yandex.net" ? Bytes(Payload) :
                request.RequestUri.AbsolutePath.EndsWith("/download") ? Json(new { href = "" }) :
                Json(new { type = "file", size = Payload.Length, sha256 = Convert.ToHexString(SHA256.HashData(Payload)),
                    file = direct ? "https://download.test/original" : "", sizes = new[] { new { name = "ORIGINAL", url = "https://download.test/original" } } }));
            using var client = Client(http);
            var result = await client.DownloadAsync(Link, [File(direct ? "film.mp4" : "a.jpg")], folder.Path, null, default);
            Equal(1, result.Completed);
            Check(System.IO.File.ReadAllBytes(Directory.GetFiles(folder.Path).Single()).SequenceEqual(Payload), "Orijinal veri değişti");
        }
    }

    private static async Task OriginalHashMismatchAsync()
    {
        using var folder = new TemporaryFolder();
        using var http = Mock(request => request.RequestUri!.Host != "cloud-api.yandex.net" ? Bytes(Payload) :
            request.RequestUri.AbsolutePath.EndsWith("/download") ? Json(new { href = "" }) :
            Json(new { type = "file", size = Payload.Length, sha256 = new string('0', 64), file = "https://download.test/original" }));
        using var client = Client(http);
        var result = await client.DownloadAsync(Link, [File("a.jpg")], folder.Path, null, default);
        Equal(1, result.Failed);
        Check(result.Updates.Single().Error!.Contains("Dosya doğrulanamadı"), "Hash hatası belirtilmedi");
        Equal(0, Directory.GetFiles(folder.Path).Length);
    }

    private static async Task OriginalAddressRequirementsAsync()
    {
        foreach (string variant in new[] { "no-hash", "preview", "video" })
        {
            using var folder = new TemporaryFolder();
            int transfers = 0;
            using var http = Mock(request =>
            {
                if (request.RequestUri!.Host == "disk.yandex.com")
                    return new HttpResponseMessage(HttpStatusCode.OK) { Content = new StringContent("<html></html>") };
                if (request.RequestUri.Host != "cloud-api.yandex.net")
                {
                    Interlocked.Increment(ref transfers);
                    return Bytes(Payload);
                }
                return request.RequestUri.AbsolutePath.EndsWith("/download") ? Json(new { href = "" }) :
                    Json(new { type = "file", size = Payload.Length, sha256 = variant == "no-hash" ? "" : Convert.ToHexString(SHA256.HashData(Payload)),
                        preview = "https://download.test/preview", sizes = new[] { new { name = variant == "preview" ? "M" : "ORIGINAL", url = "https://download.test/preview" } } });
            });
            using var client = Client(http);
            var result = await client.DownloadAsync(Link, [File(variant == "video" ? "film.mp4" : "a.jpg")], folder.Path, null, default);
            Equal(1, result.Failed);
            Equal(0, transfers);
            Equal(0, Directory.GetFiles(folder.Path).Length);
        }
    }

    private static async Task OriginalAddressRefreshAsync()
    {
        using var folder = new TemporaryFolder();
        int metadataCalls = 0;
        using var http = Mock(request =>
        {
            if (request.RequestUri!.Host != "cloud-api.yandex.net")
                return request.RequestUri.AbsolutePath == "/1" ? new HttpResponseMessage(HttpStatusCode.Forbidden) : Bytes(Payload);
            if (request.RequestUri.AbsolutePath.EndsWith("/download")) return Json(new { href = "" });
            return Json(new { type = "file", size = Payload.Length, sha256 = Convert.ToHexString(SHA256.HashData(Payload)),
                file = "https://download.test/" + Interlocked.Increment(ref metadataCalls) });
        });
        using var client = Client(http);
        var result = await client.DownloadAsync(Link, [File("a.jpg")], folder.Path, null, default);
        Equal(1, result.Completed);
        Equal(2, metadataCalls);
        Equal(1, Directory.GetFiles(folder.Path).Length);
    }

    private static readonly byte[] VideoPayload = Enumerable.Range(0, 188 * 3).Select(index => (byte)(index % 188 == 0 ? 0x47 : 1)).ToArray();

    private static HttpClient VideoMock(Func<HttpRequestMessage, HttpResponseMessage> route) => Mock(request =>
    {
        var uri = request.RequestUri!;
        if (uri.Host == "cloud-api.yandex.net") return Json(new { href = "" });
        if (uri.AbsolutePath == "/d/test")
        {
            string store = JsonSerializer.Serialize(new { rootResourceId = "root", resources = new { root = new { type = "dir", hash = "public-hash" } }, environment = new { sk = "public-sk", yandexuid = "123" } });
            return new HttpResponseMessage(HttpStatusCode.OK) { Content = new StringContent("<script id=\"store-prefetch\">" + store + "</script>") };
        }
        if (uri.AbsolutePath == "/public/api/get-video-streams")
        {
            Equal(HttpMethod.Post, request.Method);
            using var body = JsonDocument.Parse(request.Content!.ReadAsStringAsync().GetAwaiter().GetResult());
            Equal("public-hash:/film.mp4", body.RootElement.GetProperty("hash").GetString());
            Check(request.Headers.GetValues("Cookie").Single().Contains("yandexuid=123"), "Anonim video çerezi eksik");
            return route(request);
        }
        return route(request);
    });

    private static HttpResponseMessage VideoStreams() => Json(new { data = new { videos = new[] {
        new { dimension = "240p", size = new { height = 240 }, url = "https://video.test/low/list.m3u8" },
        new { dimension = "adaptive", size = new { height = 0 }, url = "https://video.test/master.m3u8" },
        new { dimension = "1080p", size = new { height = 1080 }, url = "https://video.test/high/list.m3u8" } } } });

    private static HttpResponseMessage Playlist(string value) => new(HttpStatusCode.OK) { Content = new StringContent(value) };

    private static async Task VideoStreamAsync()
    {
        using var folder = new TemporaryFolder();
        System.IO.File.WriteAllBytes(System.IO.Path.Combine(folder.Path, "film.ts"), Payload);
        using var http = VideoMock(request => request.RequestUri!.AbsolutePath == "/public/api/get-video-streams" ? VideoStreams() :
            request.RequestUri.AbsolutePath == "/high/list.m3u8" ? Playlist("#EXTM3U\n#EXTINF:4,\n1.ts\n#EXTINF:2,\n2.ts\n#EXT-X-ENDLIST\n") :
            request.RequestUri.AbsolutePath is "/high/1.ts" or "/high/2.ts" ? Bytes(VideoPayload) : throw new Exception("Yanlış video kalitesi"));
        using var client = Client(http);
        var progress = new Capture<DownloadUpdate>();
        var result = await client.DownloadAsync(Link, [File("film.mp4")], folder.Path, progress, default);
        Equal(1, result.Completed);
        var saved = System.IO.File.ReadAllBytes(System.IO.Path.Combine(folder.Path, "film (1).ts"));
        Check(saved.SequenceEqual(VideoPayload.Concat(VideoPayload)), "Video parçaları eksik");
        Check(!System.IO.File.Exists(System.IO.Path.Combine(folder.Path, "film.mp4")), "TS dosyası MP4 adıyla kaydedildi");
        Check(progress.Values.Any(update => update.State == TransferState.Downloading && update.Bytes > 0 && update.Bytes < update.Total), "Video ilerlemesi eksik");
    }

    private static async Task VideoStreamRetryAsync()
    {
        using var folder = new TemporaryFolder();
        int addresses = 0;
        int attempts = 0;
        using var http = VideoMock(request =>
        {
            if (request.RequestUri!.AbsolutePath == "/public/api/get-video-streams") { Interlocked.Increment(ref addresses); return VideoStreams(); }
            if (request.RequestUri.AbsolutePath.EndsWith(".m3u8")) return Playlist("#EXTM3U\n1.ts\n#EXT-X-ENDLIST\n");
            return Interlocked.Increment(ref attempts) == 1 ? new HttpResponseMessage(HttpStatusCode.Forbidden) : Bytes(VideoPayload);
        });
        using var client = Client(http);
        var result = await client.DownloadAsync(Link, [File("film.mp4")], folder.Path, null, default);
        Equal(1, result.Completed);
        Equal(2, addresses);
        Check(System.IO.File.ReadAllBytes(Directory.GetFiles(folder.Path).Single()).SequenceEqual(VideoPayload), "Yarım video tekrarlandı");
    }

    private static async Task VideoStreamCancellationAsync()
    {
        using var folder = new TemporaryFolder();
        using var cancel = new CancellationTokenSource();
        using var http = VideoMock(request => request.RequestUri!.AbsolutePath == "/public/api/get-video-streams" ? VideoStreams() :
            request.RequestUri.AbsolutePath.EndsWith(".m3u8") ? Playlist("#EXTM3U\n1.ts\n2.ts\n#EXT-X-ENDLIST\n") : Bytes(VideoPayload));
        using var client = Client(http);
        var progress = new Capture<DownloadUpdate>(update => { if (update.Bytes > 0) cancel.Cancel(); });
        var result = await client.DownloadAsync(Link, [File("film.mp4")], folder.Path, progress, cancel.Token);
        Equal(1, result.Cancelled);
        Equal(0, Directory.GetFiles(folder.Path).Length);
    }

    private static async Task VideoStreamErrorsAsync()
    {
        foreach (string playlist in new[] { "#EXTM3U\n1.ts", "#EXTM3U\n#EXT-X-KEY:METHOD=SAMPLE-AES\n1.ts\n#EXT-X-ENDLIST", "#EXTM3U\n#EXT-X-MAP:URI=init.mp4\n1.ts\n#EXT-X-ENDLIST", "#EXTM3U\nfile:///outside\n#EXT-X-ENDLIST", "#EXTM3U\n1.ts\n#EXT-X-ENDLIST" })
        {
            using var folder = new TemporaryFolder();
            using var http = VideoMock(request => request.RequestUri!.AbsolutePath == "/public/api/get-video-streams" ? VideoStreams() :
                request.RequestUri.AbsolutePath.EndsWith(".m3u8") ? Playlist(playlist) : Bytes(Payload));
            using var client = Client(http);
            var result = await client.DownloadAsync(Link, [File("film.mp4")], folder.Path, null, default);
            Equal(1, result.Failed);
            Equal(0, Directory.GetFiles(folder.Path).Length);
        }
    }

    private sealed class PlaybackHandler() : DelegatingHandler(new SocketsHttpHandler { ConnectTimeout = TimeSpan.FromSeconds(20) })
    {
        protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken cancellationToken) =>
            request.RequestUri!.Host == "cloud-api.yandex.net" ? Task.FromResult(Json(new { href = "" })) : base.SendAsync(request, cancellationToken);
    }

    private static async Task FailedTransferAsync()
    {
        using var folder = new TemporaryFolder();
        int badAttempts = 0;
        using var http = DownloadMock(request =>
        {
            if (Query(request, "path")!.Contains("bad"))
            {
                Interlocked.Increment(ref badAttempts);
                return new HttpResponseMessage(HttpStatusCode.OK) { Content = new StreamContent(new GeneratedStream(3)) };
            }
            return Bytes(Payload);
        });
        using var client = Client(http);
        var result = await client.DownloadAsync(Link, [File("bad.mp4"), File("good.jpg")], folder.Path, null, default);
        Equal(1, result.Completed);
        Equal(1, result.Failed);
        Equal(3, badAttempts);
        Check(!System.IO.File.Exists(System.IO.Path.Combine(folder.Path, "bad.mp4")), "Yarım dosya kaydedildi");
        Equal(1, Directory.GetFiles(folder.Path).Length);
        Check(result.Updates.Any(u => u.State == TransferState.Failed && u.Error is not null), "Hata dosyada belirtilmedi");
    }

    private static async Task LargeStreamingAsync()
    {
        using var folder = new TemporaryFolder();
        int active = 0;
        int maximum = 0;
        int largestBuffer = 0;
        const int size = 8 * 1024 * 1024;
        using var http = DownloadMock(_ =>
        {
            int current = Interlocked.Increment(ref active);
            int observed;
            do { observed = maximum; } while (current > observed && Interlocked.CompareExchange(ref maximum, current, observed) != observed);
            var stream = new GeneratedStream(size, TimeSpan.FromMilliseconds(2), count =>
            {
                int previous;
                do { previous = largestBuffer; } while (count > previous && Interlocked.CompareExchange(ref largestBuffer, count, previous) != previous);
            }, () => Interlocked.Decrement(ref active));
            var content = new StreamContent(stream);
            content.Headers.ContentLength = size;
            return new HttpResponseMessage(HttpStatusCode.OK) { Content = content };
        });
        using var client = Client(http);
        var progress = new Capture<DownloadUpdate>();
        var files = Enumerable.Range(0, 4).Select(i => File($"{i}.mp4") with { Size = size }).ToArray();
        var result = await client.DownloadAsync(Link, files, folder.Path, progress, default);
        Equal(4, result.Completed);
        Equal(2, maximum);
        Check(largestBuffer <= 131072, "Dosya tek parçada okundu");
        Check(Directory.GetFiles(folder.Path).All(f => new FileInfo(f).Length == size), "Büyük dosya eksik");
        Check(progress.Values.Any(u => u.State == TransferState.Downloading && u.Bytes > 0), "Akış ilerlemesi raporlanmadı");
    }

    private static async Task DownloadCancellationAsync()
    {
        using var folder = new TemporaryFolder();
        using var cancel = new CancellationTokenSource();
        using var http = DownloadMock(request => Query(request, "path")!.Contains("first")
            ? Bytes(Payload)
            : new HttpResponseMessage(HttpStatusCode.OK) { Content = new StreamContent(new GeneratedStream(8 * 1024 * 1024, TimeSpan.FromMilliseconds(50))) });
        using var client = Client(http);
        var progress = new Capture<DownloadUpdate>(update =>
        {
            if (update.State == TransferState.Completed)
                cancel.Cancel();
        });
        var result = await client.DownloadAsync(Link, [File("first.jpg"), File("second.mp4"), File("third.mp4")], folder.Path, progress, cancel.Token);
        Equal(1, result.Completed);
        Equal(2, result.Cancelled);
        Equal(0, result.Failed);
        Equal(3, result.Updates.Count);
        Equal(1, Directory.GetFiles(folder.Path).Length);
        Check(System.IO.File.Exists(System.IO.Path.Combine(folder.Path, "first.jpg")), "Tamamlanan dosya silindi");
        using var alreadyCancelled = new CancellationTokenSource();
        alreadyCancelled.Cancel();
        var noTransfers = await client.DownloadAsync(Link, [File("not-started.mp4")], folder.Path, null, alreadyCancelled.Token);
        Equal(1, noTransfers.Cancelled);
    }

    private static async Task IdleTimeoutAsync()
    {
        using var folder = new TemporaryFolder();
        int calls = 0;
        using var http = DownloadMock(_ =>
        {
            Interlocked.Increment(ref calls);
            return new HttpResponseMessage(HttpStatusCode.OK) { Content = new StreamContent(new GeneratedStream(100, TimeSpan.FromSeconds(30))) };
        });
        using var client = new DiskClient(http, TimeSpan.Zero, TimeSpan.FromMilliseconds(20));
        var result = await client.DownloadAsync(Link, [File("slow.mp4")], folder.Path, null, default);
        Equal(1, result.Failed);
        Equal(3, calls);
        Equal(0, Directory.GetFiles(folder.Path).Length);
    }

    private static async Task PreviewLimitsAsync()
    {
        int bodyReads = 0;
        using var http = Mock(request =>
        {
            var content = new StreamContent(new GeneratedStream(5 * 1024 * 1024, read: _ => Interlocked.Increment(ref bodyReads)));
            content.Headers.ContentType = new(request.RequestUri!.AbsolutePath == "/video" ? "video/mp4" : "image/png");
            if (request.RequestUri.AbsolutePath == "/large")
                content.Headers.ContentLength = 5 * 1024 * 1024;
            return new HttpResponseMessage(HttpStatusCode.OK) { Content = content };
        });
        using var client = Client(http);
        Check(await client.GetPreviewAsync("https://preview.test/large", default) is null, "Büyük önizleme kabul edildi");
        Check(await client.GetPreviewAsync("https://preview.test/video", default) is null, "Video önizleme yerine indirildi");
        Equal(0, bodyReads);
        Check(await client.GetPreviewAsync("https://preview.test/chunked", default) is null, "Sınırsız önizleme okundu");
        Check(bodyReads > 0, "Parçalı içerik sınırı sınanmadı");
        Check(await client.GetPreviewAsync("file:///tmp/a.png", default) is null, "Yerel önizleme bağlantısı kabul edildi");
    }

    private static async Task DestinationErrorsAsync()
    {
        using var folder = new TemporaryFolder();
        using var http = Mock(_ => new HttpResponseMessage(HttpStatusCode.Forbidden));
        using var client = Client(http);
        var result = await client.DownloadAsync(Link, [File("blocked.mp4")], folder.Path, null, default);
        Equal(1, result.Failed);
        Check(result.Updates.Single().Error!.Contains("indirmeye izin"), "Engel hatası eksik");
        await ThrowsAsync<DiskException>(() => client.DownloadAsync(Link, [File("a.jpg")], System.IO.Path.Combine(folder.Path, "missing"), null, default));
        Equal(0, Directory.GetFiles(folder.Path).Length);
    }

    private static int ShowFixture(string[] args)
    {
        bool automatic = args.Contains("--ui-check");
        int exitCode = 0;
        int previewRequests = 0;
        using var automaticFolder = automatic && args.Length == 1 ? new TemporaryFolder() : null;
        string target = args.Length > 1 ? args[1] : automaticFolder?.Path ?? System.IO.Path.Combine(System.IO.Path.GetTempPath(), "Yandex-UI-Test");
        Directory.CreateDirectory(target);
        byte[] preview = CreatePreview();
        var resources = Enumerable.Range(0, 28).Select(i => Resource(
            i % 4 == 0 ? $"Video {i + 1}.mp4" : $"Görsel {i + 1}.jpg",
            $"/medya-{i}", i % 4 == 0 ? "video/mp4" : "image/jpeg",
            i % 4 == 0 ? null : "https://preview.test/thumb")).ToArray();
        var http = Mock(request =>
        {
            if (request.RequestUri!.Host == "preview.test")
            {
                Interlocked.Increment(ref previewRequests);
                var response = Bytes(preview);
                response.Content.Headers.ContentType = new("image/png");
                return response;
            }
            if (request.RequestUri.Host == "download.test")
                return Bytes(Payload);
            if (request.RequestUri.AbsolutePath.EndsWith("/download"))
            {
                if (Query(request, "path") == "/medya-3")
                    return new HttpResponseMessage(HttpStatusCode.Forbidden);
                return Json(new { href = "https://download.test/file" });
            }
            return Json(DirectoryResource(resources));
        });
        var app = new Application();
        XNamespace presentation = "http://schemas.microsoft.com/winfx/2006/xaml/presentation";
        var source = XDocument.Load(System.IO.Path.GetFullPath(System.IO.Path.Combine(AppContext.BaseDirectory, "../../../../App.xaml")));
        var styleResources = new XElement(presentation + "ResourceDictionary",
            new XAttribute(XNamespace.Xmlns + "x", "http://schemas.microsoft.com/winfx/2006/xaml"),
            source.Root!.Element(presentation + "Application.Resources")!.Elements());
        app.Resources = (ResourceDictionary)XamlReader.Parse(styleResources.ToString());
        var window = new MainWindow(Client(http));
        ((TextBox)window.FindName("LinkBox")).Text = Link;
        typeof(MainWindow).GetField("targetFolder", BindingFlags.Instance | BindingFlags.NonPublic)!.SetValue(window, target);
        ((TextBlock)window.FindName("FolderText")).Text = target;
        if (automatic)
        {
            app.Dispatcher.BeginInvoke(new Action(async () =>
            {
                try
                {
                    var list = (Button)window.FindName("ListButton");
                    var selected = (Button)window.FindName("SelectedButton");
                    var all = (Button)window.FindName("AllButton");
                    var gallery = (ListBox)window.FindName("Gallery");
                    var status = (TextBlock)window.FindName("StatusText");
                    list.RaiseEvent(new RoutedEventArgs(Button.ClickEvent));
                    await UntilAsync(() => all.IsEnabled);
                    window.UpdateLayout();
                    var checkboxes = VisualChildren<CheckBox>(window).ToArray();
                    Check(checkboxes.Length is > 0 and < 28, "Galeri sanallaştırılmıyor");
                    await UntilAsync(() => ((FileItem)checkboxes[0].DataContext).Thumbnail is not null);
                    Check(previewRequests < 21, "Görünmeyen önizlemeler alındı");
                    Console.WriteLine("PASS Galeri şablonu ve görünür önizlemeler");
                    checkboxes[0].IsChecked = true;
                    await UntilAsync(() => selected.IsEnabled);
                    selected.RaiseEvent(new RoutedEventArgs(Button.ClickEvent));
                    await UntilAsync(() => status.Text == "1 indirildi");
                    Equal(1, Directory.GetFiles(target).Length);
                    Console.WriteLine("PASS Arayüzden seçili indirme");
                    all.RaiseEvent(new RoutedEventArgs(Button.ClickEvent));
                    await UntilAsync(() => status.Text == "27 indirildi · 1 indirilemedi");
                    Equal(28, Directory.GetFiles(target).Length);
                    var rows = gallery.Items.Cast<FileRow>().SelectMany(r => r.Items).ToArray();
                    Equal(1, rows.Count(i => i.State == TransferState.Failed));
                    Equal(27, rows.Count(i => i.State == TransferState.Completed));
                    Console.WriteLine("PASS Arayüzden tümünü indirme ve dosya hatası");
                    ((TextBox)window.FindName("LinkBox")).Text = "geçersiz";
                    list.RaiseEvent(new RoutedEventArgs(Button.ClickEvent));
                    await UntilAsync(() => gallery.Items.Count == 0);
                    Check(!all.IsEnabled && !selected.IsEnabled, "Eski seçim kaldı");
                    Check(((ProgressBar)window.FindName("TotalProgress")).Visibility == Visibility.Collapsed, "Eski ilerleme kaldı");
                    Console.WriteLine("PASS Yeni bağlantıda durum temizliği");
                }
                catch (Exception ex)
                {
                    exitCode = 1;
                    Console.WriteLine($"FAIL Arayüz: {ex}");
                }
                finally
                {
                    window.Close();
                }
            }));
        }
        app.Run(window);
        http.Dispose();
        return exitCode;
    }

    private static async Task UntilAsync(Func<bool> predicate)
    {
        using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(10));
        while (!predicate())
            await Task.Delay(20, timeout.Token);
    }

    private static IEnumerable<T> VisualChildren<T>(DependencyObject parent) where T : DependencyObject
    {
        for (int index = 0; index < VisualTreeHelper.GetChildrenCount(parent); index++)
        {
            var child = VisualTreeHelper.GetChild(parent, index);
            if (child is T value)
                yield return value;
            foreach (var descendant in VisualChildren<T>(child))
                yield return descendant;
        }
    }

    private static byte[] CreatePreview()
    {
        var visual = new DrawingVisual();
        using (var drawing = visual.RenderOpen())
        {
            drawing.DrawRectangle(new LinearGradientBrush(Color.FromRgb(201, 228, 245), Color.FromRgb(84, 161, 184), 45), null, new Rect(0, 0, 424, 268));
            drawing.DrawEllipse(new SolidColorBrush(Color.FromRgb(246, 216, 144)), null, new Point(320, 65), 26, 26);
            var mountain = Geometry.Parse("M0,268 L126,80 L268,268 Z M180,268 L300,122 L424,268 Z");
            drawing.DrawGeometry(new SolidColorBrush(Color.FromRgb(48, 102, 121)), null, mountain);
        }
        var bitmap = new RenderTargetBitmap(424, 268, 96, 96, PixelFormats.Pbgra32);
        bitmap.Render(visual);
        var encoder = new PngBitmapEncoder();
        encoder.Frames.Add(BitmapFrame.Create(bitmap));
        using var buffer = new MemoryStream();
        encoder.Save(buffer);
        return buffer.ToArray();
    }

    private static MediaFile File(string name, string[]? segments = null, string? path = null) =>
        new(name, path ?? "/" + string.Join('/', segments ?? [name]), segments ?? [name], Payload.Length, name.EndsWith(".mp4"), null);

    private static Dictionary<string, object?> Resource(string name, string path, string? mime, string? preview = null) =>
        new() { ["type"] = "file", ["name"] = name, ["path"] = path, ["mime_type"] = mime, ["size"] = Payload.Length, ["preview"] = preview };

    private static object DirectoryItem(string name, string path) => new { type = "dir", name, path };

    private static object DirectoryResource(object[] items, int? total = null) =>
        new { type = "dir", name = "Dosyalar", path = "/", _embedded = new { items, total = total ?? items.Length } };

    private static HttpResponseMessage Json(object value) => new(HttpStatusCode.OK)
    {
        Content = new StringContent(JsonSerializer.Serialize(value), Encoding.UTF8, "application/json")
    };

    private static HttpResponseMessage Bytes(byte[] value) => new(HttpStatusCode.OK) { Content = new ByteArrayContent(value) };

    private static HttpResponseMessage RetryResponse()
    {
        var response = new HttpResponseMessage(HttpStatusCode.TooManyRequests);
        response.Headers.RetryAfter = new(TimeSpan.Zero);
        return response;
    }

    private static HttpClient Mock(Func<HttpRequestMessage, HttpResponseMessage> handler) =>
        new(new MockHandler(handler)) { Timeout = Timeout.InfiniteTimeSpan };

    private static HttpClient DownloadMock(Func<HttpRequestMessage, HttpResponseMessage> transfer) => Mock(request =>
        request.RequestUri!.Host == "cloud-api.yandex.net"
            ? Json(new { href = "https://download.test/file?path=" + Uri.EscapeDataString(Query(request, "path")!) })
            : transfer(request));

    private static DiskClient Client(HttpClient http) => new(http, TimeSpan.Zero);

    private static string? Query(HttpRequestMessage request, string key)
    {
        foreach (string part in request.RequestUri!.Query.TrimStart('?').Split('&'))
        {
            var pair = part.Split('=', 2);
            if (pair[0] == key && pair.Length == 2)
                return Uri.UnescapeDataString(pair[1]);
        }
        return null;
    }

    private static void Equal<T>(T expected, T actual)
    {
        if (!EqualityComparer<T>.Default.Equals(expected, actual))
            throw new Exception($"Beklenen: {expected}; gerçekleşen: {actual}");
    }

    private static void Check(bool valid, string message)
    {
        if (!valid)
            throw new Exception(message);
    }

    private static T Throws<T>(Action action) where T : Exception
    {
        try { action(); }
        catch (T ex) { return ex; }
        throw new Exception($"{typeof(T).Name} bekleniyordu");
    }

    private static async Task<T> ThrowsAsync<T>(Func<Task> action) where T : Exception
    {
        try { await action(); }
        catch (T ex) { return ex; }
        throw new Exception($"{typeof(T).Name} bekleniyordu");
    }

    private sealed class MockHandler(Func<HttpRequestMessage, HttpResponseMessage> handler) : HttpMessageHandler
    {
        protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken token)
        {
            token.ThrowIfCancellationRequested();
            return Task.FromResult(handler(request));
        }
    }

    private sealed class Capture<T>(Action<T>? action = null) : IProgress<T>
    {
        public ConcurrentBag<T> Values { get; } = [];
        public void Report(T value)
        {
            Values.Add(value);
            action?.Invoke(value);
        }
    }

    private sealed class TemporaryFolder : IDisposable
    {
        public string Path { get; } = System.IO.Path.Combine(System.IO.Path.GetTempPath(), "YandexTest-" + Guid.NewGuid().ToString("N"));
        public TemporaryFolder() => Directory.CreateDirectory(Path);
        public void Dispose() => Directory.Delete(Path, true);
    }

    private sealed class GeneratedStream(long size, TimeSpan delay = default, Action<int>? read = null, Action? closed = null) : Stream
    {
        private long position;
        private bool disposed;
        public override bool CanRead => true;
        public override bool CanSeek => false;
        public override bool CanWrite => false;
        public override long Length => size;
        public override long Position { get => position; set => throw new NotSupportedException(); }
        public override int Read(byte[] buffer, int offset, int count) => throw new NotSupportedException();
        public override async ValueTask<int> ReadAsync(Memory<byte> buffer, CancellationToken token = default)
        {
            if (delay > TimeSpan.Zero)
                await Task.Delay(delay, token);
            token.ThrowIfCancellationRequested();
            read?.Invoke(buffer.Length);
            int count = (int)Math.Min(buffer.Length, size - position);
            buffer.Span[..count].Fill(77);
            position += count;
            return count;
        }
        protected override void Dispose(bool disposing)
        {
            if (!disposed)
            {
                disposed = true;
                closed?.Invoke();
            }
            base.Dispose(disposing);
        }
        public override void Flush() => throw new NotSupportedException();
        public override long Seek(long offset, SeekOrigin origin) => throw new NotSupportedException();
        public override void SetLength(long value) => throw new NotSupportedException();
        public override void Write(byte[] buffer, int offset, int count) => throw new NotSupportedException();
    }
}

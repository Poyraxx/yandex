# Yandex

Herkese açık Yandex Disk bağlantılarındaki video ve görselleri Windows veya Android’de indirmek için küçük bir uygulama. Dosyaları galeriden seçebilir ya da bağlantının altındaki bütün medyayı tek seferde indirebilirsiniz.

Türkçe arayüz, karanlık tema. Geliştirici: [Poyrax](https://github.com/Poyraxx).

## İndir ve kur

| Platform | Dosya | Gereksinim |
| --- | --- | --- |
| Windows | [Yandex.exe](https://github.com/Poyraxx/yandex/releases/latest/download/Yandex.exe) | Windows 10 veya üzeri, 64 bit |
| Android | [Yandex.apk](https://github.com/Poyraxx/yandex/releases/latest/download/Yandex.apk) | Android 8.0 veya üzeri |

Bütün sürümler [Releases](https://github.com/Poyraxx/yandex/releases) sayfasında. Çalıştırmak için depodaki kaynak kodu indirmeniz gerekmez.

### Windows

`Yandex.exe` dosyasını indirin, istediğiniz klasöre koyun ve açın. Kurulum veya ayrıca .NET yüklemesi gerekmez. Uygulamayı kaldırmak için EXE dosyasını silebilirsiniz; indirdiğiniz dosyalar seçtiğiniz klasörde kalır.

### Android

`Yandex.apk` dosyasını telefona indirin ve açın. Android isterse, APK’yı açtığınız tarayıcı veya dosya uygulaması için **Bu kaynaktan izin ver** seçeneğini kullanıp kurulumu tamamlayın. Uygulama Play Store üzerinden dağıtılmıyor.

İndirme sırasında bildirim izni istenebilir. Bildirim üzerinden ilerlemeyi görebilir ve indirmeyi iptal edebilirsiniz. Dosyaların kaydedileceği klasörü Android’in klasör seçicisinden siz belirlersiniz; uygulama tüm depolamaya erişim izni istemez.

## Kullanım

1. Yandex Disk’teki paylaşım bağlantısını bağlantı alanına yapıştırın.
2. **Listele** düğmesine basın ve taramanın bitmesini bekleyin.
3. **Klasör seç** ile dosyaların kaydedileceği yeri belirleyin.
4. İstediğiniz dosyaları işaretleyip **Seçilenleri indir** düğmesine basın. Hepsi için **Tümünü indir** kullanın.
5. İşlemi durdurmak için **İptal** düğmesine basın.

Tek dosya bağlantıları ve iç içe klasörler desteklenir. Alt klasörlerdeki video ve görseller aynı galeride görünür; indirilirken klasör yapısı korunur. Diğer dosya türleri indirilmez.

Dosyalar dönüştürülmez. Aynı isimde bir dosya varsa mevcut dosyanın üzerine yazılmaz; yenisi `foto (1).jpg` gibi bir adla kaydedilir. En fazla iki dosya aynı anda indirilir. Büyük dosyalar doğrudan diske yazılır.

İptalde tamamlanan dosyalar kalır, yarım dosyalar temizlenir. Geçici bağlantı hataları sınırlı olarak tekrar denenir. Bir dosya indirilemezse diğerleri devam eder ve hata o dosyanın altında görünür. Bir klasör tamamen taranamazsa indirme düğmeleri açılmaz.

Küçük resimler yalnızca görünür dosyalar için alınır. Video önizlemesi yoksa video simgesi gösterilir; önizleme oluşturmak için video indirilmez.

Android’de uygulama arka plandayken başlayan indirme bildirimle devam eder. Uygulamanın zorla durdurulması, işlemin sistem tarafından kapatılması veya cihazın yeniden başlaması sonrasında indirme kaldığı yerden sürmez.

## Desteklenen bağlantılar

Paylaşımın herkese açık ve indirmeye izin veren bir Yandex Disk bağlantısı olması gerekir. Hesap girişi, özel veya şifreli paylaşımlar ve video oynatma bulunmaz.

Bağlantı kapalıysa, geçersizse ya da paylaşım sahibi indirmeyi engellediyse uygulama kısa bir hata gösterir. Klasöre yazma izni değiştiğinde **Klasör seç** ile klasörü yeniden seçin.

## Ekranlar

Windows ve Android ekranları örnek test dosyalarıyla alınmıştır.

![Windows](docs/windows.jpg)

<img src="docs/android.png" alt="Android" width="340">

## Kaynaktan derleme

### Windows

Windows üzerinde [.NET 10 SDK](https://dotnet.microsoft.com/download/dotnet/10.0) gerekir. Depo klasöründe çalıştırın:

```powershell
dotnet publish windows/Yandex.csproj -c Release -p:PublishProfile=Windows
```

Taşınabilir EXE, `windows/publish/Yandex.exe` konumuna çıkar.

### Android

Android Studio’da `android` klasörünü açın. Android SDK 37, Build Tools 36.0.0 ve JDK 17 veya üzeri gerekir. Gradle 9.6 ve Android Gradle Plugin 9.4 proje dosyalarında tanımlıdır. Windows’ta derleme için projeyi Türkçe karakter içermeyen bir yola çıkarın; örneğin `C:\Projects\yandex`.

Deneme APK’sı için `android` klasöründe çalıştırın:

```powershell
.\gradlew.bat :app:assembleDebug
```

Dosya `android/app/build/outputs/apk/debug/app-debug.apk` konumuna çıkar.

Dağıtım APK’sını kendi anahtarınızla imzalamak için `poyrax` takma adına sahip bir keystore oluşturun. Terminalde `YANDEX_KEYSTORE`, `YANDEX_STORE_PASSWORD` ve `YANDEX_KEY_PASSWORD` ortam değişkenlerini ayarlayıp aşağıdaki komutu çalıştırın:

```powershell
.\gradlew.bat :app:assembleRelease
```

Çıktı `android/app/build/outputs/apk/release/app-release.apk` olur. Keystore ve parolalar depoya dahil edilmez. Yayınlanan APK’nın üzerine güncelleme kurabilmek için aynı imza gerekir; kendi derlemenizin imzası farklıysa önce kurulu sürümü kaldırmanız gerekir.

## Testler

Windows kontrolleri:

```powershell
dotnet run --project windows/Tests/Yandex.Tests.csproj -c Release
dotnet run --project windows/Tests/Yandex.Tests.csproj -c Release -- --ui-check
```

Android kontrolleri, `android` klasöründen:

```powershell
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat :app:connectedDebugAndroidTest
```

Cihaz testleri için bağlı bir Android cihazı veya çalışan bir emülatör gerekir. Windows’ta 20 indirme/API kontrolü ve 4 arayüz kontrolü; Android’de 13 birim testi ve Android 8 ile Android 17 emülatörlerinde üçer cihaz testi geçti. İmzalı APK’nın her iki Android sürümünde kurulup açılması da doğrulandı.

Testler tek dosya, alt klasörler, sayfalama, dosya türü ayrımı, seçili ve toplu indirme, isim çakışması, kesilen bağlantı ve iptal davranışlarını örnek API yanıtlarıyla kontrol eder. Gerçek Yandex paylaşımıyla uçtan uca indirme bu sürümde henüz doğrulanmadı.

Listeleme ve indirme için Yandex Disk’in [REST API’si](https://yandex.com/dev/disk/api/reference/public.html) kullanılır. Uygulama Yandex’in resmi ürünü değildir.

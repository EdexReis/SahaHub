# SahaHub

Halı saha rezervasyon ve işletme yönetim sistemi. Java 25 + Spring Boot 4.1 + Thymeleaf + PostgreSQL.

> **Durum (1 Ekim 2026):** Aşama 1–6 tamamlandı: kimlik, işletme izolasyonu, müşteri rezervasyonu, personel
> takvimi, fiyatlandırma (kapora, kupon, ek hizmet, indirim), ödeme hareketleri, iade, kasa, düzenli
> (haftalık) rezervasyon, bekleme listesi, bildirimler, takımlar, oyuncu/rakip ilanları, lig, raporlar, yönetim
> ekranları (saha, saat, personel, denetim kaydı) ve parola sıfırlama. Açık kalanlar "Bilinen eksikler"de.
> Ayrıntı: [PROGRESS.md](PROGRESS.md).
>
> **Gerçek entegrasyon yoktur:** çevrim içi ödeme bir **simülasyondur** (kart bilgisi alınmaz, para çekilmez),
> "manuel POS" yalnızca slip tutarının elle girilmesidir. E-postalar yalnızca yerel Mailpit'e gider;
> SMS/WhatsApp **demo kanaldır** (mesaj gönderilmez, yalnızca kaydedilir).

## Neler çalışıyor?

| Kim | Ne yapabilir |
|---|---|
| Müşteri | Kayıt/giriş, saha listesi (şehir süzgeci), saha ayrıntısı, 14 günlük gün şeridi, uygun saatler ve fiyatı, saati 10 dk tutma, ek hizmet ve kupon ekleme, kaporayı/tamamını çevrim içi ödeme (simülasyon), havale bildirimi, rezervasyonlarım (yaklaşan / geçmiş), kurala uygun iptal (çevrim içi ödeme otomatik iade), yazdırılabilir özet, dolu saat için bekleme listesi (sıra numarası, boşalınca 15 dk'lık teklif), bildirim kutusu ve e-posta/SMS tercihleri, takım kurma ve davet bağlantısıyla katılma, oyuncu/rakip ilanı verme ve başvurma, ligleri izleme |
| Resepsiyon | Şube takvimi (ödeme etiketleriyle), boş saatten hızlı rezervasyon, önizlemeli düzenli (haftalık) rezervasyon ve "bu ve sonraki maçları iptal", taşıma, geldi / tamamlandı / gelmedi, gerekçeli iptal, nakit / manuel POS tahsilat, havale doğrulama, hatalı tahsilatı ters kayıtla düzeltme, kasa açma/kapama |
| Şube yöneticisi | Resepsiyonun yaptıkları + saha kapatma, iade, personel indirimi, gider kaydı, fiyat kuralları, ek hizmetler, kapora kuralı, lig (fikstür, maç planlama, skor), raporlar ve CSV, saha ekleme/düzenleme ve fotoğraf, çalışma saatleri ve özel günler |
| İşletme sahibi | İşletmenin tüm şubelerinde yukarıdakilerin hepsi + kuponlar, personel ve yetkiler, denetim kaydı, şube karşılaştırması |
| Platform yöneticisi | İşletmeleri listeleme, gerekçeyle askıya alma/etkinleştirme (denetim kaydına yazılır), demo SMS/WhatsApp mesaj kutusu |

## Gereksinimler

| Araç | Sürüm | Not |
|---|---|---|
| JDK | **25 (LTS)** | Spring Boot 4.1.1 Java 17–26 destekler. **JDK 27 desteklenmez.** Bilgisayarınızda `C:\Program Files\Java\jdk-25.0.4.1` kurulu; JDK 27'yi kaldırmanız gerekmez. |
| Docker Desktop | 29.x | PostgreSQL 18.6 ve Mailpit konteynerleri |
| Maven | — | Kurmanız gerekmez; proje Maven Wrapper (`mvnw.cmd`, Maven 3.9.16) içerir |
| Git | herhangi | |

## Windows PowerShell ile çalıştırma

```powershell
# 1) Bu oturum için JDK 25'i seç (JDK 27 kurulu kalır)
$env:JAVA_HOME = "C:\Program Files\Java\jdk-25.0.4.1"

# 2) Ortam dosyasını oluştur; DB_PASSWORD ve PAYMENT_SIM_WEBHOOK_SECRET değerlerini değiştir
Copy-Item .env.example .env
notepad .env

# 3) PostgreSQL ve Mailpit'i başlat (Docker Desktop açık olmalı)
docker compose up -d

# 4) Uygulamayı geliştirme profiliyle başlat (demo veri otomatik oluşur)
$env:SPRING_PROFILES_ACTIVE = "dev"
.\mvnw.cmd spring-boot:run
```

Tarayıcıda: <http://localhost:8080>. Durdurmak için `Ctrl+C`; veritabanını durdurmak için `docker compose down`.

JDK 25'i kalıcı varsayılan yapmak isterseniz (yeni açılan terminallerde geçerli olur):

```powershell
[Environment]::SetEnvironmentVariable("JAVA_HOME", "C:\Program Files\Java\jdk-25.0.4.1", "User")
```

### Demo hesaplar (yalnızca `dev` profili)

Hepsinin parolası: `SahaHub.demo1`. Tüm kişi, işletme, adres ve telefonlar kurgusaldır.

| E-posta | Rol |
|---|---|
| `musteri@sahahub.test` | Müşteri (Deniz Arslan) |
| `kaptan@sahahub.test` | Müşteri (Emre Yıldız) |
| `uzun.isim@sahahub.test` | Müşteri, uzun isim testi için |
| `resepsiyon.kadikoy@yesilvadi.test` | Yeşilvadi · Kadıköy resepsiyon |
| `mudur.kadikoy@yesilvadi.test` | Yeşilvadi · Kadıköy şube yöneticisi |
| `sahip@yesilvadi.test` | Yeşilvadi işletme sahibi (2 şube) |
| `sahip@kuzeyhali.test` | Kuzey Halı Saha sahibi (ayrı işletme) |
| `admin@sahahub.test` | Platform yöneticisi |

Demo veri `dev` profili dışında **hiç oluşturulmaz** (`DemoDataSeeder` sınıfı `@Profile("dev")`). Demo veriyi sıfırlamak için:

```powershell
docker compose exec postgres psql -U sahahub -d sahahub -c "drop schema public cascade; create schema public;"
```

## Eclipse ile çalıştırma

1. **Window → Preferences → Java → Installed JREs → Add…** → `C:\Program Files\Java\jdk-25.0.4.1` ekleyin ve işaretleyin.
   Eclipse sürümünüz Java 25'i tanımıyorsa Eclipse'i güncelleyin (2025-09 veya sonrası).
2. **File → Import → Maven → Existing Maven Projects** → proje klasörünü seçin.
3. `docker compose up -d` ile veritabanını başlatın (PowerShell'den).
4. `SahaHubApplication.java` → sağ tık → **Run As → Java Application**. İlk çalıştırmadan sonra
   **Run → Run Configurations → Environment** sekmesine `SPRING_PROFILES_ACTIVE = dev` ekleyin.
   `.env` dosyası proje kök klasöründen otomatik okunur.
5. Veritabanı kurmadan denemek için: `src/test/java/com/sahahub/TestSahaHubApplication.java` → Run As →
   Java Application (Testcontainers ile geçici PostgreSQL açar; demo veri için yine `dev` profili gerekir).

## Testler

```powershell
# Birim + entegrasyon testleri (Testcontainers ile gerçek PostgreSQL; Docker açık olmalı)
.\mvnw.cmd test

# Uçtan uca tarayıcı testleri (Playwright). Yerel Edge ile, tarayıcı indirmeden:
.\mvnw.cmd -Pe2e test "-De2e.channel=msedge"
```

E2E testleri ekran görüntülerini `target/screenshots/` altına telefon (390×844) ve masaüstü (1366×900)
boyutlarında kaydeder. Son çalıştırma sonuçları için [PROGRESS.md](PROGRESS.md).

## Belgeler

| Belge | İçerik |
|---|---|
| [PROGRESS.md](PROGRESS.md) | Güncel durum, kararlar, sonraki iş |
| [DESIGN.md](DESIGN.md) | Arayüz tasarım kararları |
| [docs/MIMARI.md](docs/MIMARI.md) | Modüller, katmanlar, çakışma güvencesi, ER diyagramı |
| [docs/YETKI_MATRISI.md](docs/YETKI_MATRISI.md) | Rol / izin matrisi |
| [docs/DURUM_GECISLERI.md](docs/DURUM_GECISLERI.md) | Rezervasyon durum makinesi, fiyat ve iptal kuralları |
| [docs/RAPORLAR.md](docs/RAPORLAR.md) | Rapor metriklerinin tanımları, doluluk paydası, CSV |
| [docs/ODEME.md](docs/ODEME.md) | Ödeme durumu, hareketler, idempotency, webhook, geç ödeme, kasa |
| [docs/YEDEKLEME.md](docs/YEDEKLEME.md) | Yedekleme ve geri yükleme |
| [docs/DEMO_SENARYOSU.md](docs/DEMO_SENARYOSU.md) | Adım adım deneme senaryosu |
| [LEARNING_GUIDE_TR.md](LEARNING_GUIDE_TR.md) | Projeyi öğrenmek için rehber |
| [CV_PROJECT_TR.md](CV_PROJECT_TR.md) | CV metni ve mülakat soruları |

## Bilinen eksikler ve gerçek/demo ayrımı

- **Çevrim içi ödeme simülasyondur.** `SimulatedPaymentProvider` gerçek para çekmez; kart bilgisi alınmaz/saklanmaz.
  Gerçek sağlayıcı `PaymentProvider` arayüzüyle eklenebilir (bkz. [docs/ODEME.md](docs/ODEME.md)).
- **Manuel POS bir entegrasyon değildir**; personel slip tutarını elle girer.
- Özet belgesi **fatura değildir**; e-fatura/muhasebe entegrasyonu yoktur.
- **E-posta yalnızca Mailpit'e** (http://localhost:8025) gider; gerçek bir SMTP sunucusu yapılandırılmadı.
  Canlıda `MAIL_HOST`/`MAIL_SMTP_PORT` ve gönderici alan adı (SPF/DKIM) ayrı bir karar ve adımdır.
- **SMS/WhatsApp demo kanaldır.** Gerçek sağlayıcı ücretlidir ve gerçek kişilere ulaşır; bilinçli olarak eklenmedi.
- Saha, çalışma saati ve personel **yönetim ekranları yok**; bu veriler şimdilik demo veriyle gelir.
- Parola sıfırlama e-postası yerelde Mailpit'e gider; canlıda gerçek SMTP ayrı bir adımdır.
- Eleme usulü turnuva (kupa), takım maçlarına katılım (geliyor/gelmiyor), takım logosu ve takım maç geçmişi yok
  (istek §8–9; bkz. PROGRESS.md).
- Saha fotoğrafları `./data/uploads` klasöründe; yedeklemede bu klasör de alınmalı (bkz. docs/YEDEKLEME.md).
- Giriş hız sınırı bellekte tutulur (tek sunucu için yeterli).
- CI iş akışı (`.github/workflows/ci.yml`) yazıldı ama bir GitHub deposunda **henüz çalıştırılmadı**.

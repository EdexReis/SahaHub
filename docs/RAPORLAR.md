# Rapor metrikleri

Kaynak: `reporting/domain/ReportCalculator.java` (test: `ReportCalculatorTest`, `ReportIT`). Bu belge ile kod
aynı tanımı kullanır; biri değişirse diğeri de güncellenir.

Ekran: İşletme paneli → **Raporlar** (sahip ve şube yöneticisi, izin `REPORT_VIEW`). Şubeler arası
karşılaştırma yalnızca işletme sahibine açıktır. Varsayılan aralık bu ayın başından bugüne; en fazla 366 gün.

## Üç para kavramı

| Kavram | Tanım | Ne değildir |
|---|---|---|
| **Rezervasyon bedeli** | Aralıktaki onaylı maçların (`CONFIRMED`, `COMPLETED`, `NO_SHOW`) toplam fiyatı | Kasaya giren para değildir; alacak doğurur |
| **Tahsilat** | Başarılı ödeme hareketleri, **ödeme tarihine** göre | Maçın tarihine göre değildir |
| **Nakit farkı** | Net tahsilat − giderler | **Kâr değildir**: vergi, maaş, kira, amortisman sistemde yoktur |

## Tarih ataması

- Rezervasyon metrikleri maçın **iş gününe** göre sayılır. Şube 01:00'de kapanıyorsa Salı 00:30'daki maç
  Pazartesi'ye yazılır (takvimdeki gibi). Çalışma saatleri sonradan değişmiş ve maç hiçbir pencereye
  sığmıyorsa takvim günü kullanılır.
- Tahsilat ve giderler kaydın **şube saatindeki takvim gününe** göre sayılır.

## Metrikler

### 1. Tahsilat (günlük / haftalık / aylık)

- Satırlar: nakit, POS (manuel kayıt), havale, çevrim içi (simülasyon), iade, ters kayıt.
- **Brüt tahsilat** = dört yöntemin toplamı. **Net tahsilat** = brüt − iadeler − ters kayıtlar.
- Yalnızca `SUCCEEDED` hareketler; bekleyen havale ve başarısız ödemeler sayılmaz.
- Haftalar pazartesi başlar. İlk ve son dönem, seçilen aralıkla kırpılır. Hareketsiz günler sıfır satırdır.

### 2. Rezervasyon bedeli, alacak, iade

- **Kalan alacak**: her onaylı maç için `max(0, bedel − o maça alınmış net tahsilat)` toplamı. Tahsilatın
  tarihi önemli değildir (maçtan önce alınan kapora da sayılır).
- **İade bekleyen**: onaylı maçlardaki fazla ödeme + iptal edilmiş/süresi dolmuş kayıtlarda kalan para.

### 3. Doluluk oranı (saha başına)

```
satılabilir süre = çalışma saatleri (aralıktaki iş günleri) − bakım/etkinlik kapatmaları
doluluk           = (onaylı maçların oyun süresi + lig maçları) / satılabilir süre
hazırlık dahil    = (oyun süresi + lig maçları + hazırlık süreleri) / satılabilir süre
```

- **Çalışma saatleri**: kapalı günler ve özel kapalı günler paydaya girmez.
- **Bakım/etkinlik kapatmaları** paydadan düşülür (satılamayan süredir); yalnızca çalışma saatleriyle kesişen
  kısmı düşülür.
- **Hazırlık süresi** (maç sonrası temizlik/hazırlık) paydan da paydadan da **düşülmez**; ayrı sütunda ve
  "hazırlık dahil" oranında gösterilir. Böylece hazırlık süresi uzun sahanın doluluğu yapay olarak
  şişmez ya da düşmez.
- Geçici tutmalar (`HELD`), iptaller ve süresi dolanlar sayılmaz. Payda 0 ise oran "—" gösterilir.

### 4. Yoğun saatler

Haftanın günü × başlangıç saati başına onaylı maç sayısı. Gece yarısından sonraki maçlar iş gününün satırında
görünür; sütunlar 06:00 → 23:00 → 00:00 → 05:00 sırasındadır. Hücrede sayı yazar; gölge yalnızca destekleyicidir
(renk tek başına anlam taşımaz).

### 5. İptal ve gelmeme oranları

- **İptal oranı** = onaylandıktan sonra iptal edilen / aralıkta onaylanmış maç (iptaller dahil). Müşteri ve şube
  iptalleri ayrı sayılır (iptal eden kişi = müşteri ise müşteri iptali).
- Onaylanmadan bırakılan veya süresi dolan **geçici tutmalar** orana girmez; ayrıca gösterilir.
- **Gelmeme oranı** = gelmedi / (tamamlandı + gelmedi). Durumu girilmemiş geçmiş maçlar orana girmez,
  uyarı olarak sayılır.
- Not: `confirmed_at` sütunu Aşama 6'da eklendi. Eski kayıtlarda çevrim içi müşterinin iptal ettiği
  rezervasyonların onay anı bilinmiyor; bu kayıtlar "bırakılan tutma" sayılır (V5 migration açıklaması).

### 6. Tekrar gelen müşteriler

Aralıkta en az bir onaylı maçı olan **kayıtlı** müşterilerden en az iki maç yapanların oranı. Misafir (hesapsız)
rezervasyonlar kişi olarak eşleştirilemediği için sayılmaz; sayıları ayrıca gösterilir. Ekranda en çok gelen 10
kişi listelenir (yalnızca sahip ve şube yöneticisi görür).

### 7. Şube ve saha karşılaştırması

Saha tablosu: satılabilir süre, rezervasyon/lig/hazırlık süreleri, doluluk, maç sayısı, rezervasyon bedeli.
Şube karşılaştırması (işletme sahibi): maç sayısı, rezervasyon bedeli, net tahsilat, gider, doluluk, iptal oranı.

### 8. Gelir / gider özeti

Net tahsilat, kategori bazında giderler (ters kayıtlar düşülmüş) ve nakit farkı.

## CSV dışa aktarma

- Bölümler: `tahsilat`, `sahalar`, `yogun-saatler`, `musteriler`. Aynı tarih filtresi uygulanır.
- Biçim: UTF-8 (BOM'lu), ayraç `;`, ondalık `,` — Türkçe Excel'de doğrudan açılır.
- **Formül enjeksiyonu koruması**: `=`, `+`, `-`, `@`, sekme veya satır başıyla başlayan metin hücrelerinin
  başına `'` eklenir (ör. `=HYPERLINK(...)` adıyla kaydedilmiş bir müşteri). Sayılar bu korumadan geçmez,
  eksi tutarlar bozulmaz (`CsvWriterTest`).

## Bilinen sınırlar

- Para birimi tek (TRY) varsayılır.
- Raporlar her açılışta hesaplanır (önbellek yok); 366 günlük bir şube için birkaç bin satırla sınırlı kalır.
- Hazırlık süresi lig maçlarında yoktur.

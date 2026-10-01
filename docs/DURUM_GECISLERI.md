# Rezervasyon durumları, fiyat ve iptal kuralları

## Rezervasyon durum makinesi

```mermaid
stateDiagram-v2
    [*] --> HELD: müşteri saati tutar
    [*] --> CONFIRMED: personel kaydı açar
    HELD --> CONFIRMED: müşteri onaylar (tutma süresi içinde)
    HELD --> CANCELLED: müşteri bırakır / personel iptal eder
    HELD --> EXPIRED: tutma süresi doldu (zamanlanmış görev veya onay denemesi)
    CONFIRMED --> COMPLETED: maç başladıktan sonra
    CONFIRMED --> NO_SHOW: maç başladıktan sonra, geliş kaydı yoksa
    CONFIRMED --> CANCELLED: müşteri (süre içinde) / personel (maç bitene kadar)
    CANCELLED --> [*]
    EXPIRED --> [*]
    COMPLETED --> [*]
    NO_SHOW --> [*]
```

| Durum | Ekrandaki ad | Sahayı meşgul eder mi? |
|---|---|---|
| `HELD` | Geçici tutuluyor | Evet |
| `CONFIRMED` | Onaylandı | Evet |
| `COMPLETED` | Tamamlandı | Evet (geçmiş zaman) |
| `NO_SHOW` | Gelmedi | Evet (geçmiş zaman) |
| `CANCELLED` | İptal edildi | Hayır (`active=false`) |
| `EXPIRED` | Süresi doldu | Hayır (`active=false`) |

"Geldi" bir durum değil, `CONFIRMED` rezervasyondaki `checked_in_at` alanıdır (maçtan en fazla 1 saat önce
ile maç bitişi arasında kaydedilir).

Kurallar `Reservation` sınıfındadır; geçersiz geçiş, kod nereden çağrılırsa çağrılsın reddedilir
(`ReservationLifecycleTest`). Personel panelindeki düğmeler aynı sınıfın `canCheckIn(now)` gibi
sorgularından üretilir; geçersiz düğme gösterilmez.

**Ödeme durumu rezervasyon durumundan ayrıdır** ve hareketlerden hesaplanır (ör. "Onaylandı + Kapora
bekleniyor"). Ayrıntı: [ODEME.md](ODEME.md).

Şubede kapora kuralı varsa müşterinin geçici tutması **kapora ödemesiyle** onaylanır (ödeme bildirimi
HELD → CONFIRMED geçişini yapar); ödemesiz "onayla" düğmesi gösterilmez ve sunucuda da reddedilir.

## Süresi dolan tutmalar

- Tutma süresi şube ayarıdır (`branch.hold_minutes`, varsayılan 10 dk).
- `HoldExpiryJob` her 30 sn `HoldExpiryService.expireDueHolds()` çağırır:
  `status='HELD' AND hold_expires_at <= now` satırları `FOR UPDATE SKIP LOCKED` ile 100'erli kilitlenir,
  EXPIRED yapılır, doluluk kapatılır. Tekrar çalıştırmak güvenlidir.
- Müşteri süre dolduktan sonra onaylamaya çalışırsa rezervasyon o anda EXPIRED yapılır (görevi beklemeden).
- Sınır: `hold_expires_at` anı dahil süre dolmuş sayılır.

## Düzenli rezervasyon (seri)

- Seri, haftalık tekrar eden maçların **kuralıdır** (`reservation_series`: ilk tarih, saat, süre, adet).
  Her maç ayrı bir `reservation` satırıdır (`series_id`, `series_index`); kendi durumu, fiyatı ve ödemesi vardır.
- Adet 2 ile `sahahub.booking.series-max-occurrences` (varsayılan 26) arasındadır.
- Önizlemede her tarih: **Uygun**, **Dolu** (rezervasyon veya bakım kapatması), **Şube kapalı**, **Geçmiş**.
- "Tümünü oluştur" yalnızca hepsi uygunsa çalışır. Değilse personel tarihleri tek tek işaretler
  ("Yalnızca seçili tarihleri oluştur"); işaretlenmeyen tarih sessizce atlanmaz.
- Oluşturma tek transaction'dır. Önizlemeden sonra bir tarih dolmuşsa hiçbir maç oluşmaz; personel güncel
  önizlemeyi görür (`SeriesIT.concurrentSeriesNeverLeavesPartialSeries`).
- "Bu ve sonraki maçları iptal et": seçilen maç ve sonrasındaki açık (HELD/CONFIRMED) maçlar iptal edilir;
  önceki maçlar etkilenmez. Gerekçe zorunlu.

## Bekleme listesi

```mermaid
stateDiagram-v2
    [*] --> WAITING: müşteri dolu saat için sıraya girer
    WAITING --> OFFERED: saat boşaldı, sıradaki ilk kişi (adına HELD rezervasyon)
    WAITING --> LEFT: müşteri sıradan çıkar
    WAITING --> EXPIRED: saat geçti, teklif açılamadı
    OFFERED --> ACCEPTED: müşteri tutulan saati onaylar
    OFFERED --> EXPIRED: teklif süresi doldu / tutma iptal edildi (sıradakine geçilir)
    ACCEPTED --> [*]
    LEFT --> [*]
    EXPIRED --> [*]
```

- Yalnızca **dolu** saat için sıraya girilir; boş saat için doğrudan rezervasyon yapılır.
- Bir müşteri aynı saat için bir kez, aynı anda en fazla 5 saat için sırada olabilir.
- Sıra, sıraya giriş zamanına göredir. Teklif süresi `sahahub.waitlist.offer-minutes` (varsayılan 15 dk).
- Teklif sırasında saat, teklif alan kişi adına HELD durumdadır; başkası (sıradaki dahil) alamaz.
  Süre dolunca mevcut tutma süresi görevi saati serbest bırakır ve sıradakine yeni teklif açılır.
- Aynı saat için en fazla bir açık teklif olur (`ux_waitlist_one_offer`); eşzamanlı teklif denemelerinden
  yalnızca biri başarılı olur (`WaitlistIT.concurrentOfferAttemptsCreateExactlyOneOffer`).

## Bildirimler

| Olay | Bildirim | Tekrar anahtarı |
|---|---|---|
| Rezervasyon onaylandı (seri dışı) | "Rezervasyonunuz onaylandı" | `confirmed:{id}` |
| Rezervasyon iptal edildi | "Rezervasyonunuz iptal edildi" (kim iptal etti) | `cancelled:{id}` |
| Seri oluşturuldu | Tek bildirim (maç sayısı, ilk tarih) | `series:{id}` |
| Bekleme teklifi | "Beklediğiniz saat boşaldı" + son onay saati | `offer:{kayıt}` |
| Maça 24 saatten az | "Maç hatırlatması" | `reminder:{id}` |
| Maça 48 saatten az, kapora ödenmemiş | "Kapora bekleniyor" | `deposit-reminder:{id}` |

Uygulama içi bildirim her zaman yazılır. E-posta (varsayılan açık) ve SMS (varsayılan kapalı, telefon
gerekli) müşterinin tercihidir. Misafir rezervasyonunda telefon varsa yalnızca demo SMS oluşur.

## Fiyat hesabı

1. Süre dakika dakika ele alınır; her dakikaya, şubenin yerel saatinde o dakikanın başlangıcına uyan
   kural uygulanır. 17:30–18:30 maçında 18:00 akşam tarifesi son 30 dakikaya uygulanır.
2. Birden fazla kural uyarsa **en yüksek `priority`**, eşitlikte **daha yeni (büyük id)** kural kazanır.
   Hiçbiri uymazsa sahanın standart saatlik ücreti.
3. Aynı ücretli ardışık dakikalar tek kalem olur.
4. Kalem tutarı = saatlik ücret × dakika ÷ 60, **kuruşa HALF_UP** yuvarlanır. Toplam = kalemlerin toplamı.
5. Hazırlık süresi ücretlendirilmez. Para birimi TRY; tüm hesaplar `BigDecimal`.
6. Kalemler `reservation_price_line` tablosuna kopyalanır; tarife sonradan değişse de rezervasyon değişmez.
7. **Taşıma** fiyatı değiştirmez (anlık görüntü korunur). Fark gerekiyorsa personel indirimi veya ek
   hizmet kalemiyle düzeltilir.

Saha ücretinin üzerine sırasıyla: ek hizmetler → kupon → personel indirimi (gerekçeli); kapora son
toplamdan hesaplanır. Ayrıntı ve yuvarlama: [ODEME.md §2](ODEME.md).

## İptal sınırı

Müşteri onaylı rezervasyonu **maç başlangıcı − iptal süresi** anına kadar (bu an dahil) iptal edebilir.
24 saat kuralında 2 Ekim 21:00 maçı için 1 Ekim 21:00:00 hâlâ iptal edilebilir, 21:00:01 edilemez
(`ReservationRulesIT.CancellationCutoff`). Sonrasında iptali personel yapar (gerekçe zorunlu).

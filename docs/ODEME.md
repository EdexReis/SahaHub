# Ödeme, iade ve kasa

> **Gerçek/demo ayrımı:** Bu sürümde gerçek bir ödeme sağlayıcısı, POS cihazı veya banka entegrasyonu
> **yoktur**. Çevrim içi ödeme `SimulatedPaymentProvider` ile taklit edilir (kart bilgisi istenmez, para
> çekilmez). "Manuel POS" yalnızca personelin slip tutarını elle girmesidir. Üretilen belge
> "rezervasyon/ödeme özeti"dir; fatura veya mali belge değildir.

## 1. Ödeme durumu rezervasyon durumundan ayrıdır

| Rezervasyon durumu (`reservation.status`) | Ödeme durumu (hesaplanır, saklanmaz) |
|---|---|
| Geçici tutuluyor, Onaylandı, Tamamlandı, Gelmedi, İptal edildi, Süresi doldu | Ödeme yok, Ödenmedi, Kapora bekleniyor, Kısmi ödendi, Ödendi, İade edilecek |

Ödeme durumu her seferinde hareketlerden hesaplanır (`PaymentSummary`):

- **Ödenmesi gereken (due)**: iptal/süresi dolmuşsa 0, aksi hâlde rezervasyon toplamı.
- **Ödenen (paid)** = başarılı tahsilatlar − başarılı iadeler − ters kayıtlar.
- **Bekleyen** = sonucu belli olmayan tahsilatlar (havale bildirimi, çevrim içi ödeme). Ödenene sayılmaz.

| Koşul | Durum |
|---|---|
| ödenen > ödenmesi gereken | İade edilecek |
| ödenmesi gereken = 0 | Ödeme yok |
| ödenen = ödenmesi gereken | Ödendi |
| kapora > 0 ve ödenen < kapora | Kapora bekleniyor |
| ödenen > 0 | Kısmi ödendi |
| diğer | Ödenmedi |

## 2. Fiyat sırası ve kapora

1. Saha ücreti (rezervasyon anında kopyalanmış, değişmez kalemler)
2. Ek hizmetler (adet × eklendiği andaki birim fiyat)
3. **Kupon** (en fazla bir; ara toplamın yüzdesi veya sabit tutar, ara toplamı aşamaz)
4. **Personel indirimi** (kupondan sonra kalan üzerinden; gerekçe zorunlu; denetim kaydına yazılır)
5. **Kapora** son toplam üzerinden: sabit (toplamı aşamaz) veya yüzde (kuruşa HALF_UP).
   Kapora kuralı rezervasyon anında rezervasyona kopyalanır; şube kuralı sonradan değişse de etkilenmez.

Kalemler hiçbir zaman silinmez; kaldırılan ek hizmet/indirim `voided_at` ile işaretlenir. Kupon kullanım
sayacı tek bir koşullu `UPDATE` ile artırılır (`used_count < max_uses`), iptal/süre dolumunda geri verilir.

## 3. Ödeme hareketleri

| Tür | Anlamı | Ödenene etkisi |
|---|---|---|
| CHARGE (Tahsilat) | Müşteriden alınan para | + |
| REFUND (İade) | Müşteriye geri verilen para | − |
| REVERSAL (Ters kayıt) | Yanlış girilen nakit/POS tahsilatının düzeltmesi | − |

Kurallar:

- Hareket **silinmez**, tutarı **değiştirilmez**. Durum yalnızca bir kez `PENDING → SUCCEEDED/FAILED` olur.
- Her istek bir **idempotency anahtarı** taşır (formlarda gizli alan, `payment.idempotency_key UNIQUE`).
  Çift tıklama veya geri tuşuyla aynı form yeniden gönderilirse ikinci hareket oluşmaz.
- Tahsilat kalan borçtan fazla olamaz. İade, ilgili tahsilattan "iade edilebilir" tutardan fazla olamaz
  (bekleyen iadeler de düşülür). Her işlem rezervasyon satırını kilitlediği için eşzamanlı iki iade
  aynı parayı iki kez iade edemez (`PaymentIT.concurrentRefundsCannotExceedTheCharge`).
- Bir tahsilat en fazla bir kez ters çevrilir (kısmi tekil indeks); iade yapılmış tahsilat ters çevrilemez.

## 4. Çevrim içi ödeme (simülasyon) akışı

```mermaid
sequenceDiagram
    participant M as Müşteri
    participant A as SahaHub
    participant S as Simülasyon sağlayıcı
    M->>A: Kaporayı öde (anahtar)
    A->>A: payment PENDING (idempotency_key)
    A->>S: createCharge(anahtar, tutar)
    S-->>A: providerRef, ödeme sayfası
    A-->>M: sağlayıcı sayfasına yönlendir
    M->>S: sonucu seç (başarılı/başarısız/gecikmeli/yinelenen)
    S->>A: imzalı webhook (eventId, providerRef, sonuç)
    A->>A: payment_event ON CONFLICT DO NOTHING
    A->>A: ödeme sonuçlanır; rezervasyon HELD → CONFIRMED
```

- Webhook gövdesi **HMAC-SHA256** ile imzalanır (`PAYMENT_SIM_WEBHOOK_SECRET`). İmzası tutmayan istek 401.
- `/webhooks/**` CSRF dışıdır (sunucudan sunucuya gelir); oturum yerine imzayla korunur.
- Aynı olay ikinci kez gelirse (`(provider, event_id)` tekil) hiçbir şey değişmez ve 200 döner.

### Senaryolar (simülasyon sayfasında seçilir)

| Senaryo | Sonuç |
|---|---|
| Ödeme başarılı | Rezervasyon onaylanır |
| Ödeme başarısız | Tutma devam eder, yeniden denenebilir |
| Bildirim gecikmeli | Bildirim 60 sn sonra gelir (`sahahub.payment.simulation.delay`) |
| Bildirim iki kez | Aynı olay iki kez teslim edilir; bir kez işlenir |
| İadesi başarısız | Bu ödemenin iadeleri sağlayıcıda reddedilir |

### Geç gelen ödeme (telafi kuralı)

Tutma süresi dolduktan (veya rezervasyon iptal edildikten) sonra başarılı ödeme bildirimi gelirse:

1. Para alınmış kabul edilir (tahsilat SUCCEEDED) — sağlayıcıyla tutarlılık bozulmaz.
2. Rezervasyon **onaylanmaz** (saat başkasına verilmiş olabilir).
3. Commit sonrası otomatik **tam iade** başlar (sabit anahtar `auto-refund-{id}`: iki kez tetiklense de tek iade).
4. İade başarısız olursa FAILED iade hareketi kalır; personel takviminde **"İade başarısız"** uyarısı çıkar,
   panelden yeniden iade denenir.

Müşteri onaylı rezervasyonunu süresi içinde iptal ederse çevrim içi ödemeleri de aynı yolla otomatik iade
edilir. Personel iptalinde iade kararı personeldedir (panelde "İade edilecek" görünür).

## 5. Havale/EFT

Müşteri (onaylı rezervasyonunda) veya personel havale bildirimi girer → PENDING, ödenene sayılmaz →
personel hesabı kontrol edip **doğrular** (SUCCEEDED) veya gerekçeyle **reddeder** (FAILED).

## 6. Kasa

- Şube başına aynı anda **tek açık kasa** (kısmi tekil indeks). Nakit tahsilat/iade/ters kayıt açık kasa ister.
- Kasada olması gereken = açılış parası + nakit tahsilat − nakit iade − nakit ters kayıt − kasadan giderler.
- Kapanışta sayılan tutar girilir; fark (fazla/açık) kaydedilir ve denetim kaydına yazılır.
- Nakit hareketi ile kapanış aynı kasa satırını kilitler: kapanışla eşzamanlı bir nakit hareket ya kapanıştan
  önce kasaya girer ya da "kasayı açın" hatası alır.
- POS, havale ve çevrim içi ödemeler kasadaki nakdi etkilemez; kasa ekranında ayrı gösterilir.
- Hatalı gider silinmez; aynı tutarın ters kaydı girilir.

## 7. Kavramlar karıştırılmaz

| Kavram | Nerede | Anlamı |
|---|---|---|
| Rezervasyon bedeli | Takvim özeti "Rezervasyon bedeli" | Açık/tamamlanmış rezervasyonların toplam tutarı; tahsil edilmiş olmayabilir |
| Tahsilat | Kasa ekranı "Bugünkü tahsilatlar (net)" | O gün gerçekten alınan para (iadeler ve ters kayıtlar düşülmüş) |
| Kâr | — | Bu sürümde hesaplanmıyor (gelir/gider özeti Aşama 6'da) |

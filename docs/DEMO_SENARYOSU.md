# Demo senaryosu (yaklaşık 10 dakika)

Ön koşul: README'deki gibi `dev` profiliyle çalışan uygulama. Parola: `SahaHub.demo1`.

## 1. Müşteri: saat seç ve onayla

1. <http://localhost:8080> → "Saha seç" listesi. **İstanbul** süzgecine tıklayın.
2. **Saha 1 · Kapalı** → gün şeridinden **Yarın**. Akşam saatlerinin fiyatının farklı olduğuna
   (hafta içi akşam tarifesi) ve **Gece yarısından sonra** bölümüne dikkat edin.
3. Bir saate tıklayın → giriş sayfası → `musteri@sahahub.test` ile girin → saati onaylama sayfası.
4. **Saati tut** → geri sayım ve fiyat kalemleri görünür → **Rezervasyonu onayla**.
5. Üst menü → **Rezervasyonlarım**: yeni rezervasyon "Yaklaşan" listesinde.

## 2. Çakışma: aynı saat iki kişiye verilmez

1. İkinci bir tarayıcı (veya gizli pencere) açın, `kaptan@sahahub.test` ile girin.
2. İki pencerede de aynı saha ve günü açın. İlk pencerede bir saati tutun.
3. İkinci pencerede (sayfayı yenilemeden) **aynı saate** tıklayın → "Bu saat artık uygun değil" mesajı.

## 3. Resepsiyon: telefonla gelen müşteri

1. Çıkış → `resepsiyon.kadikoy@yesilvadi.test` ile girin → İşletme paneli (bugünün takvimi).
2. Akşam saatleri dolu, uzun isimli müşteri kesilerek gösterilir. Mini Saha'da 14:00–17:00 bakım bloğu ve
   maçlardan sonra "hazırlık" şeridi görünür.
3. Kesik çizgili bir **+ Boş** kutusuna tıklayın → sağ panelde form saha ve saatle dolu açılır.
4. Müşteri adını boş bırakıp **Rezervasyonu oluştur** → alanın yanında hata mesajı.
5. Ad ve telefon girip oluşturun → takvimde yeni kutu, panelde ayrıntı.
6. Panelden **Başka saate taşı** ile dolu bir saate taşımayı deneyin → hata; rezervasyon yerinde kalır.
7. Haftalık görünüme geçin, saha seçiciden başka sahayı seçin.

## 4. Yetki sınırları

1. Resepsiyon hesabıyla "Saha kapat" düğmesi görünmez. Adres çubuğuna
   `/isletme/subeler/1/kapatma` yazsanız bile form açılır ama **Sahayı kapat** sunucuda reddedilir (403).
2. `sahip@kuzeyhali.test` ile girip adres çubuğuna `/isletme/subeler/1/takvim` yazın → **403**:
   başka işletmenin takvimi görülemez.
3. `mudur.kadikoy@yesilvadi.test` ile **Saha kapat** → bir saha ve aralık seçip kaydedin.

## 5. Ödeme ve kasa (Aşama 3)

Kadıköy şubesi %30, Ataşehir 300 ₺ kapora ister; Çankaya kapora istemez. Kuponlar: `HOSGELDIN` (%10),
`ILKMAC` (200 ₺, tek kullanımlık).

1. `musteri@sahahub.test` → Kadıköy'de bir saat tut → **Ek hizmet** ekle, `HOSGELDIN` kuponunu uygula
   → fiyat kalemleri ve kapora güncellenir.
2. **Kaporayı öde** → DEMO sağlayıcı sayfası → **Ödeme başarılı** → rezervasyon "Onaylandı · Kısmi ödendi".
3. Başka bir saat için **Başarılı ama bildirim gecikmeli gelsin** seçin; sayfada "onay bekleniyor" yazar.
   60 sn sonra sayfayı yenileyin → onaylanır. (Tutma süresi dolana kadar beklerseniz geç ödeme
   otomatik iade edilir ve personel ekranında görünür.)
4. **Başarılı ve bildirim iki kez gelsin** → ödeme hareketlerinde tek tahsilat görünür.
5. `resepsiyon.kadikoy@yesilvadi.test` → takvimde **Kapora** etiketli bir kutuya (ör. Okan Tunç) tıklayın →
   panelde **Tahsilat al** (Nakit) → etiket "Kısmi" olur. Tutar alanına `1.250,50` gibi Türkçe yazım da olur.
6. Panelden **Hatalı kayıt: ters çevir** ile yanlış tahsilatı düzeltin (tahsilat silinmez, ters kaydı eklenir).
7. Üst menüden **Kasa** → kasada olması gereken tutar; sayılan tutarı girip kasayı kapatın → fark mesajı.
8. `mudur.kadikoy@yesilvadi.test` → panelden **İade et** ve **Personel indirimi** (gerekçe zorunlu);
   **Fiyatlandırma** ekranında kural/ek hizmet/kapora değiştirin (mevcut rezervasyonlar etkilenmez).
9. `sahip@yesilvadi.test` → Fiyatlandırma → **Kuponlar** bölümü yalnızca sahipte görünür.
10. Takvim yan panelinde: **Kapora bekleniyor** ve **Havale doğrulanacak** (Deniz Arslan'ın bildirimi).
    **İade başarısız** uyarısı yalnızca başarısız bir iade olduğunda çıkar (ör. adım 2'de
    "Başarılı; bu ödemenin iadesi başarısız olsun" seçip yöneticiyle iade deneyin).

## 6. Platform yöneticisi

`admin@sahahub.test` → **Platform** → bir işletmeyi gerekçe yazarak askıya alın → müşteri listesinde o
işletmenin sahaları kaybolur. Etkinleştirince geri gelir. Her iki işlem `audit_event` tablosuna yazılır:

```powershell
docker compose exec postgres psql -U sahahub -d sahahub -c "select occurred_at, action, details from audit_event order by id desc limit 5;"
```

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

## 5. Platform yöneticisi

`admin@sahahub.test` → **Platform** → bir işletmeyi gerekçe yazarak askıya alın → müşteri listesinde o
işletmenin sahaları kaybolur. Etkinleştirince geri gelir. Her iki işlem `audit_event` tablosuna yazılır:

```powershell
docker compose exec postgres psql -U sahahub -d sahahub -c "select occurred_at, action, details from audit_event order by id desc limit 5;"
```

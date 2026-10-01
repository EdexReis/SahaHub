# Demo senaryosu (yaklaşık 20 dakika)

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

## 6. Düzenli rezervasyon, bekleme listesi, bildirimler (Aşama 4)

1. `resepsiyon.kadikoy@yesilvadi.test` → üst sekmelerden **Düzenli rezervasyon**.
2. Saha **Saha 1 · Kapalı**, ilk tarih olarak 2 gün sonrası, saat **21:00**, 4 hafta, bir takım adı →
   **Tarihleri önizle**. İlk tarih Deniz Arslan'ın rezervasyonu yüzünden **Dolu**; "Tümünü oluştur" kapalı.
3. **Yalnızca seçili tarihleri oluştur** → takvim ilk maçı açar; panelde "Her hafta · 2. maç / 4" ve
   **Bu ve sonraki 2 maçı iptal et**.
4. Demo veride Emre Yıldız'ın 6 haftalık serisi de var (Saha 2 · Açık, her hafta 19:00).
5. Çıkış → `musteri@sahahub.test` → **Rezervasyonlarım** → **Bekleme listem**: bugün 21:00 Saha 1 için
   1. sıradasınız (demo veride Emre 2. sırada).
6. Yeni sekmede resepsiyonla girip takvimde bugün **Saha 1 · 21:00** (Mert Aydın) rezervasyonunu gerekçeyle
   iptal edin.
7. Müşteri sekmesini yenileyin: menüde bildirim sayısı artar; **Bildirimler** → "Beklediğiniz saat boşaldı".
   Rezervasyonlarım'da **Saat boşaldı · Onayla** → Kadıköy kapora istediği için 15 dakika içinde kaporayı (simülasyon) ödeyerek onaylayın.
   (Onaylamazsanız süre dolunca teklif Emre'ye geçer.)
8. Saha sayfasında dolu bir saate dokunarak sıraya girmeyi deneyin. Aynı saate ikinci kez → "zaten sıradasınız".
9. **Bildirimler → Tercihler**: SMS'i açın (telefon zorunlu).
10. E-postalar: <http://localhost:8025> (Mailpit). Hiçbir e-posta dışarı gitmez.
11. `admin@sahahub.test` → **Platform → Demo mesaj kutusu**: gönderilmemiş (demo) SMS'ler.

## 7. Takımlar, ilanlar ve lig (Aşama 5)

1. Herkes: üst menü **İlanlar** → "Rakip arıyoruz" (Kadıköy Kartalları, Emre'nin seri maçına bağlı) ve
   "Oyuncu arıyoruz" (Moda Şimşekleri, 2 oyuncu). **Ligler** sekmesi → **Kadıköy Kış Ligi 2026**: puan durumu ve
   fikstür (1. hafta oynandı, 2. hafta planlı).
2. `kaptan@sahahub.test` → **Takımlarım → Kadıköy Kartalları**: davet bağlantısı, oyuncular, "Kaptan yap",
   "Çıkar". Bağlantıyı gizli pencerede açın → giriş yapmadan önizleme; `uzun.isim@sahahub.test` zaten üye.
3. Aynı hesapla **İlanlar → Moda Şimşekleri** → mesaj yazıp **Başvur**.
4. `musteri@sahahub.test` (Moda Şimşekleri kaptanı) → menüde bildirim → ilan sayfasında başvurular
   (Muhammed'in bekleyen başvurusu ve Emre'nin yeni başvurusu) → ikisini **Kabul et** → ilan "Doldu" olur;
   kabul edilenler telefon numaralarını görür.
5. `musteri@sahahub.test` → **İlan ver**: takım seçin, "Rezervasyonunuz" listesinden bir maçınızı seçin
   (zaman ve yer otomatik gelir).
6. `mudur.kadikoy@yesilvadi.test` → İşletme paneli → **Ligler** → Kış Ligi:
   - 3. haftanın bir maçında **Planla** → dolu bir saat seçin (ör. Mini Saha'da bugün 19:00, Emre'nin rezervasyonu) → "saha dolu", maç
     planlanmaz.
   - **Haftalık planla**: ilk hafta için 2. haftadan 7 gün sonrası, Mini Saha, 20:00 → kalan 9 maç planlanır
     (bir maç doluysa hiçbiri planlanmaz).
   - Takvimde 6 gün sonrasına gidin: Mini Saha'da lacivert kenarlı **lig maçı** kutuları; o saatlere
     rezervasyon yapılamaz.
7. **Ligler → Çankaya Bahar Kupası** (eleme): eşleşme ağacı, ilk turu bay geçen iki üst tohum ve penaltıyla
   biten çeyrek final. Yarı finaller 4 gün sonraya planlı; skor ancak maç başladıktan sonra girilebilir.
   Hemen denemek için `sahip@kuzeyhali.test` ile **Ligler → Yeni lig veya turnuva → Eleme** açın, 3 takım
   ekleyin, **Eşleşmeleri oluştur**, maçı birkaç dakika sonrasına planlayın; maç saati gelince skoru girin.
   Berabere girerseniz penaltı galibi istenir; maç bitince final kendiliğinden açılır.
8. Resepsiyon hesabıyla **Ligler** sekmesi görünmez; `/isletme/subeler/1/ligler` adresi 403 verir. Maçlar
   takvimde yine görünür.

## 8. Raporlar ve yönetim (Aşama 6)

1. `mudur.kadikoy@yesilvadi.test` → **Raporlar**: bu ayın özeti. Üstteki dört kartta rezervasyon bedeli,
   net tahsilat, kalan alacak ve doluluk ayrı ayrı görünür. "Doluluk nasıl hesaplanıyor?" açıklamasını açın.
   Tarih aralığını değiştirin, dönemi "Haftalık" yapın, **CSV indir** ile dosyayı Excel'de açın.
2. **Şube ve sahalar** → bir sahada **Düzenle** → JPEG/PNG fotoğraf yükleyin (3 MB üstü dosyayı tarayıcı
   uyarır). Fotoğraf saha listesinde ve saha sayfasında görünür. Bugün rezervasyonu olan sahayı
   **Rezervasyona kapat**mayı deneyin → reddedilir.
3. Haftalık saatlerde bir günün kapanışını erkene çekin → "X ileri tarihli rezervasyon yeni saatlerin dışında
   kaldı" uyarısı; rezervasyonlar silinmez. Bir **özel gün** ekleyin → müşteri ekranında o gün "kapalı".
4. Takvimde bir rezervasyon → panelde **Müşteri geçmişi**.
5. `sahip@yesilvadi.test` → **Personel**: kayıtlı bir hesabın e-postasıyla (ör. `kaptan@sahahub.test`)
   resepsiyon görevi verin, sonra kaldırın. **Denetim kaydı**: tüm bu işlemler süzgeçle listelenir.
   **Raporlar → Şubeleri karşılaştır**.
6. Çıkış → **Giriş → Parolamı unuttum** → `musteri@sahahub.test` → <http://localhost:8025> Mailpit'te gelen
   bağlantıyı açıp yeni parola belirleyin. Aynı bağlantı ikinci kez çalışmaz.

## 9. Platform yöneticisi

`admin@sahahub.test` → **Platform** → bir işletmeyi gerekçe yazarak askıya alın → müşteri listesinde o
işletmenin sahaları kaybolur. Etkinleştirince geri gelir. Her iki işlem `audit_event` tablosuna yazılır:

```powershell
docker compose exec postgres psql -U sahahub -d sahahub -c "select occurred_at, action, details from audit_event order by id desc limit 5;"
```

# SahaHub Tasarım Kararları

Bu belge arayüzün neden böyle göründüğünü açıklar. Yeni ekran eklerken önce buraya bakın.

## 1. Yön: "Saha tabelası"

Halı saha işletmesinin gerçek dünyadaki görsel dili: girişteki tabela, skorbord, saha çizgileri,
duvardaki saat çizelgesi. Arayüz bu dili ödünç alır; dekorasyon değil, **okunabilirlik** için.

- **Saatler skorbord gibi** okunur: dar (condensed), kalın, eşit genişlikli rakamlar. Müşteri saat
  listesinde göz önce saate, sonra fiyata gider.
- **İnce çizgiler** (1px, saha çizgisi gibi) kartlardan önce gelir. Bölümler çerçeveyle değil
  çizgi ve boşlukla ayrılır. Her şeyi yuvarlak kartlara koymuyoruz.
- **Lacivert** = yapı (üst bar, başlıklar, birincil metin). **Yeşil** = eylem ve "uygun".
  Yeşil yalnızca tıklanabilir/olumlu şeylerde kullanılır; süs olarak kullanılmaz.

Yasaklar (bilinçli): gradyan, cam/blur efekti, dev hero başlığı, dekoratif istatistik, sürekli
animasyon, "spor deneyiminizi zirveye taşıyın" türü reklam cümlesi.

## 2. Renk

| Belirteç | Değer | Kullanım | Kontrast (WCAG, hesaplandı) |
|---|---|---|---|
| `--ink` | `#0F1E33` | Ana metin, üst bar zemini | Beyaz zeminde 16.8:1 |
| `--ink-2` | `#3A4A60` | İkincil metin | 9.0:1 |
| `--ink-3` | `#5D6B7E` | Yardımcı metin, etiket | 5.4:1 beyaz, 5.0:1 kağıt zeminde (AA) |
| `--paper` | `#F4F6F2` | Sayfa zemini (hafif yeşilimsi kırık beyaz, tebeşir) | — |
| `--surface` | `#FFFFFF` | Formlar, paneller | — |
| `--line` | `#D9DFD6` | Ayırıcı çizgiler | — |
| `--green` | `#17804A` | Birincil buton, "uygun" | Beyaz yazı 5.0:1 (AA) |
| `--green-ink` | `#0F6A3B` | Yeşil metin | 6.7:1 beyaz, 5.8:1 yeşil zeminde |
| `--green-soft` | `#E3F3E9` | Uygun saat zemini, başarı mesajı | — |
| `--amber` | `#9A5B00` / `#FFF3DC` | Geçici tutma, uyarı | 5.4:1 beyaz, 4.9:1 sarı zeminde |
| `--red` | `#B42318` / `#FDECEA` | İptal, hata | 6.6:1 beyaz, 5.8:1 kırmızı zeminde |

Durum asla yalnızca renkle anlatılmaz: her durum etiketinde **metin** vardır ("Onaylandı",
"Geçici tutuluyor"), takvimde ayrıca **kenar deseni** farklıdır (geçici tutma kesik çizgili,
bakım çapraz taramalı, gelmedi üstü çizili).

## 3. Tipografi

- **Barlow** (gövde, 400/500/600) — tabela fontu ailesinden, Türkçe karakterleri tam, dar
  ekranda okunaklı. Yerel dosya (`static/fonts`), SIL OFL lisanslı; dış CDN yok.
- **Barlow Condensed** (600/700) — saatler, tarih şeridi, sayılar. `font-variant-numeric: tabular-nums`.
- Ölçek: 13 / 14 / 16 / 18 / 22 / 28 px. En büyük başlık 28px; sayfa başlıkları bilgi taşır
  ("Kadıköy Şubesi · 1 Ekim Çarşamba"), slogan taşımaz.

## 4. İki yoğunluk, tek dil

### Müşteri (telefon öncelikli, rahat)
- Tek sütun, 16px kenar boşluğu, en az **44×44px** dokunma alanı.
- Akış: **Saha → Gün → Saat → Özet → Onay.** Her ekranda tek birincil eylem.
- Gün seçimi yatay kaydırılan **tarih şeridi** (Bugün, Yarın, Cum 3 …). Kapalı günler soluk ve
  "Kapalı" yazılı; tıklanamaz değil, tıklanınca nedenini söyler.
- Saatler **ızgara** (telefonda 2, tablette 3–4 sütun). Her hücre: saat (büyük, condensed) +
  fiyat. Dolu saat gri ve "Dolu" yazılı; gizlenmez — müşteri yoğunluğu görür.
- Gece yarısından sonraki saatler ayrı bir "Gece" ayıracıyla gösterilir (00:00 hangi güne ait
  karışmasın diye).
- Fiyat her zaman açıktır: saat hücresinde toplam, özette kalem kalem (ör. "Standart ücret
  30 dk + Hafta içi akşam 30 dk").

### Personel (masaüstü/tablet öncelikli, yoğun)
- Ana ekran **günlük saha takvimi**: sütun = saha, satır = 15 dk. Bir saat 48px.
- Takvimin solunda değil **sağında** sabit bir panel: rezervasyon ayrıntısı / hızlı
  rezervasyon / saha kapatma. Panel açılınca takvim yerinde kalır (bağlam kaybolmaz).
- Boş saatler takvimde **soluk yeşil, kesik kenarlı "+ Boş"** kutular: tek tıkla hızlı
  rezervasyon formu o saha ve saatle dolu açılır. Odak: boş saatleri görmek ve doldurmak.
- Üstte tek satır özet: rezervasyon sayısı, geçici tutma, gelen, boş saat. Bunlar dekor değil;
  her biri günün operasyon sorusuna cevap.
- Telefonda takvim yatay kaydırılır; saat sütunu sabit kalır. Panel alttan tam genişlikte açılır.
- "Bekleyen kaporalar" kutusu ödeme modülü (Aşama 3) gelince eklenecek; o zamana kadar
  ekranda sahte veri gösterilmez.

## 5. Bileşenler

- **Buton**: birincil (yeşil dolgu), ikincil (lacivert çerçeve), tehlikeli (kırmızı çerçeve),
  metin buton. Yükseklik 44px (personel panelinde 36px). Köşe yarıçapı **6px** — tabela
  plakası gibi; hap (pill) şekli yok.
- **Durum etiketi**: büyük harf değil, cümle düzeni; küçük nokta + metin.
- **Form**: etiket üstte, hata metni alanın hemen altında, kırmızı ve ikonlu. Zorunlu alanlar
  belirtilir. Sunucu taraflı doğrulama esastır; tarayıcı doğrulaması yardımcıdır.
- **Uyarı alanı** (`#flash-area`): sayfanın üstünde, `role="status"` / hata için `role="alert"`.
- **Boş durum**: tek cümle + bir sonraki adım ("Bu gün için uygun saat kalmadı. Yarına bak").
- **Yükleniyor**: HTMX isteği sırasında hedef alan %55 opaklık + `aria-busy`. Döner ikon yok.

## 6. Hareket

Yalnızca durum geçişinde 120ms opaklık/renk geçişi. `prefers-reduced-motion` ile kapanır.
Sürekli/dekoratif animasyon yok.

## 7. Erişilebilirlik

- Tüm etkileşimler klavyeyle: saat hücreleri gerçek `<button>`, takvim öğeleri gerçek `<a>`.
- Görünür odak halkası: 3px yeşil dış çizgi + 2px boşluk.
- Takvim öğelerinin `aria-label`'ı tam cümle: "Saha 1, 21:00–22:00, Ali Vural, Onaylandı".
- Metin %200 büyütmede taşmaz; uzun isimler `text-overflow: ellipsis` + tam isim `title`'da.

## 8. Metin dili

Kısa, somut, fiille başlayan: "Saat seç", "Saati tut", "Rezervasyonu onayla",
"Rezervasyonu iptal et", "Kapora bekleniyor", "Geldi olarak işaretle". Hata mesajı ne olduğunu
ve ne yapılacağını söyler: "Bu saat artık uygun değil. Lütfen başka bir saat seçin."

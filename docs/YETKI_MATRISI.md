# Rol ve yetki matrisi

Kaynak: `identity/domain/RolePermissions.java` (test: `RolePermissionsTest`). Biri değişirse diğeri de güncellenir.

## Roller

| Rol | Kapsam | Nasıl verilir |
|---|---|---|
| Platform yöneticisi | Tüm platform (yalnızca `/admin`) | `app_user.platform_admin` |
| İşletme sahibi (`OWNER`) | Kendi işletmesinin **tüm** şubeleri | `staff_membership` (branch_id boş) |
| Şube yöneticisi (`BRANCH_MANAGER`) | Yalnızca atandığı şube | `staff_membership` (branch_id dolu) |
| Resepsiyon/kasa (`RECEPTION`) | Yalnızca atandığı şube | `staff_membership` (branch_id dolu) |
| Müşteri | Kendi rezervasyonları | Her kayıtlı kullanıcı |
| Takım kaptanı | Yönettiği takım: davet, üye çıkarma, kaptanlık devri, ilan verme, başvuru kabulü | Rol değil; `team_member.role = CAPTAIN` |

## İzinler

| İzin | Sahip | Şube yön. | Resepsiyon | Durum |
|---|:-:|:-:|:-:|---|
| `CALENDAR_VIEW` takvim, rezervasyon ayrıntısı | ✓ | ✓ | ✓ | çalışıyor |
| `RESERVATION_CREATE` hızlı rezervasyon | ✓ | ✓ | ✓ | çalışıyor |
| `RESERVATION_MOVE` taşıma | ✓ | ✓ | ✓ | çalışıyor |
| `RESERVATION_CANCEL` personel iptali (gerekçe zorunlu) | ✓ | ✓ | ✓ | çalışıyor |
| `RESERVATION_STATUS_UPDATE` geldi/tamamlandı/gelmedi | ✓ | ✓ | ✓ | çalışıyor |
| `PITCH_BLOCK_MANAGE` saha kapatma | ✓ | ✓ | ✗ | çalışıyor |
| `PITCH_MANAGE` saha düzenleme | ✓ | ✓ | ✗ | ekran yok |
| `PRICE_MANAGE` taban ücret, fiyat kuralları, ek hizmetler, kapora | ✓ | ✓ | ✗ | çalışıyor |
| `STAFF_MANAGE` personel atama | ✓ | ✗ | ✗ | ekran yok |
| `REPORT_VIEW` raporlar | ✓ | ✓ | ✗ | Aşama 6 |
| `AUDIT_VIEW` denetim kayıtları | ✓ | ✗ | ✗ | Aşama 6 |
| `PAYMENT_COLLECT` nakit/POS tahsilat, havale doğrulama, ters kayıt | ✓ | ✓ | ✓ | çalışıyor |
| `PAYMENT_REFUND` iade | ✓ | ✓ | ✗ | çalışıyor |
| `DISCOUNT_APPLY` personel indirimi (gerekçeli) | ✓ | ✓ | ✗ | çalışıyor |
| `CASH_MANAGE` kasa açma/kapama | ✓ | ✓ | ✓ | çalışıyor |
| `EXPENSE_MANAGE` gider kaydı | ✓ | ✓ | ✗ | çalışıyor |
| `TOURNAMENT_MANAGE` lig açma, takım ekleme, fikstür, maç planlama, skor | ✓ | ✓ | ✗ | çalışıyor |
| `COUPON_MANAGE` işletme geneli kupon | ✓ | ✗ | ✗ | çalışıyor (yalnızca sahip; işletme düzeyi kontrol) |

## Müşteri ve platform kuralları

| İşlem | Kural |
|---|---|
| Rezervasyonu görme/onaylama/iptal | Yalnızca `customer_id` kendisi olan kayıt; değilse 404 |
| Saat tutma | Aynı anda en fazla 3 onaylanmamış tutma |
| İptal | Geçici tutma her zaman; onaylı rezervasyon maçtan şube ayarı kadar (varsayılan 24 saat) öncesine kadar, sınır anı dahil |
| Ek hizmet, kupon | Yalnızca kendi geçici tutmasında (onaydan sonra şubeye başvurur) |
| Çevrim içi ödeme | Yalnızca kendi geçici tutması; simülasyon sayfasını yalnızca ödemenin sahibi görür |
| Havale bildirimi | Yalnızca kendi onaylı rezervasyonu |
| Platform: işletme askıya alma | Gerekçe zorunlu, `PLATFORM_BUSINESS_STATUS_CHANGED` denetim kaydı |
| Bekleme listesi | Yalnızca kayıtlı müşteri, yalnızca dolu saat; aynı saate bir kez, aynı anda en fazla 5 saat; yalnızca kendi kaydından çıkabilir |
| Bildirimler, tercihler | Yalnızca kendi bildirimleri (`user_id`) ve kendi tercihleri |
| Platform: demo mesaj kutusu | Yalnızca platform yöneticisi (`/admin/**`); kayıtlar demo SMS/WhatsApp, gerçek gönderim yok |

Takım ve ilan kuralları (müşteri tarafı, `CommunityIT`):

| İşlem | Kural |
|---|---|
| Takım sayfası | Yalnızca aktif üyeler; üye olmayan "bulunamadı" alır. Davet kodu yalnızca kaptana görünür |
| Takıma katılma | Yalnızca davet koduyla; en fazla 25 üye (takım satırı kilitlenir), kişi başı en fazla 10 takım |
| İlan verme | Yalnızca takımın kaptanı; kişi başı en fazla 5 açık ilan; bağlanan rezervasyon kendi, onaylı ve ileri tarihli olmalı |
| Başvuru | Kendi ilanına ve üyesi olduğu takımın oyuncu ilanına başvurulamaz; rakip ilanına yalnızca kaptanı olduğu takımla |
| Başvuru kabul/ret, ilanı kapatma | Yalnızca ilan sahibi |
| Telefon numarası | Yalnızca kabul edilen başvuruda karşılıklı görünür |
| Lig sayfası (`/ligler`) | Herkese açık; taslak lig ve askıdaki işletmenin ligi "bulunamadı" |

Personel tarafında düzenli rezervasyon yeni izin gerektirmez: oluşturma `RESERVATION_CREATE`, "bu ve sonraki
maçları iptal" `RESERVATION_CANCEL` ister (her ikisi de rol matrisinde zaten tanımlı).

## Kontrol nasıl yapılır?

1. URL: `/isletme/**` → `ROLE_STAFF`, `/admin/**` → `ROLE_PLATFORM_ADMIN` (kaba süzgeç).
2. Servis: kaydın şubesi veritabanından okunur → `AccessGuard.requireBranch(kullanıcı, işletme, şube, izin)`.
   Kapsam (bu işletme + bu şube) **ve** izin birlikte sağlanmazsa `AccessDeniedException` → 403.
3. Testler: `AccessControlIT` (başka işletmenin takvimi/rezervasyonu, başka şubenin sahası, CSRF'siz istek,
   müşterinin personel paneli, resepsiyonun saha kapatması).

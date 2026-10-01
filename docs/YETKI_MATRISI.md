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
| Takım kaptanı | Yönettiği takımlar (Aşama 5) | Rol değil; takım-üye ilişkisi |

## İzinler

| İzin | Sahip | Şube yön. | Resepsiyon | Durum |
|---|:-:|:-:|:-:|---|
| `CALENDAR_VIEW` takvim, rezervasyon ayrıntısı | ✓ | ✓ | ✓ | çalışıyor |
| `RESERVATION_CREATE` hızlı rezervasyon | ✓ | ✓ | ✓ | çalışıyor |
| `RESERVATION_MOVE` taşıma | ✓ | ✓ | ✓ | çalışıyor |
| `RESERVATION_CANCEL` personel iptali (gerekçe zorunlu) | ✓ | ✓ | ✓ | çalışıyor |
| `RESERVATION_STATUS_UPDATE` geldi/tamamlandı/gelmedi | ✓ | ✓ | ✓ | çalışıyor |
| `PITCH_BLOCK_MANAGE` saha kapatma | ✓ | ✓ | ✗ | çalışıyor |
| `PITCH_MANAGE` saha düzenleme | ✓ | ✓ | ✗ | ekran yok (Aşama 3+) |
| `PRICE_MANAGE` fiyat kuralları | ✓ | ✓ | ✗ | ekran yok (Aşama 3) |
| `STAFF_MANAGE` personel atama | ✓ | ✗ | ✗ | ekran yok |
| `REPORT_VIEW` raporlar | ✓ | ✓ | ✗ | Aşama 6 |
| `AUDIT_VIEW` denetim kayıtları | ✓ | ✗ | ✗ | Aşama 6 |

## Müşteri ve platform kuralları

| İşlem | Kural |
|---|---|
| Rezervasyonu görme/onaylama/iptal | Yalnızca `customer_id` kendisi olan kayıt; değilse 404 |
| Saat tutma | Aynı anda en fazla 3 onaylanmamış tutma |
| İptal | Geçici tutma her zaman; onaylı rezervasyon maçtan şube ayarı kadar (varsayılan 24 saat) öncesine kadar, sınır anı dahil |
| Platform: işletme askıya alma | Gerekçe zorunlu, `PLATFORM_BUSINESS_STATUS_CHANGED` denetim kaydı |

## Kontrol nasıl yapılır?

1. URL: `/isletme/**` → `ROLE_STAFF`, `/admin/**` → `ROLE_PLATFORM_ADMIN` (kaba süzgeç).
2. Servis: kaydın şubesi veritabanından okunur → `AccessGuard.requireBranch(kullanıcı, işletme, şube, izin)`.
   Kapsam (bu işletme + bu şube) **ve** izin birlikte sağlanmazsa `AccessDeniedException` → 403.
3. Testler: `AccessControlIT` (başka işletmenin takvimi/rezervasyonu, başka şubenin sahası, CSRF'siz istek,
   müşterinin personel paneli, resepsiyonun saha kapatması).

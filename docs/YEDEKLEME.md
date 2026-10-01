# Yedekleme ve geri yükleme

Komutlar Windows PowerShell içindir ve Docker Compose ile çalışan yerel PostgreSQL'i hedefler.
Canlı ortamda aynı `pg_dump`/`pg_restore` komutları yönetilen veritabanına karşı çalıştırılır.

## Yedek alma

```powershell
New-Item -ItemType Directory -Force backups | Out-Null
$stamp = Get-Date -Format "yyyyMMdd-HHmm"
docker compose exec -T postgres pg_dump -U sahahub -d sahahub -Fc -f /tmp/sahahub.dump
docker compose cp postgres:/tmp/sahahub.dump "backups/sahahub-$stamp.dump"
```

`-Fc` sıkıştırılmış özel biçimdir; tabloları seçerek geri yüklemeye izin verir. `backups/` klasörü
repoya eklenmemelidir (kişisel veri içerir).

## Geri yükleme (boş veritabanına)

```powershell
# Uygulamayı durdurun. Dikkat: mevcut veriyi siler.
docker compose exec -T postgres psql -U sahahub -d sahahub -c "drop schema public cascade; create schema public;"
docker compose cp "backups/sahahub-20261001-0900.dump" postgres:/tmp/restore.dump
docker compose exec -T postgres pg_restore -U sahahub -d sahahub --no-owner /tmp/restore.dump
```

Ardından uygulamayı başlatın; Flyway şema sürümünü `flyway_schema_history` tablosundan okur ve yalnızca
yeni migration'ları uygular.

## Doğrulama

Yedeği düzenli olarak ayrı bir veritabanına geri yükleyip kontrol edin:

```powershell
docker compose exec -T postgres createdb -U sahahub sahahub_check
docker compose exec -T postgres pg_restore -U sahahub -d sahahub_check --no-owner /tmp/sahahub.dump
docker compose exec -T postgres psql -U sahahub -d sahahub_check -c "select count(*) from reservation;"
docker compose exec -T postgres dropdb -U sahahub sahahub_check
```

## Notlar

- `docker compose down -v` **veritabanı birimini (volume) siler**; yedeksiz kullanmayın.
- Canlı ortam için: günlük otomatik yedek, en az 7 günlük saklama, yedeklerin şifreli ve farklı bir
  konumda tutulması ve ayda bir geri yükleme denemesi önerilir. Bu adımlar bu sürümde otomatik değildir.

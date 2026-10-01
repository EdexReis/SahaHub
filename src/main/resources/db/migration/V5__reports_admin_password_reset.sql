-- Aşama 6: raporlar, yönetim ekranları, parola sıfırlama.

-- ---------------------------------------------------------------- rezervasyon onay anı
-- İptal oranında "onaylandıktan sonra iptal" ile "geçici tutmada bırakılan" kayıtları ayırmak için.
ALTER TABLE reservation ADD COLUMN confirmed_at timestamptz;
-- Geriye dönük doldurma: açık/bitmiş onaylı kayıtlar ve personelin açtığı (doğrudan onaylı) kayıtlar.
-- Çevrim içi müşterinin iptal ettiği eski kayıtlarda onay anı bilinmez; NULL kalır (raporda belirtilir).
UPDATE reservation SET confirmed_at = created_at
 WHERE status IN ('CONFIRMED', 'COMPLETED', 'NO_SHOW')
    OR (status = 'CANCELLED' AND channel <> 'ONLINE');
ALTER TABLE reservation ADD CONSTRAINT ck_reservation_confirmed
    CHECK (status NOT IN ('CONFIRMED', 'COMPLETED', 'NO_SHOW') OR confirmed_at IS NOT NULL);

-- Rapor sorguları: ödemeler tamamlanma zamanına göre, rezervasyonlar şube + başlangıç (V1'de var)
CREATE INDEX ix_payment_branch_completed ON payment (branch_id, completed_at) WHERE status = 'SUCCEEDED';

-- ---------------------------------------------------------------- parola sıfırlama
-- Token'ın kendisi saklanmaz; yalnızca SHA-256 özeti. Bağlantı süreli ve tek kullanımlıktır.
CREATE TABLE password_reset_token (
    id          bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id     bigint      NOT NULL REFERENCES app_user (id),
    token_hash  varchar(64) NOT NULL UNIQUE,
    created_at  timestamptz NOT NULL,
    expires_at  timestamptz NOT NULL,
    used_at     timestamptz,
    CHECK (expires_at > created_at)
);
CREATE INDEX ix_password_reset_user ON password_reset_token (user_id, created_at);

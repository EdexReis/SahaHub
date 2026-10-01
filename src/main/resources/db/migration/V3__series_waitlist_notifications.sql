-- Aşama 4: düzenli rezervasyon serileri, bekleme listesi, bildirimler (transactional outbox).

-- ---------------------------------------------------------------- bildirim tercihleri
ALTER TABLE app_user
    ADD COLUMN notify_email boolean NOT NULL DEFAULT true,
    ADD COLUMN notify_sms   boolean NOT NULL DEFAULT false;

-- ---------------------------------------------------------------- düzenli rezervasyon
-- Seri yalnızca tanımdır; her maç ayrı bir reservation satırıdır (series_id ile bağlı).
CREATE TABLE reservation_series (
    id                bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    business_id       bigint       NOT NULL REFERENCES business (id),
    branch_id         bigint       NOT NULL REFERENCES branch (id),
    pitch_id          bigint       NOT NULL REFERENCES pitch (id),
    customer_id       bigint       REFERENCES app_user (id),
    guest_name        varchar(120),
    guest_phone       varchar(30),
    first_date        date         NOT NULL,
    start_time        time         NOT NULL,
    duration_minutes  integer      NOT NULL CHECK (duration_minutes BETWEEN 30 AND 240),
    occurrences       integer      NOT NULL CHECK (occurrences BETWEEN 1 AND 104),
    channel           varchar(20)  NOT NULL,
    note              varchar(500),
    created_by        bigint       NOT NULL REFERENCES app_user (id),
    created_at        timestamptz  NOT NULL,
    CHECK (customer_id IS NOT NULL OR guest_name IS NOT NULL)
);

ALTER TABLE reservation
    ADD COLUMN series_id    bigint REFERENCES reservation_series (id),
    ADD COLUMN series_index integer,
    ADD CONSTRAINT ck_reservation_series CHECK ((series_id IS NULL) = (series_index IS NULL));
CREATE INDEX ix_reservation_series ON reservation (series_id, starts_at) WHERE series_id IS NOT NULL;
CREATE UNIQUE INDEX ux_reservation_series_index ON reservation (series_id, series_index) WHERE series_id IS NOT NULL;

-- ---------------------------------------------------------------- bekleme listesi
-- Teklif, sıradaki müşteri adına açılan geçici tutmadır (offer_reservation_id). Böylece "aynı boşluk
-- iki kişiye verilmez" güvencesini pitch_occupancy EXCLUDE kısıtı sağlar.
CREATE TABLE waitlist_entry (
    id                    bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    business_id           bigint       NOT NULL REFERENCES business (id),
    branch_id             bigint       NOT NULL REFERENCES branch (id),
    pitch_id              bigint       NOT NULL REFERENCES pitch (id),
    customer_id           bigint       NOT NULL REFERENCES app_user (id),
    starts_at             timestamptz  NOT NULL,
    ends_at               timestamptz  NOT NULL,
    status                varchar(12)  NOT NULL CHECK (status IN ('WAITING', 'OFFERED', 'ACCEPTED', 'EXPIRED', 'LEFT')),
    offer_reservation_id  bigint REFERENCES reservation (id),
    offered_at            timestamptz,
    created_at            timestamptz  NOT NULL,
    closed_at             timestamptz,
    CHECK (ends_at > starts_at),
    -- Teklif edilmiş kaydın teklif rezervasyonu olmalı; kapanmış kaydın kapanış zamanı olmalı
    CHECK (status <> 'OFFERED' OR offer_reservation_id IS NOT NULL),
    CHECK ((status IN ('WAITING', 'OFFERED')) = (closed_at IS NULL))
);
-- Aynı müşteri aynı saat için iki kez sıraya giremez
CREATE UNIQUE INDEX ux_waitlist_active ON waitlist_entry (customer_id, pitch_id, starts_at)
    WHERE status IN ('WAITING', 'OFFERED');
-- Bir boşluk için aynı anda en fazla bir açık teklif
CREATE UNIQUE INDEX ux_waitlist_one_offer ON waitlist_entry (pitch_id, starts_at) WHERE status = 'OFFERED';
CREATE INDEX ix_waitlist_queue ON waitlist_entry (pitch_id, starts_at, created_at) WHERE status = 'WAITING';

-- ---------------------------------------------------------------- bildirimler
-- Uygulama içi bildirim merkezi
CREATE TABLE notification (
    id          bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id     bigint       NOT NULL REFERENCES app_user (id),
    kind        varchar(30)  NOT NULL,
    title       varchar(160) NOT NULL,
    body        varchar(600) NOT NULL,
    link        varchar(200),
    dedup_key   varchar(160) NOT NULL UNIQUE,
    created_at  timestamptz  NOT NULL,
    read_at     timestamptz
);
CREATE INDEX ix_notification_user ON notification (user_id, created_at DESC);
CREATE INDEX ix_notification_unread ON notification (user_id) WHERE read_at IS NULL;

-- Transactional outbox: gönderilecek e-posta/SMS/WhatsApp mesajları. İşi doğuran değişiklikle AYNI
-- transaction'da yazılır; işlem geri alınırsa mesaj da hiç oluşmaz. dedup_key tekil: aynı olay
-- ikinci kez işlense de ikinci mesaj oluşmaz.
CREATE TABLE notification_outbox (
    id               bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    channel          varchar(10)  NOT NULL CHECK (channel IN ('EMAIL', 'SMS', 'WHATSAPP')),
    recipient        varchar(254) NOT NULL,
    subject          varchar(160) NOT NULL,
    body             varchar(1000) NOT NULL,
    dedup_key        varchar(160) NOT NULL UNIQUE,
    status           varchar(10)  NOT NULL CHECK (status IN ('PENDING', 'SENT', 'FAILED')),
    attempts         integer      NOT NULL DEFAULT 0,
    next_attempt_at  timestamptz  NOT NULL,
    provider         varchar(20),
    last_error       varchar(300),
    created_at       timestamptz  NOT NULL,
    sent_at          timestamptz
);
CREATE INDEX ix_outbox_due ON notification_outbox (next_attempt_at) WHERE status = 'PENDING';

-- Aşama 3: kapora politikası, ek hizmetler, kupon, indirim kalemleri, ödeme hareketleri,
-- webhook olayları, simülasyon sağlayıcı, kasa ve giderler.

-- ---------------------------------------------------------------- kapora politikası
ALTER TABLE branch
    ADD COLUMN deposit_type  varchar(10)   NOT NULL DEFAULT 'NONE' CHECK (deposit_type IN ('NONE', 'FIXED', 'PERCENT')),
    ADD COLUMN deposit_value numeric(10,2) NOT NULL DEFAULT 0 CHECK (deposit_value >= 0),
    ADD COLUMN bank_iban     varchar(34),
    ADD CONSTRAINT ck_branch_deposit_percent CHECK (deposit_type <> 'PERCENT' OR deposit_value <= 100);

-- Rezervasyon anındaki kapora kuralının kopyası: şube politikası sonradan değişse de bu rezervasyon etkilenmez.
ALTER TABLE reservation
    ADD COLUMN deposit_type  varchar(10)   NOT NULL DEFAULT 'NONE' CHECK (deposit_type IN ('NONE', 'FIXED', 'PERCENT')),
    ADD COLUMN deposit_value numeric(10,2) NOT NULL DEFAULT 0;

-- ---------------------------------------------------------------- ek hizmetler
CREATE TABLE extra_service (
    id          bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    branch_id   bigint        NOT NULL REFERENCES branch (id),
    name        varchar(80)   NOT NULL,
    unit_price  numeric(10,2) NOT NULL CHECK (unit_price >= 0),
    active      boolean       NOT NULL DEFAULT true,
    created_at  timestamptz   NOT NULL DEFAULT now()
);
CREATE INDEX ix_extra_service_branch ON extra_service (branch_id) WHERE active;

-- ---------------------------------------------------------------- kupon
CREATE TABLE coupon (
    id           bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    business_id  bigint        NOT NULL REFERENCES business (id),
    code         varchar(30)   NOT NULL,
    kind         varchar(10)   NOT NULL CHECK (kind IN ('PERCENT', 'FIXED')),
    value        numeric(10,2) NOT NULL CHECK (value > 0),
    valid_from   date,
    valid_to     date,
    max_uses     integer       NOT NULL CHECK (max_uses > 0),
    used_count   integer       NOT NULL DEFAULT 0,
    active       boolean       NOT NULL DEFAULT true,
    created_at   timestamptz   NOT NULL DEFAULT now(),
    CHECK (kind <> 'PERCENT' OR value <= 100),
    -- Kullanım sınırı veritabanında da garanti: eşzamanlı iki kullanım sınırı aşamaz
    CHECK (used_count >= 0 AND used_count <= max_uses)
);
CREATE UNIQUE INDEX ux_coupon_code ON coupon (business_id, upper(code));

-- ---------------------------------------------------------------- fiyat kalemleri genişletme
-- PITCH kalemleri saha ücretinin değişmez kopyasıdır. EXTRA/COUPON/STAFF_DISCOUNT kalemleri sonradan
-- eklenebilir; kaldırılırken silinmez, voided_at doldurulur.
ALTER TABLE reservation_price_line
    ADD COLUMN kind              varchar(20)   NOT NULL DEFAULT 'PITCH'
        CHECK (kind IN ('PITCH', 'EXTRA', 'COUPON', 'STAFF_DISCOUNT')),
    ADD COLUMN quantity          integer       CHECK (quantity IS NULL OR quantity > 0),
    ADD COLUMN unit_amount       numeric(10,2),
    ADD COLUMN percent           numeric(5,2)  CHECK (percent IS NULL OR (percent > 0 AND percent <= 100)),
    ADD COLUMN reason            varchar(300),
    ADD COLUMN extra_service_id  bigint REFERENCES extra_service (id),
    ADD COLUMN coupon_id         bigint REFERENCES coupon (id),
    ADD COLUMN created_by        bigint REFERENCES app_user (id),
    ADD COLUMN voided_at         timestamptz,
    ADD COLUMN voided_by         bigint REFERENCES app_user (id),
    ALTER COLUMN starts_at   DROP NOT NULL,
    ALTER COLUMN ends_at     DROP NOT NULL,
    ALTER COLUMN minutes     DROP NOT NULL,
    ALTER COLUMN hourly_rate DROP NOT NULL,
    ADD CONSTRAINT ck_price_line_discount_reason CHECK (kind <> 'STAFF_DISCOUNT' OR reason IS NOT NULL);

-- Bir rezervasyonda en fazla bir etkin kupon
CREATE UNIQUE INDEX ux_price_line_one_coupon ON reservation_price_line (reservation_id)
    WHERE kind = 'COUPON' AND voided_at IS NULL;

-- ---------------------------------------------------------------- kasa oturumu
CREATE TABLE cash_session (
    id               bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    branch_id        bigint        NOT NULL REFERENCES branch (id),
    opened_by        bigint        NOT NULL REFERENCES app_user (id),
    opened_at        timestamptz   NOT NULL,
    opening_float    numeric(10,2) NOT NULL CHECK (opening_float >= 0),
    closed_by        bigint REFERENCES app_user (id),
    closed_at        timestamptz,
    expected_amount  numeric(10,2),
    counted_amount   numeric(10,2) CHECK (counted_amount IS NULL OR counted_amount >= 0),
    note             varchar(500),
    CHECK ((closed_at IS NULL) = (counted_amount IS NULL))
);
-- Şube başına aynı anda tek açık kasa
CREATE UNIQUE INDEX ux_cash_session_open ON cash_session (branch_id) WHERE closed_at IS NULL;

-- ---------------------------------------------------------------- ödeme hareketleri
-- Hareketler silinmez ve tutarları değişmez. Yalnızca PENDING → SUCCEEDED/FAILED geçişi yapılır.
-- CHARGE: tahsilat; REFUND: müşteriye iade; REVERSAL: hatalı girilen tahsilatın ters kaydı.
CREATE TABLE payment (
    id                bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    business_id       bigint        NOT NULL REFERENCES business (id),
    branch_id         bigint        NOT NULL REFERENCES branch (id),
    reservation_id    bigint        NOT NULL REFERENCES reservation (id),
    kind              varchar(10)   NOT NULL CHECK (kind IN ('CHARGE', 'REFUND', 'REVERSAL')),
    method            varchar(20)   NOT NULL CHECK (method IN ('CASH', 'MANUAL_POS', 'BANK_TRANSFER', 'ONLINE_SIM')),
    status            varchar(10)   NOT NULL CHECK (status IN ('PENDING', 'SUCCEEDED', 'FAILED')),
    amount            numeric(10,2) NOT NULL CHECK (amount > 0),
    currency          varchar(3)    NOT NULL,
    idempotency_key   varchar(80)   NOT NULL UNIQUE,
    related_payment_id bigint REFERENCES payment (id),
    provider_ref      varchar(80) UNIQUE,
    cash_session_id   bigint REFERENCES cash_session (id),
    payer_name        varchar(120),
    note              varchar(300),
    failure_reason    varchar(300),
    recorded_by       bigint REFERENCES app_user (id),
    created_at        timestamptz   NOT NULL,
    completed_at      timestamptz,
    CHECK (kind = 'CHARGE' OR related_payment_id IS NOT NULL),
    CHECK (method <> 'CASH' OR status <> 'SUCCEEDED' OR cash_session_id IS NOT NULL)
);
CREATE INDEX ix_payment_reservation ON payment (reservation_id);
-- Bir tahsilat en fazla bir kez ters kayıtla düzeltilebilir
CREATE UNIQUE INDEX ux_payment_one_reversal ON payment (related_payment_id) WHERE kind = 'REVERSAL';
CREATE INDEX ix_payment_branch_time ON payment (branch_id, created_at);
CREATE INDEX ix_payment_session ON payment (cash_session_id) WHERE cash_session_id IS NOT NULL;

-- Sağlayıcıdan gelen bildirimler. (provider, event_id) tekil: aynı bildirim ikinci kez işlenmez.
CREATE TABLE payment_event (
    id           bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    provider     varchar(20)  NOT NULL,
    event_id     varchar(80)  NOT NULL,
    provider_ref varchar(80)  NOT NULL,
    outcome      varchar(20)  NOT NULL,
    received_at  timestamptz  NOT NULL,
    UNIQUE (provider, event_id)
);

-- ---------------------------------------------------------------- giderler
-- Hatalı gider silinmez; reversal_of ile ters kaydı girilir.
CREATE TABLE expense (
    id              bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    business_id     bigint        NOT NULL REFERENCES business (id),
    branch_id       bigint        NOT NULL REFERENCES branch (id),
    category        varchar(30)   NOT NULL CHECK (category IN ('MAINTENANCE', 'UTILITIES', 'SUPPLIES', 'STAFF', 'OTHER')),
    amount          numeric(10,2) NOT NULL CHECK (amount <> 0),
    description     varchar(300)  NOT NULL,
    paid_from_cash  boolean       NOT NULL,
    cash_session_id bigint REFERENCES cash_session (id),
    reversal_of     bigint UNIQUE REFERENCES expense (id),
    recorded_by     bigint        NOT NULL REFERENCES app_user (id),
    created_at      timestamptz   NOT NULL,
    CHECK (NOT paid_from_cash OR cash_session_id IS NOT NULL),
    CHECK ((reversal_of IS NULL) = (amount > 0))
);
CREATE INDEX ix_expense_branch_time ON expense (branch_id, created_at);

-- ---------------------------------------------------------------- simülasyon sağlayıcısı
-- Bu iki tablo "dış ödeme sağlayıcısının" kendi kayıtlarını taklit eder. Uygulamanın geri kalanı
-- onlara doğrudan erişmez; yalnızca PaymentProvider arayüzü ve imzalı webhook üzerinden konuşur.
CREATE TABLE sim_charge (
    provider_ref   varchar(80)   PRIMARY KEY,
    amount         numeric(10,2) NOT NULL,
    status         varchar(10)   NOT NULL CHECK (status IN ('CREATED', 'SUCCEEDED', 'FAILED')),
    refund_fails   boolean       NOT NULL DEFAULT false,
    refunded       numeric(10,2) NOT NULL DEFAULT 0,
    created_at     timestamptz   NOT NULL
);

-- Gönderilecek webhook bildirimleri (gecikmeli ve yinelenen bildirim senaryoları için)
CREATE TABLE sim_webhook_outbox (
    id            bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    event_id      varchar(80)  NOT NULL,
    provider_ref  varchar(80)  NOT NULL REFERENCES sim_charge (provider_ref),
    outcome       varchar(20)  NOT NULL,
    deliver_at    timestamptz  NOT NULL,
    delivered_at  timestamptz
);
CREATE INDEX ix_sim_outbox_due ON sim_webhook_outbox (deliver_at) WHERE delivered_at IS NULL;

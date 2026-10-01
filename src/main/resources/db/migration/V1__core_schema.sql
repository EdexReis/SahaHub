-- SahaHub çekirdek şeması: kimlik, işletme/şube/saha, doluluk, rezervasyon, fiyat, denetim.
-- Tüm anlar timestamptz (UTC) olarak saklanır; iş kuralları şubenin zaman diliminde değerlendirilir.

-- EXCLUDE kısıtında bigint eşitliği (=) ile aralık kesişimini (&&) aynı GiST indeksinde
-- birleştirebilmek için gerekir.
CREATE EXTENSION IF NOT EXISTS btree_gist;

-- ---------------------------------------------------------------- kimlik
CREATE TABLE app_user (
    id              bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    email           varchar(254) NOT NULL,
    password_hash   varchar(200) NOT NULL,
    full_name       varchar(120) NOT NULL,
    phone           varchar(30),
    platform_admin  boolean      NOT NULL DEFAULT false,
    enabled         boolean      NOT NULL DEFAULT true,
    created_at      timestamptz  NOT NULL DEFAULT now()
);
-- E-posta büyük/küçük harf duyarsız tekil
CREATE UNIQUE INDEX ux_app_user_email ON app_user (lower(email));

-- ---------------------------------------------------------------- işletme
CREATE TABLE business (
    id          bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name        varchar(120) NOT NULL,
    description varchar(2000),
    phone       varchar(30),
    email       varchar(254),
    status      varchar(20)  NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'SUSPENDED', 'ARCHIVED')),
    created_at  timestamptz  NOT NULL DEFAULT now()
);

CREATE TABLE branch (
    id                        bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    business_id               bigint       NOT NULL REFERENCES business (id),
    name                      varchar(120) NOT NULL,
    description               varchar(2000),
    phone                     varchar(30),
    address_line              varchar(300) NOT NULL,
    district                  varchar(80)  NOT NULL,
    city                      varchar(80)  NOT NULL,
    time_zone                 varchar(60)  NOT NULL DEFAULT 'Europe/Istanbul',
    hold_minutes              integer      NOT NULL DEFAULT 10 CHECK (hold_minutes BETWEEN 1 AND 120),
    customer_cancel_cutoff_h  integer      NOT NULL DEFAULT 24 CHECK (customer_cancel_cutoff_h BETWEEN 0 AND 336),
    booking_horizon_days      integer      NOT NULL DEFAULT 30 CHECK (booking_horizon_days BETWEEN 1 AND 365),
    archived                  boolean      NOT NULL DEFAULT false,
    created_at                timestamptz  NOT NULL DEFAULT now()
);
CREATE INDEX ix_branch_business ON branch (business_id);

-- Haftalık çalışma saatleri. close_time <= open_time ise kapanış ertesi güne sarkar (ör. 10:00-02:00).
CREATE TABLE branch_opening_hours (
    branch_id   bigint   NOT NULL REFERENCES branch (id),
    day_of_week smallint NOT NULL CHECK (day_of_week BETWEEN 1 AND 7), -- 1=Pazartesi (ISO)
    closed      boolean  NOT NULL DEFAULT false,
    open_time   time,
    close_time  time,
    PRIMARY KEY (branch_id, day_of_week),
    CHECK (closed OR (open_time IS NOT NULL AND close_time IS NOT NULL))
);

-- Tatil veya özel çalışma saati: o gün için haftalık planın yerine geçer.
CREATE TABLE branch_special_day (
    id          bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    branch_id   bigint       NOT NULL REFERENCES branch (id),
    day         date         NOT NULL,
    closed      boolean      NOT NULL,
    open_time   time,
    close_time  time,
    note        varchar(200),
    UNIQUE (branch_id, day),
    CHECK (closed OR (open_time IS NOT NULL AND close_time IS NOT NULL))
);

-- Personel atamaları. OWNER işletmenin tamamında yetkilidir (branch_id NULL);
-- BRANCH_MANAGER ve RECEPTION yalnızca atandığı şubede.
CREATE TABLE staff_membership (
    id          bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id     bigint      NOT NULL REFERENCES app_user (id),
    business_id bigint      NOT NULL REFERENCES business (id),
    branch_id   bigint      REFERENCES branch (id),
    role        varchar(20) NOT NULL CHECK (role IN ('OWNER', 'BRANCH_MANAGER', 'RECEPTION')),
    active      boolean     NOT NULL DEFAULT true,
    created_at  timestamptz NOT NULL DEFAULT now(),
    CHECK ((role = 'OWNER') = (branch_id IS NULL))
);
CREATE UNIQUE INDEX ux_staff_membership ON staff_membership (user_id, business_id, coalesce(branch_id, 0));
CREATE INDEX ix_staff_membership_user ON staff_membership (user_id) WHERE active;

-- ---------------------------------------------------------------- saha
CREATE TABLE pitch (
    id                 bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    branch_id          bigint        NOT NULL REFERENCES branch (id),
    name               varchar(80)   NOT NULL,
    description        varchar(2000),
    capacity_players   integer       NOT NULL CHECK (capacity_players BETWEEN 2 AND 40),
    length_m           integer       CHECK (length_m BETWEEN 10 AND 150),
    width_m            integer       CHECK (width_m BETWEEN 5 AND 100),
    indoor             boolean       NOT NULL DEFAULT false,
    surface            varchar(30)   NOT NULL CHECK (surface IN ('ARTIFICIAL_TURF', 'NATURAL_GRASS', 'PARQUET', 'RUBBER')),
    has_lighting       boolean       NOT NULL DEFAULT true,
    has_parking        boolean       NOT NULL DEFAULT false,
    has_shower         boolean       NOT NULL DEFAULT false,
    has_locker_room    boolean       NOT NULL DEFAULT false,
    slot_minutes       integer       NOT NULL DEFAULT 60 CHECK (slot_minutes BETWEEN 30 AND 240 AND slot_minutes % 15 = 0),
    slot_step_minutes  integer       NOT NULL DEFAULT 60 CHECK (slot_step_minutes BETWEEN 15 AND 240 AND slot_step_minutes % 15 = 0),
    buffer_minutes     integer       NOT NULL DEFAULT 0 CHECK (buffer_minutes BETWEEN 0 AND 120),
    base_hourly_price  numeric(10,2) NOT NULL CHECK (base_hourly_price >= 0),
    currency           varchar(3)    NOT NULL DEFAULT 'TRY' CHECK (length(currency) = 3),
    photo_path         varchar(200),
    active             boolean       NOT NULL DEFAULT true,
    created_at         timestamptz   NOT NULL DEFAULT now()
);
CREATE INDEX ix_pitch_branch ON pitch (branch_id);

-- Bakım, özel etkinlik veya işletme kararıyla kapatma.
CREATE TABLE pitch_block (
    id           bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    pitch_id     bigint       NOT NULL REFERENCES pitch (id),
    starts_at    timestamptz  NOT NULL,
    ends_at      timestamptz  NOT NULL,
    reason       varchar(20)  NOT NULL CHECK (reason IN ('MAINTENANCE', 'EVENT', 'MANAGEMENT')),
    note         varchar(300),
    created_by   bigint       NOT NULL REFERENCES app_user (id),
    cancelled    boolean      NOT NULL DEFAULT false,
    created_at   timestamptz  NOT NULL DEFAULT now(),
    CHECK (ends_at > starts_at)
);
CREATE INDEX ix_pitch_block_pitch_time ON pitch_block (pitch_id, starts_at);

-- ---------------------------------------------------------------- doluluk
-- Bir sahayı meşgul eden HER kaynak (rezervasyon, bakım bloğu, ileride turnuva maçı)
-- buraya bir satır ekler. Çakışma güvencesi tek yerde, veritabanı seviyesinde sağlanır:
-- aynı sahada active=true olan iki satırın [starts_at, ends_at) aralıkları kesişemez.
-- Rezervasyonların ends_at değeri hazırlık süresini (buffer) içerir.
-- "Şimdiki zaman" kısıtta kullanılmaz: aktiflik açık durum geçişleriyle (active=false) kapatılır.
CREATE TABLE pitch_occupancy (
    id           bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    pitch_id     bigint      NOT NULL REFERENCES pitch (id),
    starts_at    timestamptz NOT NULL,
    ends_at      timestamptz NOT NULL,
    source_type  varchar(20) NOT NULL CHECK (source_type IN ('RESERVATION', 'BLOCK', 'TOURNAMENT_MATCH')),
    source_id    bigint      NOT NULL,
    active       boolean     NOT NULL DEFAULT true,
    CHECK (ends_at > starts_at),
    CONSTRAINT ex_pitch_occupancy_no_overlap EXCLUDE USING gist (
        pitch_id WITH =,
        tstzrange(starts_at, ends_at, '[)') WITH &&
    ) WHERE (active)
);
CREATE UNIQUE INDEX ux_pitch_occupancy_source ON pitch_occupancy (source_type, source_id) WHERE active;
CREATE INDEX ix_pitch_occupancy_lookup ON pitch_occupancy (pitch_id, starts_at) WHERE active;

-- ---------------------------------------------------------------- fiyat
-- Eşleşen kurallardan en yüksek priority kazanır; eşitlikte daha yeni (büyük id) kural.
-- Hiçbiri eşleşmezse sahanın base_hourly_price değeri kullanılır.
CREATE TABLE price_rule (
    id            bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    pitch_id      bigint        NOT NULL REFERENCES pitch (id),
    name          varchar(80)   NOT NULL,
    days_mask     smallint      NOT NULL CHECK (days_mask BETWEEN 1 AND 127), -- bit0=Pzt ... bit6=Paz
    start_time    time          NOT NULL,
    end_time      time,          -- NULL = gece yarısına kadar
    valid_from    date,
    valid_to      date,
    hourly_price  numeric(10,2) NOT NULL CHECK (hourly_price >= 0),
    priority      integer       NOT NULL DEFAULT 0,
    active        boolean       NOT NULL DEFAULT true,
    CHECK (end_time IS NULL OR end_time > start_time),
    CHECK (valid_to IS NULL OR valid_from IS NULL OR valid_to >= valid_from)
);
CREATE INDEX ix_price_rule_pitch ON price_rule (pitch_id) WHERE active;

-- ---------------------------------------------------------------- rezervasyon
CREATE TABLE reservation (
    id                bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code              varchar(12)   NOT NULL UNIQUE,
    business_id       bigint        NOT NULL REFERENCES business (id),
    branch_id         bigint        NOT NULL REFERENCES branch (id),
    pitch_id          bigint        NOT NULL REFERENCES pitch (id),
    customer_id       bigint        REFERENCES app_user (id),
    guest_name        varchar(120),
    guest_phone       varchar(30),
    starts_at         timestamptz   NOT NULL,
    ends_at           timestamptz   NOT NULL,
    buffer_minutes    integer       NOT NULL,
    status            varchar(20)   NOT NULL CHECK (status IN ('HELD', 'CONFIRMED', 'CANCELLED', 'COMPLETED', 'NO_SHOW', 'EXPIRED')),
    hold_expires_at   timestamptz,
    channel           varchar(20)   NOT NULL CHECK (channel IN ('ONLINE', 'PHONE', 'WALK_IN')),
    note              varchar(500),
    total_amount      numeric(10,2) NOT NULL,
    currency          varchar(3)    NOT NULL CHECK (length(currency) = 3),
    checked_in_at     timestamptz,
    cancelled_at      timestamptz,
    cancel_reason     varchar(300),
    cancelled_by      bigint        REFERENCES app_user (id),
    created_by        bigint        REFERENCES app_user (id),
    created_at        timestamptz   NOT NULL DEFAULT now(),
    updated_at        timestamptz   NOT NULL DEFAULT now(),
    version           bigint        NOT NULL DEFAULT 0,
    CHECK (ends_at > starts_at),
    CHECK (customer_id IS NOT NULL OR guest_name IS NOT NULL),
    CHECK (status <> 'HELD' OR hold_expires_at IS NOT NULL)
);
CREATE INDEX ix_reservation_branch_time ON reservation (branch_id, starts_at);
CREATE INDEX ix_reservation_customer ON reservation (customer_id, starts_at DESC);
CREATE INDEX ix_reservation_held ON reservation (hold_expires_at) WHERE status = 'HELD';

-- Rezervasyon anındaki fiyatın değişmez anlık görüntüsü.
CREATE TABLE reservation_price_line (
    id              bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    reservation_id  bigint        NOT NULL REFERENCES reservation (id),
    line_no         integer       NOT NULL,
    label           varchar(120)  NOT NULL,
    starts_at       timestamptz   NOT NULL,
    ends_at         timestamptz   NOT NULL,
    minutes         integer       NOT NULL,
    hourly_rate     numeric(10,2) NOT NULL,
    amount          numeric(10,2) NOT NULL,
    price_rule_id   bigint,
    UNIQUE (reservation_id, line_no)
);

-- ---------------------------------------------------------------- denetim
CREATE TABLE audit_event (
    id           bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    occurred_at  timestamptz  NOT NULL DEFAULT now(),
    actor_id     bigint       REFERENCES app_user (id),
    business_id  bigint       REFERENCES business (id),
    action       varchar(60)  NOT NULL,
    entity_type  varchar(40)  NOT NULL,
    entity_id    bigint,
    details      varchar(2000),
    request_id   varchar(40)
);
CREATE INDEX ix_audit_business_time ON audit_event (business_id, occurred_at DESC);

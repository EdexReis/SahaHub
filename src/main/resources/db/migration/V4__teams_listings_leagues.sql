-- Aşama 5: takımlar, oyuncu/rakip ilanları, lig.

-- ---------------------------------------------------------------- takımlar
-- Kaptan ayrı bir sütunda tutulmaz; team_member.role = 'CAPTAIN' olan aktif üyedir (tek kaynak).
CREATE TABLE team (
    id           bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name         varchar(60)  NOT NULL,
    city         varchar(60)  NOT NULL,
    invite_code  varchar(16)  NOT NULL UNIQUE,
    created_at   timestamptz  NOT NULL,
    disbanded_at timestamptz
);

CREATE TABLE team_member (
    id        bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    team_id   bigint      NOT NULL REFERENCES team (id),
    user_id   bigint      NOT NULL REFERENCES app_user (id),
    role      varchar(10) NOT NULL CHECK (role IN ('CAPTAIN', 'MEMBER')),
    joined_at timestamptz NOT NULL,
    left_at   timestamptz
);
-- Bir kişi bir takımda bir kez; her takımda tek aktif kaptan
CREATE UNIQUE INDEX ux_team_member_active ON team_member (team_id, user_id) WHERE left_at IS NULL;
CREATE UNIQUE INDEX ux_team_one_captain ON team_member (team_id) WHERE left_at IS NULL AND role = 'CAPTAIN';
CREATE INDEX ix_team_member_user ON team_member (user_id) WHERE left_at IS NULL;

-- ---------------------------------------------------------------- ilanlar
-- Süre dolumu ayrı bir durum değildir: expires_at <= şimdi olan OPEN ilan "süresi doldu" sayılır.
CREATE TABLE listing (
    id             bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    kind           varchar(20)  NOT NULL CHECK (kind IN ('PLAYERS_WANTED', 'OPPONENT_WANTED')),
    team_id        bigint       NOT NULL REFERENCES team (id),
    author_id      bigint       NOT NULL REFERENCES app_user (id),
    reservation_id bigint       REFERENCES reservation (id),
    city           varchar(60)  NOT NULL,
    district       varchar(60),
    play_at        timestamptz,
    players_needed integer      CHECK (players_needed BETWEEN 1 AND 11),
    level          varchar(12)  NOT NULL CHECK (level IN ('CASUAL', 'INTERMEDIATE', 'COMPETITIVE')),
    note           varchar(500),
    status         varchar(10)  NOT NULL CHECK (status IN ('OPEN', 'FILLED', 'CLOSED')),
    expires_at     timestamptz  NOT NULL,
    created_at     timestamptz  NOT NULL,
    closed_at      timestamptz,
    version        bigint       NOT NULL DEFAULT 0,
    CHECK ((kind = 'PLAYERS_WANTED') = (players_needed IS NOT NULL)),
    CHECK ((status = 'OPEN') = (closed_at IS NULL))
);
CREATE INDEX ix_listing_open ON listing (city, expires_at) WHERE status = 'OPEN';
CREATE INDEX ix_listing_reservation ON listing (reservation_id) WHERE reservation_id IS NOT NULL;

CREATE TABLE listing_application (
    id                bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    listing_id        bigint       NOT NULL REFERENCES listing (id),
    applicant_id      bigint       NOT NULL REFERENCES app_user (id),
    applicant_team_id bigint       REFERENCES team (id),
    message           varchar(300),
    status            varchar(10)  NOT NULL CHECK (status IN ('PENDING', 'ACCEPTED', 'REJECTED', 'WITHDRAWN')),
    created_at        timestamptz  NOT NULL,
    decided_at        timestamptz,
    CHECK ((status = 'PENDING') = (decided_at IS NULL))
);
-- Aynı ilana bekleyen veya kabul edilmiş tek başvuru
CREATE UNIQUE INDEX ux_application_active ON listing_application (listing_id, applicant_id)
    WHERE status IN ('PENDING', 'ACCEPTED');
CREATE INDEX ix_application_applicant ON listing_application (applicant_id, created_at);

-- ---------------------------------------------------------------- lig
CREATE TABLE tournament (
    id           bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    business_id  bigint       NOT NULL REFERENCES business (id),
    branch_id    bigint       NOT NULL REFERENCES branch (id),
    name         varchar(80)  NOT NULL,
    format       varchar(10)  NOT NULL CHECK (format IN ('LEAGUE')),
    double_round boolean      NOT NULL DEFAULT false,
    points_win   integer      NOT NULL DEFAULT 3 CHECK (points_win BETWEEN 0 AND 10),
    points_draw  integer      NOT NULL DEFAULT 1 CHECK (points_draw BETWEEN 0 AND 10),
    points_loss  integer      NOT NULL DEFAULT 0 CHECK (points_loss BETWEEN 0 AND 10),
    status       varchar(10)  NOT NULL CHECK (status IN ('DRAFT', 'ACTIVE', 'FINISHED')),
    created_by   bigint       NOT NULL REFERENCES app_user (id),
    created_at   timestamptz  NOT NULL,
    started_at   timestamptz,
    finished_at  timestamptz,
    version      bigint       NOT NULL DEFAULT 0
);
CREATE INDEX ix_tournament_branch ON tournament (branch_id, created_at);

CREATE TABLE tournament_entry (
    id            bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tournament_id bigint       NOT NULL REFERENCES tournament (id),
    name          varchar(60)  NOT NULL,
    created_at    timestamptz  NOT NULL
);
CREATE UNIQUE INDEX ux_tournament_entry_name ON tournament_entry (tournament_id, lower(name));

-- Planlanan maç pitch_occupancy'ye source_type = 'TOURNAMENT_MATCH' ile yazılır; rezervasyonla aynı
-- EXCLUDE kısıtı çakışmayı engeller (V1'de bu kaynak türü öngörülmüştü).
CREATE TABLE tournament_match (
    id            bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tournament_id bigint       NOT NULL REFERENCES tournament (id),
    round         integer      NOT NULL CHECK (round >= 1),
    home_entry_id bigint       NOT NULL REFERENCES tournament_entry (id),
    away_entry_id bigint       NOT NULL REFERENCES tournament_entry (id),
    pitch_id      bigint       REFERENCES pitch (id),
    starts_at     timestamptz,
    ends_at       timestamptz,
    status        varchar(12)  NOT NULL CHECK (status IN ('UNSCHEDULED', 'SCHEDULED', 'PLAYED')),
    home_score    integer      CHECK (home_score BETWEEN 0 AND 99),
    away_score    integer      CHECK (away_score BETWEEN 0 AND 99),
    version       bigint       NOT NULL DEFAULT 0,
    CHECK (home_entry_id <> away_entry_id),
    CHECK ((pitch_id IS NULL) = (starts_at IS NULL) AND (starts_at IS NULL) = (ends_at IS NULL)),
    CHECK (ends_at > starts_at),
    CHECK ((status = 'UNSCHEDULED') = (starts_at IS NULL)),
    CHECK ((status = 'PLAYED') = (home_score IS NOT NULL) AND (home_score IS NULL) = (away_score IS NULL))
);
CREATE INDEX ix_tournament_match_t ON tournament_match (tournament_id, round, id);

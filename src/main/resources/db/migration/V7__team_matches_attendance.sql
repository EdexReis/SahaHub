-- Takım maçları, katılım yanıtları, takım açıklaması ve logosu.

ALTER TABLE team
    ADD COLUMN description varchar(300),
    ADD COLUMN logo_path   varchar(64);

-- Takımın maçı: kaptanın onaylı rezervasyonuna bağlı (yer/saat oradan) ya da serbest (başka tesiste maç).
CREATE TABLE team_match (
    id             bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    team_id        bigint       NOT NULL REFERENCES team (id),
    reservation_id bigint       REFERENCES reservation (id),
    starts_at      timestamptz  NOT NULL,
    place          varchar(120) NOT NULL,
    opponent       varchar(60),
    note           varchar(300),
    status         varchar(10)  NOT NULL CHECK (status IN ('SCHEDULED', 'PLAYED', 'CANCELLED')),
    our_score      integer      CHECK (our_score BETWEEN 0 AND 99),
    their_score    integer      CHECK (their_score BETWEEN 0 AND 99),
    created_by     bigint       NOT NULL REFERENCES app_user (id),
    created_at     timestamptz  NOT NULL,
    version        bigint       NOT NULL DEFAULT 0,
    CHECK ((status = 'PLAYED') = (our_score IS NOT NULL) AND (our_score IS NULL) = (their_score IS NULL))
);
CREATE INDEX ix_team_match_team ON team_match (team_id, starts_at);
-- Aynı rezervasyon aynı takıma iki kez maç olarak eklenmez
CREATE UNIQUE INDEX ux_team_match_reservation ON team_match (team_id, reservation_id)
    WHERE reservation_id IS NOT NULL AND status <> 'CANCELLED';

-- Üyenin yanıtı. Yanıt değiştirilebilir; kişi başına tek satır.
CREATE TABLE team_match_attendance (
    team_match_id bigint      NOT NULL REFERENCES team_match (id),
    user_id       bigint      NOT NULL REFERENCES app_user (id),
    status        varchar(10) NOT NULL CHECK (status IN ('GOING', 'MAYBE', 'NOT_GOING')),
    updated_at    timestamptz NOT NULL,
    PRIMARY KEY (team_match_id, user_id)
);

-- Lig/kupa kaydını platformdaki takıma bağlama.
-- Personel her kayıt için bir bağlantı kodu paylaşır; kodu açan takım kaptanı kendi takımını seçer (rıza onda).
ALTER TABLE tournament_entry
    ADD COLUMN link_code varchar(16),
    ADD COLUMN team_id   bigint REFERENCES team (id),
    ADD COLUMN linked_at timestamptz,
    ADD CONSTRAINT ck_entry_link CHECK ((team_id IS NULL) = (linked_at IS NULL));
-- Var olan kayıtlar için kod (rakam ve büyük harf); yeni kayıtlarda uygulama üretir
UPDATE tournament_entry SET link_code = upper(substr(md5(random()::text || id::text), 1, 12));
ALTER TABLE tournament_entry ALTER COLUMN link_code SET NOT NULL;
CREATE UNIQUE INDEX ux_tournament_entry_link_code ON tournament_entry (link_code);
-- Bir takım aynı turnuvada iki kayda bağlanamaz
CREATE UNIQUE INDEX ux_tournament_entry_team ON tournament_entry (tournament_id, team_id) WHERE team_id IS NOT NULL;
CREATE INDEX ix_tournament_entry_team ON tournament_entry (team_id) WHERE team_id IS NOT NULL;

-- Bağlı takımın lig maçları takım maçı olarak görünür (katılım yanıtı, hatırlatma, geçmiş).
ALTER TABLE team_match
    ADD COLUMN tournament_id       bigint REFERENCES tournament (id),
    ADD COLUMN tournament_match_id bigint REFERENCES tournament_match (id),
    ADD CONSTRAINT ck_team_match_tournament CHECK ((tournament_id IS NULL) = (tournament_match_id IS NULL)),
    ADD CONSTRAINT ck_team_match_one_source CHECK (tournament_match_id IS NULL OR reservation_id IS NULL);
CREATE UNIQUE INDEX ux_team_match_tournament ON team_match (team_id, tournament_match_id)
    WHERE tournament_match_id IS NOT NULL AND status <> 'CANCELLED';
CREATE INDEX ix_team_match_tournament_match ON team_match (tournament_match_id) WHERE tournament_match_id IS NOT NULL;

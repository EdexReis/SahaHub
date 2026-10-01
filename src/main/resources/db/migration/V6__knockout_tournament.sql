-- Eleme usulü turnuva (kupa).

ALTER TABLE tournament DROP CONSTRAINT tournament_format_check;
ALTER TABLE tournament ADD CONSTRAINT tournament_format_check CHECK (format IN ('LEAGUE', 'KNOCKOUT'));

-- Eleme maçları ağaçtaki yerleriyle (tur, sıra) tutulur. Bir maç satırı yalnızca iki takım da belli olunca
-- oluşturulur; aynı yere ikinci maç açılamaz.
ALTER TABLE tournament_match
    ADD COLUMN bracket_slot          integer CHECK (bracket_slot >= 0),
    ADD COLUMN winner_entry_id       bigint  REFERENCES tournament_entry (id),
    ADD COLUMN decided_by_penalties  boolean NOT NULL DEFAULT false,
    ADD CONSTRAINT ck_match_winner_side
        CHECK (winner_entry_id IS NULL OR winner_entry_id = home_entry_id OR winner_entry_id = away_entry_id),
    -- Oynanmış eleme maçının galibi olmalı
    ADD CONSTRAINT ck_match_knockout_winner
        CHECK (bracket_slot IS NULL OR status <> 'PLAYED' OR winner_entry_id IS NOT NULL),
    -- Penaltı yalnızca beraberlikte; eleme maçında beraberlik penaltısız bitemez
    ADD CONSTRAINT ck_match_penalties CHECK (NOT decided_by_penalties OR home_score = away_score),
    ADD CONSTRAINT ck_match_knockout_draw
        CHECK (bracket_slot IS NULL OR status <> 'PLAYED' OR home_score <> away_score OR decided_by_penalties);
CREATE UNIQUE INDEX ux_tournament_match_bracket ON tournament_match (tournament_id, round, bracket_slot)
    WHERE bracket_slot IS NOT NULL;

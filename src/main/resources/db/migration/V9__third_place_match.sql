-- Kupada üçüncülük maçı (isteğe bağlı): yarı finalde kaybedenler oynar. Maç satırı finalle aynı turda,
-- bracket_slot = 1 olarak tutulur (final 0); ux_tournament_match_bracket aynı yere ikinci maçı engeller.
ALTER TABLE tournament
    ADD COLUMN third_place boolean NOT NULL DEFAULT false,
    ADD CONSTRAINT ck_tournament_third_place CHECK (NOT third_place OR format = 'KNOCKOUT');

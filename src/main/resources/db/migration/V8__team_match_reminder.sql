-- Takım maçı hatırlatması: zamanlanmış görev "önümüzdeki 24 saatte başlayan planlı maçları" arar.
CREATE INDEX ix_team_match_scheduled_start ON team_match (starts_at) WHERE status = 'SCHEDULED';

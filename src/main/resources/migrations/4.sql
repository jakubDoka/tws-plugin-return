INSERT INTO game (map, wave, finished_at)
	SELECT name, max_wave, unixepoch() FROM map_score;

ALTER TABLE map_score ADD COLUMN switches INTEGER NOT NULL DEFAULT 0;
ALTER TABLE map_score DROP COLUMN shortest_playtime;
ALTER TABLE map_score DROP COLUMN longest_playtime;
ALTER TABLE map_score DROP COLUMN max_wave;

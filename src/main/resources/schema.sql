PRAGMA foreign_keys = ON;
PRAGMA busy_timeout = 5000;
PRAGMA journal_mode = WAL;

CREATE TABLE IF NOT EXISTS test (
	id INTEGER PRIMARY KEY
);

CREATE TABLE IF NOT EXISTS user (
	name TEXT PRIMARY KEY,
	password_hash TEXT NOT NULL,
	joined_at INTEGER NOT NULL DEFAULT (unixepoch()),
	rank TEXT NOT NULL DEFAULT "newcomer",
	discord_id TEXT DEFAULT NULL,

	blocks_broken INTEGER NOT NULL DEFAULT 0,
	blocks_placed INTEGER NOT NULL DEFAULT 0,
	play_time INTEGER NOT NULL DEFAULT 0,
	messages_sent INTEGER NOT NULL DEFAULT 0,
	commands_executed INTEGER NOT NULL DEFAULT 0,
	enemies_killed INTEGER NOT NULL DEFAULT 0,
	waves_survived INTEGER NOT NULL DEFAULT 0,
	afk_time INTEGER NOT NULL DEFAULT 0,
	blocks_destroyed INTEGER NOT NULL DEFAULT 0,
	deaths INTEGER NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS discord_id_index ON user (discord_id);

CREATE TABLE IF NOT EXISTS login (
	uuid TEXT PRIMARY KEY,
	name TEXT,
	FOREIGN KEY(name) REFERENCES user(name)
		ON DELETE CASCADE
		ON UPDATE CASCADE
);

CREATE TABLE IF NOT EXISTS appeal (
	-- can be a uuid or ip address
	appeal_key TEXT PRIMARY KEY
);

CREATE TABLE IF NOT EXISTS griefer (
	-- can be a uuid or ip address
	ban_key TEXT PRIMARY KEY
);

CREATE TABLE IF NOT EXISTS failed_test_sessions (
	name TEXT PRIMARY KEY,
	happened_at INTEGER NOT NULL DEFAULT (unixepoch()),
	FOREIGN KEY(name) REFERENCES user(name)
		ON DELETE CASCADE
		ON UPDATE CASCADE
);

CREATE TABLE IF NOT EXISTS map_score (
	name TEXT PRIMARY KEY,
	switches INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS game (
	id INTEGER PRIMARY KEY,
	map TEXT NOT NULL,
	started_at INTEGER NOT NULL DEFAULT (unixepoch()),
	finished_at INTEGER NOT NULL DEFAULT 0,
	wave INTEGER NOT NULL DEFAULT 0,
	peak_players INTEGER NOT NULL DEFAULT 0,
	won INTEGER NOT NULL DEFAULT 0,

	FOREIGN KEY(map) REFERENCES map_score(name)
		ON DELETE CASCADE
		ON UPDATE CASCADE
);

CREATE INDEX IF NOT EXISTS game_map_index ON game (map);

DROP   VIEW IF     EXISTS aggregated_map_score;
CREATE VIEW IF NOT EXISTS aggregated_map_score AS
	SELECT
		m.name                                         as name,
		m.switches                                     as switches,
		COALESCE(max(g.finished_at - g.started_at), 0) as max_gametime,
		min(g.finished_at - g.started_at)              as min_gametime,
		COALESCE(sum(g.finished_at - g.started_at), 0) as total_gametime,
		COALESCE(max(g.wave), 0)                       as max_wave,
		COALESCE(max(g.peak_players), 0)               as max_peak_players,
		COALESCE(sum(g.won), 0)                        as total_wins,
		count(g.map)                                   as total_games
	FROM map_score m
	LEFT JOIN game g ON m.name = g.map
	GROUP BY m.name;

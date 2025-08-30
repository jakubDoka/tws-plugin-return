CREATE TABLE IF NOT EXISTS user (
	name TEXT PRIMARY KEY,
	password_hash TEXT NOT NULL,
	joined_at INTEGER NOT NULL DEFAULT (unixepoch()),
	rank TEXT NOT NULL DEFAULT "newcomer",
	discord_id TEXT DEFAULT NULL,
	blocks_broken INTEGER NOT NULL DEFAULT 0,
	blocks_placed INTEGER NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS discord_id_index ON user (discord_id);

CREATE TABLE IF NOT EXISTS login (
	uuid TEXT PRIMARY KEY,
	name TEXT,
	FOREIGN KEY(name) REFERENCES user(name)
);

CREATE TABLE IF NOT EXISTS griefer (
	-- can be a uuid or ip address
	ban_key TEXT PRIMARY KEY
);

CREATE TABLE IF NOT EXISTS failed_test_sessions (
	name TEXT PRIMARY KEY,
	happened_at INTEGER NOT NULL DEFAULT (unixepoch())
);

CREATE TABLE IF NOT EXISTS map_score (
	name TEXT PRIMARY KEY,
	max_wave INTEGER NOT NULL DEFAULT 0,
	shortest_playtime INTEGER NOT NULL DEFAULT 9223372036854775807,
	longest_playtime INTEGER NOT NULL DEFAULT 0
);

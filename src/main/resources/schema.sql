CREATE TABLE IF NOT EXISTS user (
	name TEXT PRIMARY KEY,
	password_hash TEXT NOT NULL,
	joined_at INTEGER NOT NULL DEFAULT (unixepoch()),
	rank TEXT NOT NULL DEFAULT "newcommer",
	discord_name TEXT DEFAULT NULL,
	muted_players TEXT DEFAULT NULL,
	is_muted BOOLEAN NOT NULL DEFAULT FALSE
);

CREATE TABLE IF NOT EXISTS login (
	uuid TEXT PRIMARY KEY,
	name TEXT,
	FOREIGN KEY(name) REFERENCES user(name)
)

PRAGMA foreign_keys = OFF;

CREATE TABLE IF NOT EXISTS new_login (
	uuid TEXT PRIMARY KEY,
	name TEXT,
	FOREIGN KEY(name) REFERENCES user(name)
		ON DELETE CASCADE
		ON UPDATE CASCADE
);

INSERT INTO new_login SELECT uuid, name FROM login;
DROP TABLE login;
ALTER TABLE new_login RENAME TO login;


CREATE TABLE IF NOT EXISTS new_failed_test_sessions (
	name TEXT PRIMARY KEY,
	happened_at INTEGER NOT NULL DEFAULT (unixepoch()),
	FOREIGN KEY(name) REFERENCES user(name)
		ON DELETE CASCADE
		ON UPDATE CASCADE
);

INSERT INTO new_failed_test_sessions SELECT name, happened_at FROM failed_test_sessions;
DROP TABLE failed_test_sessions;
ALTER TABLE new_failed_test_sessions RENAME TO failed_test_sessions;

PRAGMA foreign_keys = OFF;

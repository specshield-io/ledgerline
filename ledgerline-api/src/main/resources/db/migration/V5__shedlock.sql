-- Ledgerline V5: ShedLock's lock table, so the demo reset (nightly job, admin endpoint, startup seeding) never runs
-- twice at once, even across instances. timestamptz like every other timestamp; the provider binds UTC instants.

CREATE TABLE shedlock (
    name       varchar(64)  PRIMARY KEY,
    lock_until timestamptz  NOT NULL,
    locked_at  timestamptz  NOT NULL,
    locked_by  varchar(255) NOT NULL
);

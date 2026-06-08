-- ShedLock table — Oracle-specific column types per ShedLock docs.
-- DO NOT swap these for the PostgreSQL types; Oracle TIMESTAMP without
-- precision is seconds-only and will cause the lock to behave unexpectedly.
CREATE TABLE shedlock (
    name        VARCHAR2(64)  NOT NULL,
    lock_until  TIMESTAMP(3)  NOT NULL,
    locked_at   TIMESTAMP(3)  NOT NULL,
    locked_by   VARCHAR2(255) NOT NULL,
    CONSTRAINT pk_shedlock PRIMARY KEY (name)
);

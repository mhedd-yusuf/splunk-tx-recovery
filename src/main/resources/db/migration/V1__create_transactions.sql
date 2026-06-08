-- Transactions ingested from Splunk.
-- The natural key is `id` (the upstream transaction id). The UNIQUE constraint
-- is what makes Oracle MERGE idempotent: re-reads of the overlap window cannot
-- produce duplicates.
--
-- Column types are Oracle-flavoured:
--   VARCHAR2 (not VARCHAR), NUMBER(19,4) (not NUMERIC), TIMESTAMP WITH TIME ZONE
--   for instants, CLOB for raw_payload (which may exceed the 4000-byte
--   VARCHAR2 limit on extended-string-disabled databases).
CREATE TABLE transactions (
    id              VARCHAR2(128)               NOT NULL,
    ts              TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    amount          NUMBER(19, 4),
    status          VARCHAR2(32),
    raw_payload     CLOB,
    created_at      TIMESTAMP(6) WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
    CONSTRAINT pk_transactions PRIMARY KEY (id)
);

CREATE INDEX idx_transactions_ts ON transactions (ts);

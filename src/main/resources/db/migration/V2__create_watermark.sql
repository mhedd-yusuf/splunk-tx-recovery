-- A single-row table holding the timestamp of the last successfully processed
-- Splunk event. `name` exists only as a primary key so the row can be looked up
-- by a stable string ('splunk') instead of by ROWID.
--
-- Initial row is inserted with ts = epoch so the first scheduled run reads from
-- the beginning of time. Operators wanting a different start point should
-- update the row before the first run (see docs/runbook.md).
CREATE TABLE watermark (
    name        VARCHAR2(32)                NOT NULL,
    ts          TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    updated_at  TIMESTAMP(6) WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
    CONSTRAINT pk_watermark PRIMARY KEY (name)
);

INSERT INTO watermark (name, ts) VALUES ('splunk', TIMESTAMP '1970-01-01 00:00:00.000 +00:00');

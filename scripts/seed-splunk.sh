#!/usr/bin/env bash
# Bootstrap the local Splunk for end-to-end testing:
#   1. Create the `app_logs` index (idempotent)
#   2. Push N synthetic `transactions` events via HEC
#
# Reads the same defaults baked into docker-compose.yml. Override via env if
# needed. Safe to run repeatedly — duplicate ids land in Splunk but are
# absorbed by Oracle MERGE on the consumer side, which is the whole point.
#
# Usage:
#     ./scripts/seed-splunk.sh            # 20 events, default creds
#     COUNT=100 ./scripts/seed-splunk.sh  # 100 events
#
set -euo pipefail

SPLUNK_MGMT="${SPLUNK_MGMT:-https://localhost:8089}"
SPLUNK_HEC="${SPLUNK_HEC:-https://localhost:8088}"
SPLUNK_USER="${SPLUNK_USER:-admin}"
SPLUNK_PASS="${SPLUNK_PASS:-changeme-please}"
HEC_TOKEN="${HEC_TOKEN:-00000000-0000-0000-0000-000000000000}"
INDEX="${INDEX:-app_logs}"
SOURCETYPE="${SOURCETYPE:-transactions}"
COUNT="${COUNT:-20}"

bold()  { printf '\033[1m%s\033[0m\n' "$*"; }
ok()    { printf '  \033[32m✓\033[0m %s\n' "$*"; }
warn()  { printf '  \033[33m!\033[0m %s\n' "$*"; }
die()   { printf '  \033[31m✗\033[0m %s\n' "$*" >&2; exit 1; }

bold "1. Probe Splunk mgmt API at $SPLUNK_MGMT"
if ! curl -sSk -o /dev/null --max-time 5 "$SPLUNK_MGMT/services/server/info"; then
    die "Splunk mgmt API unreachable. Is 'docker compose up -d splunk' running?"
fi
ok "mgmt API reachable"

bold "2. Ensure index '$INDEX' exists"
http_status=$(curl -sSk -o /tmp/seed-splunk-resp.json -w '%{http_code}' \
    -u "$SPLUNK_USER:$SPLUNK_PASS" \
    "$SPLUNK_MGMT/services/data/indexes/$INDEX?output_mode=json") || true

case "$http_status" in
    200)
        ok "index already exists"
        ;;
    404)
        create_status=$(curl -sSk -o /tmp/seed-splunk-resp.json -w '%{http_code}' \
            -u "$SPLUNK_USER:$SPLUNK_PASS" \
            -X POST "$SPLUNK_MGMT/services/data/indexes?output_mode=json" \
            -d "name=$INDEX")
        if [ "$create_status" = "201" ]; then
            ok "index created"
        else
            cat /tmp/seed-splunk-resp.json
            die "failed to create index (HTTP $create_status)"
        fi
        ;;
    *)
        cat /tmp/seed-splunk-resp.json
        die "unexpected status $http_status from indexes API"
        ;;
esac

bold "3. Verify HEC is reachable at $SPLUNK_HEC"
hec_health=$(curl -sSk -o /dev/null -w '%{http_code}' --max-time 5 \
    "$SPLUNK_HEC/services/collector/health") || true
if [ "$hec_health" != "200" ]; then
    die "HEC health check failed (HTTP $hec_health). Confirm SPLUNK_HEC_TOKEN env in compose and that port 8088 is published."
fi
ok "HEC healthy"

bold "4. Push $COUNT events into $INDEX (sourcetype=$SOURCETYPE)"
now_epoch=$(date +%s)
payload_file=$(mktemp)
trap 'rm -f "$payload_file" /tmp/seed-splunk-resp.json' EXIT

# Build a single HEC batch — each JSON object on its own line. HEC parses them
# in order. We stagger timestamps over the past hour so the recovery service
# can pick them up within its overlap window.
: > "$payload_file"
for i in $(seq 1 "$COUNT"); do
    ts=$(( now_epoch - (COUNT - i) * 30 ))   # one event every 30s, ending now
    id="tx-$(date +%s)-$i"
    amount=$(awk -v i="$i" 'BEGIN{printf "%.2f", (i * 7.13) }')
    status=$([ $((i % 5)) -eq 0 ] && echo "REVERSED" || echo "OK")
    printf '{"time":%s,"index":"%s","sourcetype":"%s","event":{"id":"%s","amount":%s,"status":"%s"}}\n' \
        "$ts" "$INDEX" "$SOURCETYPE" "$id" "$amount" "$status" >> "$payload_file"
done

hec_status=$(curl -sSk -o /tmp/seed-splunk-resp.json -w '%{http_code}' \
    -H "Authorization: Splunk $HEC_TOKEN" \
    -H "Content-Type: application/json" \
    --data-binary "@$payload_file" \
    "$SPLUNK_HEC/services/collector/event")

if [ "$hec_status" = "200" ]; then
    ok "$COUNT events accepted by HEC"
else
    cat /tmp/seed-splunk-resp.json
    die "HEC rejected the batch (HTTP $hec_status)"
fi

bold "5. Verify with a quick SPL query"
# Splunk indexes events asynchronously — give it a couple of seconds.
sleep 3
search_status=$(curl -sSk -o /tmp/seed-splunk-resp.json -w '%{http_code}' \
    -u "$SPLUNK_USER:$SPLUNK_PASS" \
    -d "search=search index=$INDEX sourcetype=$SOURCETYPE earliest=-1h | stats count" \
    -d "output_mode=json" -d "exec_mode=oneshot" \
    "$SPLUNK_MGMT/services/search/jobs")

if [ "$search_status" = "200" ]; then
    indexed=$(python3 -c "import json,sys;d=json.load(open('/tmp/seed-splunk-resp.json'));print(d['results'][0]['count'] if d.get('results') else 0)" 2>/dev/null || echo "?")
    ok "Splunk reports $indexed events in $INDEX in the last hour"
else
    warn "search probe returned HTTP $search_status (events may still be in transit; try the manual SPL below)"
fi

echo
bold "Done. The tx-recovery service will pick these up on its next scheduled run."
echo
echo "Manual checks:"
echo "  - Splunk UI:   http://localhost:8000  → Search & Reporting"
echo "                 search index=$INDEX sourcetype=$SOURCETYPE | table id _time amount status"
echo "  - DB rows:     docker exec -it tx-recovery-oracle sqlplus appuser/appuser@//localhost:1521/FREEPDB1"
echo "                 SQL> SELECT id, ts, amount, status FROM transactions ORDER BY ts DESC FETCH FIRST 10 ROWS ONLY;"
echo "  - App metrics: curl -s localhost:8080/actuator/prometheus | grep -E '^transactions_(inserted|skipped)_total'"

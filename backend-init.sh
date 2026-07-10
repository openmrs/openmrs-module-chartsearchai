#!/bin/sh
# Pre-fetch the ML model file this image needs into the OpenMRS appdata volume:
#   querystore/model.onnx ......... e5-base-v2 sentence embedder (~440MB)
#   querystore/vocab.txt .......... BERT WordPiece vocab for e5
#
# chartsearchai has no bundled inference runtime or retrieval pipeline. Chat relays to
# the configured med-agent-hub endpoint, and hub profiles own context sourcing.
# These files support a separately deployed querystore context source; there is
# no chartsearchai-side model weights to provision.
#
# Operator wiring required for querystore deployments:
#   querystore.embedding.modelFilePath  = querystore/model.onnx
#   querystore.embedding.vocabFilePath  = querystore/vocab.txt
#   querystore.embedding.queryModelFilePath = (empty — single encoder)

# When started as root (deployments without a separate init container that
# chowns the volume), heal pre-Apr-27 root-owned contents and drop to the
# openmrs user. The application itself always runs as uid 1001.
if [ "$(id -u)" = "0" ]; then
  chown -R 1001:1001 /openmrs/data 2>/dev/null || true
  exec runuser -u openmrs -- "$0" "$@"
fi

QS_DIR="/openmrs/data/querystore"
mkdir -p "$QS_DIR"

# ---- embedder: e5-base-v2 ---------------------------------------------------
# e5-base-v2 is provisioned for the optional Querystore context source in this
# demo distribution. Selection and quality evaluation belong to Querystore and
# the validation harness, not ChartSearchAI.
# Source is Xenova's ONNX export rather than `intfloat/e5-base-v2`'s
# canonical repo: Xenova ships a self-contained model.onnx file, while
# the canonical repo's onnx/ subdirectory uses external-data format
# (graph file + separate `model.onnx_data` weights sidecar). Downloading
# only the graph file produces a tiny "successful" file that the ONNX
# runtime can open but cannot execute — the bug shape that broke the
# previous L6-v2 download. The size guard below is the second line of
# defense against any future upstream format change.
ONNX_FILE="$QS_DIR/model.onnx"
VOCAB_FILE="$QS_DIR/vocab.txt"
HF_EMBED="https://huggingface.co/Xenova/e5-base-v2/resolve/main"
ONNX_MIN_BYTES=200000000  # ~200MB; well under e5's ~440MB self-contained size
VOCAB_MIN_BYTES=200000    # ~200KB; well under e5's ~230KB vocab.txt

if [ ! -f "$ONNX_FILE" ]; then
  echo "Downloading e5-base-v2 ONNX model (~440MB)..."
  # --speed-time/--speed-limit abort a stalled connection after 60s rather
  # than hanging the container start indefinitely; a partial file then
  # trips the size guard below, which rm's it so the next start retries
  # from zero.
  curl -fsSL --speed-time 60 --speed-limit 1024 -o "$ONNX_FILE" "$HF_EMBED/onnx/model.onnx"
fi

# Size guard. A graph-only ONNX file from an external-data export is
# ~1MB and the runtime fails late, at first inference, with a misleading
# "Not a directory" error trying to read a sidecar weights file that
# doesn't exist next to the graph. Catch that at download time.
ONNX_BYTES=$(stat -c %s "$ONNX_FILE" 2>/dev/null || stat -f %z "$ONNX_FILE" 2>/dev/null || echo 0)
if [ "$ONNX_BYTES" -lt "$ONNX_MIN_BYTES" ]; then
  echo "ERROR: $ONNX_FILE is only ${ONNX_BYTES} bytes (expected at least ${ONNX_MIN_BYTES})." >&2
  echo "       The HuggingFace ONNX export likely changed shape upstream." >&2
  echo "       Remove $ONNX_FILE and re-run; if the file is still small," >&2
  echo "       the source repo's onnx/model.onnx is now external-data format" >&2
  echo "       and the sidecar (model.onnx_data) must be staged alongside it." >&2
  rm -f "$ONNX_FILE"
  exit 1
fi
echo "Embedder ready: $ONNX_FILE (${ONNX_BYTES} bytes)."

if [ ! -f "$VOCAB_FILE" ]; then
  echo "Downloading e5-base-v2 vocab..."
  curl -fsSL --speed-time 60 --speed-limit 1024 -o "$VOCAB_FILE" "$HF_EMBED/vocab.txt"
fi

# Vocab size guard, parallel to the ONNX one. e5-base-v2's WordPiece vocab
# is ~230KB. A short or empty vocab would either fail tokenizer init or,
# worse, silently degrade embeddings as missing tokens fall back to [UNK]
# — both modes are easier to diagnose at startup than at first query.
VOCAB_BYTES=$(stat -c %s "$VOCAB_FILE" 2>/dev/null || stat -f %z "$VOCAB_FILE" 2>/dev/null || echo 0)
if [ "$VOCAB_BYTES" -lt "$VOCAB_MIN_BYTES" ]; then
  echo "ERROR: $VOCAB_FILE is only ${VOCAB_BYTES} bytes (expected at least ${VOCAB_MIN_BYTES})." >&2
  echo "       Truncated vocab fails tokenizer init or silently degrades to [UNK] fallbacks." >&2
  echo "       Remove the file and re-run; if it stays small, the upstream export changed." >&2
  rm -f "$VOCAB_FILE"
  exit 1
fi
echo "Vocab ready: $VOCAB_FILE (${VOCAB_BYTES} bytes)."

# ---- one-shot demo dataset seed --------------------------------------------
# Load a fixed OpenMRS demo dump (5,284-patient reference set) into the database
# the first time a backend built with this entrypoint boots against a DB that
# hasn't been seeded with it yet. This is how the chartsearchai.openmrs.org demo
# gets its dataset without any direct DB access: the seed runs in-container
# against the `db` service over the compose network, on an ordinary deploy — no
# volume wipe, so the downloaded models and runtime properties are untouched.
#
# Safety:
#   * Gated by a sentinel global property -> runs exactly once.
#   * Backs up the current DB to the persistent /openmrs/data volume first.
#   * On any failure it restores that backup (or leaves the existing data
#     untouched) and falls through to a normal start — a failed seed never
#     bricks the demo.
#   * Snapshots and restores the server's own chartsearchai/querystore global
#     properties, so the dump's config (LLM endpoint, model paths) does not
#     overwrite the operator's wiring.
# The repairs mirror what loading this dump requires (orphan rows, stale module
# changelog rows, liquibase checksums); see commit history for the rationale.
DEMO_DUMP_URL="${CHARTSEARCHAI_DEMO_DUMP_URL:-https://github.com/openmrs/openmrs-module-chartsearchai/releases/download/demo-data-5284-2026-05-19/openmrs-2.8-refapp-demo-5284-patients-2026-05-19.sql.gz}"
DEMO_SEED_TAG="5284-2026-05-19"

# Resolve DB connection from the runtime properties OpenMRS actually uses. The
# deployed compose injects creds in its own way (not necessarily the OMRS_CONFIG_*
# env names this repo's compose uses), but openmrs-runtime.properties always
# carries the real values and persists in the data volume across boots. Fall back
# to env, then to conventional defaults.
RTP=$(find /openmrs /usr/local/tomcat -maxdepth 4 -name 'openmrs-runtime.properties' 2>/dev/null | head -1)
rtp_get() { [ -n "$RTP" ] && sed -n "s/^[[:space:]]*$1[[:space:]]*=[[:space:]]*//p" "$RTP" | head -1; }
_url=$(rtp_get connection.url)
# Credential sources in priority order: the official image's OMRS_DB_* env vars,
# then this repo's OMRS_CONFIG_CONNECTION_* names, then the runtime properties
# OpenMRS last wrote, then conventional defaults.
DB_USER="${OMRS_DB_USERNAME:-${OMRS_CONFIG_CONNECTION_USERNAME:-$(rtp_get connection.username)}}"; DB_USER="${DB_USER:-openmrs}"
DB_PASS="${OMRS_DB_PASSWORD:-${OMRS_CONFIG_CONNECTION_PASSWORD:-$(rtp_get connection.password)}}"; DB_PASS="${DB_PASS:-openmrs}"
DB_HOST="${OMRS_DB_HOSTNAME:-${OMRS_CONFIG_CONNECTION_SERVER:-$(printf %s "$_url" | sed -n 's#^jdbc:[^:]*://\([^:/]*\).*#\1#p')}}"; DB_HOST="${DB_HOST:-db}"
DB_NAME="${OMRS_DB_NAME:-${OMRS_CONFIG_CONNECTION_DATABASE:-$(printf %s "$_url" | sed -n 's#^jdbc:[^:]*://[^/]*/\([^?]*\).*#\1#p')}}"; DB_NAME="${DB_NAME:-openmrs}"

# --skip-ssl: the bundled MariaDB 11.x client requires TLS by default, but the
# db container doesn't serve it (OpenMRS's own JDBC driver connects without TLS).
seed_sql()  { MYSQL_PWD="$DB_PASS" mariadb      --skip-ssl -h "$DB_HOST" -u "$DB_USER" "$@"; }
seed_dump() { MYSQL_PWD="$DB_PASS" mariadb-dump --skip-ssl -h "$DB_HOST" -u "$DB_USER" "$@"; }
# Records a one-line progress/diagnostic breadcrumb readable over REST at
# GET /ws/rest/v1/systemsetting?q=chartsearchai.demo.seedStatus
seed_status() {
  seed_sql "$DB_NAME" -e "INSERT INTO global_property (property,property_value,uuid) VALUES ('chartsearchai.demo.seedStatus','$1',UUID()) ON DUPLICATE KEY UPDATE property_value='$1';" >/dev/null 2>&1 || true
}

drop_all_tables() {
  _drops=$(seed_sql -N "$DB_NAME" -e \
    "SELECT CONCAT('DROP TABLE IF EXISTS \`', table_name, '\`;') FROM information_schema.tables WHERE table_schema='$DB_NAME'" 2>/dev/null)
  printf 'SET FOREIGN_KEY_CHECKS=0;\n%s\nSET FOREIGN_KEY_CHECKS=1;\n' "$_drops" | seed_sql "$DB_NAME"
}

maybe_seed_demo_data() {
  command -v mariadb >/dev/null 2>&1 || { echo "[demo-seed] mariadb client absent; skipping."; return 0; }
  echo "[demo-seed] db host=$DB_HOST name=$DB_NAME user=$DB_USER (creds from ${RTP:-env/defaults})"

  _i=0
  while [ "$_i" -lt 30 ]; do
    seed_sql -N -e "SELECT 1" >/dev/null 2>&1 && break
    _i=$((_i + 1)); sleep 2
  done
  if ! seed_sql -N -e "SELECT 1" >/dev/null 2>&1; then
    echo "[demo-seed] DB $DB_HOST unreachable / auth failed; starting normally without seeding."; return 0
  fi
  seed_status "connected; starting seed of $DEMO_SEED_TAG"

  _seeded=$(seed_sql -N "$DB_NAME" -e \
    "SELECT property_value FROM global_property WHERE property='chartsearchai.demo.seededDataset'" 2>/dev/null || true)
  if [ "$_seeded" = "$DEMO_SEED_TAG" ]; then
    echo "[demo-seed] dataset $DEMO_SEED_TAG already loaded; skipping."; return 0
  fi

  echo "[demo-seed] seeding demo dataset $DEMO_SEED_TAG ..."
  _dump=/tmp/demo-seed.sql.gz
  seed_status "downloading dump"
  if ! curl -fsSL --retry 3 -o "$_dump" "$DEMO_DUMP_URL"; then
    seed_status "FAILED: dump download"; echo "[demo-seed] dump download failed; starting with existing data."; return 0
  fi

  _bk=/openmrs/data/pre-demo-seed-backup.sql.gz
  echo "[demo-seed] backing up current '$DB_NAME' to $_bk ..."
  seed_status "backing up current DB"
  if ! seed_dump --single-transaction --routines --triggers "$DB_NAME" 2>/dev/null | gzip > "$_bk"; then
    seed_status "FAILED: backup"; echo "[demo-seed] backup failed; aborting seed, starting with existing data."; rm -f "$_bk"; return 0
  fi

  _gp=/tmp/gp-snapshot.sql
  seed_dump --replace --no-create-info --skip-extended-insert "$DB_NAME" global_property \
    --where="property LIKE 'chartsearchai%' OR property LIKE 'querystore%'" > "$_gp" 2>/dev/null || : > "$_gp"

  echo "[demo-seed] clearing existing tables ..."
  seed_status "clearing tables"
  if ! drop_all_tables; then
    seed_status "FAILED: clear tables (restoring backup)"; echo "[demo-seed] failed to clear tables; restoring backup."; gunzip -c "$_bk" | seed_sql "$DB_NAME" || true; return 0
  fi

  echo "[demo-seed] importing dump ..."
  seed_status "importing dump"
  # strip CREATE DATABASE / USE so the import needs no CREATE-database privilege;
  # everything targets the existing $DB_NAME via the client's default database.
  if ! gunzip -c "$_dump" | sed -E '/^CREATE DATABASE/d; /^USE /d' | seed_sql "$DB_NAME"; then
    seed_status "FAILED: import (restoring backup)"; echo "[demo-seed] import failed; restoring backup."
    drop_all_tables || true
    gunzip -c "$_bk" | seed_sql "$DB_NAME" || echo "[demo-seed] WARNING: restore failed; DB may be inconsistent."
    return 0
  fi

  echo "[demo-seed] repairing orphans + liquibase, restoring config ..."
  seed_status "repairing + restoring config"
  seed_sql -f "$DB_NAME" <<SQL
SET FOREIGN_KEY_CHECKS=0;
DELETE pa FROM person_attribute    pa LEFT JOIN person  p  ON p.person_id  = pa.person_id  WHERE p.person_id   IS NULL;
DELETE v  FROM visit               v  LEFT JOIN patient pt ON pt.patient_id = v.patient_id  WHERE pt.patient_id IS NULL;
DELETE d  FROM encounter_diagnosis d  LEFT JOIN patient pt ON pt.patient_id = d.patient_id  WHERE pt.patient_id IS NULL;
DELETE c  FROM conditions          c  LEFT JOIN patient pt ON pt.patient_id = c.patient_id  WHERE pt.patient_id IS NULL;
DELETE o  FROM orders              o  LEFT JOIN patient pt ON pt.patient_id = o.patient_id  WHERE pt.patient_id IS NULL;
DELETE e  FROM encounter           e  LEFT JOIN patient pt ON pt.patient_id = e.patient_id  WHERE pt.patient_id IS NULL;
DELETE ob FROM obs                 ob LEFT JOIN person  p  ON p.person_id  = ob.person_id   WHERE p.person_id   IS NULL;
DELETE a  FROM allergy             a  LEFT JOIN patient pt ON pt.patient_id = a.patient_id  WHERE pt.patient_id IS NULL;
DELETE FROM liquibasechangelog WHERE id LIKE 'chartsearchai%' OR id LIKE 'querystore%';
UPDATE liquibasechangelog SET md5sum = NULL;
SET FOREIGN_KEY_CHECKS=1;
SQL

  { echo 'SET FOREIGN_KEY_CHECKS=0;'; cat "$_gp"; } | seed_sql -f "$DB_NAME" || true

  seed_sql "$DB_NAME" -e \
    "INSERT INTO global_property (property, property_value, uuid) VALUES ('chartsearchai.demo.seededDataset','$DEMO_SEED_TAG', UUID()) ON DUPLICATE KEY UPDATE property_value='$DEMO_SEED_TAG';" || true

  # A fresh dump bypasses querystore's write-path sync, so its read index starts empty and the FIRST
  # chart query on each patient would pay a ~40s lazy projection (getPatientChart -> ensureIndexedSafely
  # embeds all of that patient's records on the request thread). querystore already has the cure: a
  # progressive, checkpoint-resumable bootstrap (BootstrapProgress cursor, persisted per page) that runs
  # on module startup when querystore.bootstrap.autostart=true. Enable it so the next startup pre-indexes
  # every patient in the background (resuming if interrupted) instead of paying the cost lazily per first
  # query. No app/auth/post-start hook needed — the module's own autostart does it.
  seed_sql "$DB_NAME" -e \
    "INSERT INTO global_property (property, property_value, uuid) VALUES ('querystore.bootstrap.autostart','true', UUID()) ON DUPLICATE KEY UPDATE property_value='true';" || true

  rm -f "$_dump" "$_gp"
  _count=$(seed_sql -N "$DB_NAME" -e 'SELECT COUNT(*) FROM patient' 2>/dev/null)
  seed_status "done: $_count patients"
  echo "[demo-seed] done. patients now: $_count"
}

maybe_seed_demo_data || echo "[demo-seed] seed step errored; continuing to start OpenMRS."

# ---- CPU / RAM breadcrumb (diagnostic) -------------------------------------
# Records the host CPU model, ISA extensions, core count, and total RAM into a
# diagnostic global property readable over REST at
#   GET /ws/rest/v1/systemsetting?q=chartsearchai.demo.cpuInfo
# Runs every start so it reflects the current host. It does not describe or
# control med-agent-hub's separately deployed model-serving runtime.
record_cpu_breadcrumb() {
  command -v mariadb >/dev/null 2>&1 || { echo "[cpu-breadcrumb] mariadb client absent; skipping."; return 0; }
  _model=$(sed -n 's/^model name[[:space:]]*:[[:space:]]*//p' /proc/cpuinfo 2>/dev/null | head -1)
  [ -n "$_model" ] || _model=$(sed -n 's/^Model[[:space:]]*:[[:space:]]*//p' /proc/cpuinfo 2>/dev/null | head -1)
  _isa=$(grep -m1 -iE '^(flags|Features)' /proc/cpuinfo 2>/dev/null \
    | grep -oiE 'avx512[a-z0-9_]*|avx2|avx_vnni|avx[0-9]*|amx[_a-z0-9]*|f16c|fma|bf16|sse4[a-z._0-9]*' \
    | tr 'A-Z' 'a-z' | sort -u | tr '\n' ' ')
  _cores=$(nproc 2>/dev/null || echo '?')
  _mem=$(sed -n 's/^MemTotal:[[:space:]]*\([0-9]*\).*/\1/p' /proc/meminfo 2>/dev/null | head -1)
  _val="model=${_model}; cores=${_cores}; memKB=${_mem}; isa=${_isa}"
  # global_property.property_value is plain text; single-quote-escape for the SQL literal.
  _val=$(printf %s "$_val" | sed "s/'/''/g")
  # maybe_seed_demo_data already ran and, on every path that reaches here, already
  # established DB reachability - so this is only a short transient-blip cushion, not a
  # cold wait. Capped low so a DB that maybe_seed already found unreachable (its own 60s
  # probe) doesn't add another 30s to an already-failing boot.
  _i=0; while [ "$_i" -lt 3 ]; do seed_sql -N -e "SELECT 1" >/dev/null 2>&1 && break; _i=$((_i + 1)); sleep 2; done
  seed_sql "$DB_NAME" -e \
    "INSERT INTO global_property (property,property_value,uuid) VALUES ('chartsearchai.demo.cpuInfo','$_val',UUID()) ON DUPLICATE KEY UPDATE property_value='$_val';" >/dev/null 2>&1 \
    && echo "[cpu-breadcrumb] $_val" \
    || echo "[cpu-breadcrumb] could not write GP (DB unreachable?); continuing."
}
record_cpu_breadcrumb || true

exec /openmrs/startup.sh

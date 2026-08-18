#!/bin/sh
# Pre-fetch the ML model files this image needs into the OpenMRS appdata
# volume:
#   querystore/model.onnx ......... e5-base-v2 sentence embedder (~440MB)
#   querystore/vocab.txt .......... BERT WordPiece vocab for e5
#   chartsearchai/<gguf-file> ..... local LLM weights — Gemma 4 E4B (~5GB),
#                                   with Gemma 4 E2B (~3GB) also pulled so
#                                   an operator can A/B latency between the
#                                   two by flipping chartsearchai.llm.modelFilePath
#
# Architectural note: chartsearchai delegates retrieval to querystore via
# `chartsearchai.querystore.enabled=true` (the recommended deployment).
# That path uses the embedder under querystore/ for top-K retrieval, with
# the local LLM doing the filtering in the answer phase. chartsearchai's
# own embedding-pre-filter pipeline (querystore=false, preFilter=true) is
# being deprecated — its filter thresholds are L6-v2-distribution-specific
# and don't transfer to other embedders without re-tuning — so we don't
# provision a chartsearchai-side embedder here.
#
# Retrieval wiring (global properties). Both modules ship these with
# empty/false defaults, so the files provisioned here would be unused
# until something points the properties at them:
#   chartsearchai.querystore.enabled    = true
#   querystore.embedding.modelFilePath  = querystore/model.onnx
#   querystore.embedding.vocabFilePath  = querystore/vocab.txt
#   querystore.embedding.queryModelFilePath = (empty — single encoder)
# configure_retrieval_gps below now writes them, on every start and only
# where the property is blank, so a deliberately-set value survives. The
# one exception is chartsearchai.querystore.enabled on the seed path,
# which maybe_seed_demo_data asserts outright because a freshly imported
# dump carries its own value for it. They used to be an operator step,
# which meant they lived nowhere but the demo's database and a
# --destroy-volumes deploy deleted the wiring along with it; see that
# function for what the resulting unconfigured-embedder state costs.

# When started as root (deployments without a separate init container that
# chowns the volume), heal pre-Apr-27 root-owned contents and drop to the
# openmrs user. The application itself always runs as uid 1001.
if [ "$(id -u)" = "0" ]; then
  chown -R 1001:1001 /openmrs/data 2>/dev/null || true
  exec runuser -u openmrs -- "$0" "$@"
fi

QS_DIR="/openmrs/data/querystore"
LLM_DIR="/openmrs/data/chartsearchai"
mkdir -p "$QS_DIR" "$LLM_DIR"

# ---- embedder: e5-base-v2 ---------------------------------------------------
# e5-base-v2 was chosen empirically over the alternatives we tested
# (sentence-transformers/all-MiniLM-L6-v2 and ncbi/MedCPT-* article+query
# encoders). On colloquial clinical questions a chart-search user actually
# types — "any heart issues?", "any cardiovascular issues?" — it bridges
# to the formal clinical record terms ("Cerebrovascular Accident") that
# L6-v2's tighter clusters miss and that MedCPT's PubMed-trained clusters
# pedantically refuse to associate. The 50% divergence we measured
# against chartsearchai's L6-v2-tuned eval baseline is the LLM-as-filter
# design absorbing what L6-v2 used to do at the retrieval layer; on the
# querystore + LLM path that's a property, not a quality regression.
#
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

# ---- LLM: Gemma 4 E4B Instruct, Q4_K_M -------------------------------------
# Chosen over Gemma 4 E2B, Gemma 4 26B-MoE, and Llama-3.2-3B as the
# smallest model that reliably filters querystore's top-K candidates
# without the small-LLM failure modes we observed:
#   - Llama-3.2-3B collapsed pulse readings into "HB" values when given a
#     full chart, and over-trusted retrieval prior in querystore mode
#     (cited "Cocaine abuse" as a medication).
#   - Gemma 4 E2B over-cited (included benign neoplasms in cancer answers,
#     listed 18 BP readings as cardiovascular records).
#   - Gemma 4 26B-MoE is marginally cleaner on edge cases but its ~16GB
#     footprint isn't justified given E4B's strength on this question class.
# E4B holds the benign/malignant distinction, keeps citations focused on
# clinically-relevant records, and fits in ~5GB of memory.
#
# Background the download and resume on container restart via `curl -C -`
# so OpenMRS can become healthy without waiting for the transfer; deploy
# health-poll loops time out at ~5min, but the actual download can take
# longer on slow networks. Chart search queries return errors until the
# .partial file is renamed to its final name.
# Inner worker: actually performs the download and the .partial→final
# rename. Receives all required values as positional args so each
# backgrounded invocation has its own argument snapshot — reading globals
# from a backgrounded subshell would race with the parent shell's next
# fetch_llm_in_background call overwriting them.
_download_llm_file() {
  _url=$1
  _partial=$2
  _target=$3
  _label=$4
  # --speed-time/--speed-limit aborts if avg throughput stays under 1 KB/s
  # for 60s, so a stalled TCP connection (Hugging Face hangs the socket
  # without closing it) doesn't leave curl waiting on a dead peer
  # indefinitely. On the next container start (operator-initiated under
  # the default restart=no policy), curl -C - resumes from .partial.
  if curl -fsSL -C - --speed-time 60 --speed-limit 1024 -o "$_partial" "$_url"; then
    mv "$_partial" "$_target"
    echo "$_label downloaded: $_target"
  else
    echo "$_label download failed; restart the backend container to retry (curl -C - resumes from the .partial file)."
  fi
}

# Helper: emit the start/resume log line and background the actual
# download. $1 url, $2 filename (under $LLM_DIR), $3 human label, $4 size
# hint, $5 availability-note fragment appended to the log line — callers
# pass the served-vs-standby wording so the helper itself doesn't encode
# which model is currently active.
#
# Each invocation backgrounds, so two calls run in parallel — total volume
# need on /openmrs/data is now ~8GB (E4B ~5GB + E2B ~3GB).
fetch_llm_in_background() {
  url=$1
  filename=$2
  label=$3
  size_hint=$4
  availability_note=$5
  target="$LLM_DIR/$filename"
  partial="$target.partial"
  if [ -f "$target" ]; then
    return
  fi
  if [ -f "$partial" ]; then
    echo "Resuming $label download (${size_hint}) in background${availability_note}..."
  else
    echo "Starting $label download (${size_hint}) in background${availability_note}..."
  fi
  _download_llm_file "$url" "$partial" "$target" "$label" &
}

# E4B is the default served model (config.xml defaults
# chartsearchai.llm.modelFilePath to gemma-4-E4B-it-Q4_K_M.gguf). E2B is
# provisioned alongside as a standby so an operator can A/B latency by
# flipping the GP between the two filenames and waiting for llama-server's
# idle-restart to pick up the new weights — no redeploy required.
fetch_llm_in_background \
  "https://huggingface.co/unsloth/gemma-4-E4B-it-GGUF/resolve/main/gemma-4-E4B-it-Q4_K_M.gguf" \
  "gemma-4-E4B-it-Q4_K_M.gguf" \
  "Gemma 4 E4B Q4_K_M" \
  "~5GB" \
  "; chart search will be unavailable until it completes"

fetch_llm_in_background \
  "https://huggingface.co/unsloth/gemma-4-E2B-it-GGUF/resolve/main/gemma-4-E2B-it-Q4_K_M.gguf" \
  "gemma-4-E2B-it-Q4_K_M.gguf" \
  "Gemma 4 E2B Q4_K_M" \
  "~3GB" \
  " (standby model — chart search keeps using the currently-served weights)"

# ---- one-shot demo dataset seed --------------------------------------------
# Load a fixed OpenMRS demo dump (5,284-patient reference set) into the database
# the first time a backend built with this entrypoint boots against a DB that
# hasn't been seeded with it yet. This is how the chartsearchai.openmrs.org demo
# gets its dataset without any direct DB access: the seed runs in-container
# against the `db` service over the compose network.
#
# Two deploy shapes reach here, and they are not equivalent. On an ordinary
# deploy nothing is wiped, so the models and runtime properties survive and the
# sentinel below skips the seed entirely. On a --destroy-volumes deploy this is
# the code that repopulates an empty database — and the database it hands back
# is then complete while openmrs-data is empty, which is a combination OpenMRS
# does not otherwise produce. The two steps after this function exist for that
# case and must stay downstream of it.
#
# Safety:
#   * Gated by a sentinel global property -> runs exactly once.
#   * Backs up the current DB to the persistent /openmrs/data volume first.
#   * On any failure it restores that backup (or leaves the existing data
#     untouched) and falls through to a normal start — a failed seed never
#     bricks the demo.
#   * Snapshots and restores the server's own chartsearchai/querystore global
#     properties, so the dump's config (LLM endpoint, model paths) does not
#     overwrite the operator's wiring. Two properties are then set deliberately
#     AFTER that restore — querystore.bootstrap.autostart and
#     chartsearchai.querystore.enabled — because a freshly imported dump brings
#     its own values for both and they describe the dump, not this server. That
#     is the one place the restore is overridden on purpose.
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

  # The dump carries chartsearchai.querystore.enabled=false baked in, so the
  # blank-only wiring below cannot reach it and a re-seeded demo would come back
  # with retrieval switched off. Asserted here rather than there because this is
  # where the demo's intended configuration is being established from scratch —
  # it runs after the operator-GP snapshot is restored, alongside the autostart
  # line above, and only on the seed path, so an ordinary boot never overrides a
  # deliberate false.
  seed_sql "$DB_NAME" -e \
    "INSERT INTO global_property (property, property_value, uuid) VALUES ('chartsearchai.querystore.enabled','true', UUID()) ON DUPLICATE KEY UPDATE property_value='true';" || true

  rm -f "$_dump" "$_gp"
  _count=$(seed_sql -N "$DB_NAME" -e 'SELECT COUNT(*) FROM patient' 2>/dev/null)
  seed_status "done: $_count patients"
  echo "[demo-seed] done. patients now: $_count"
}

maybe_seed_demo_data || echo "[demo-seed] seed step errored; continuing to start OpenMRS."

# ---- does this database already carry an OpenMRS schema? --------------------
# The three steps below all need to tell a virgin database from one this
# entrypoint (or a previous boot) has already populated — and, separately, from
# one they could not reach at all.
#
# Memoised, because those steps ask up to six times between them and
# every miss pays a full connect timeout against a DB that is not answering.
# record_cpu_breadcrumb further down caps its own wait for exactly that reason —
# "doesn't add another 30s to an already-failing boot" — and this keeps that
# true rather than quietly spending the budget just above it. Safe to cache over
# this window: maybe_seed_demo_data has already done the 60s wait, and these
# calls all land within a second of each other.
DB_REACHABLE=""
db_reachable() {
  if [ -z "$DB_REACHABLE" ]; then
    if command -v mariadb >/dev/null 2>&1 && seed_sql -N -e "SELECT 1" >/dev/null 2>&1; then
      DB_REACHABLE=yes
    else
      DB_REACHABLE=no
    fi
  fi
  [ "$DB_REACHABLE" = yes ]
}

# Four core tables rather than one, so a stray leftover table cannot pass for a
# schema. Answers no when the DB is unreachable; schema_absent_because below is
# what keeps that from being reported as "empty".
openmrs_schema_present() {
  db_reachable || return 1
  _tables=$(seed_sql -N -e \
    "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='$DB_NAME' \
     AND table_name IN ('global_property','patient','person','users')" 2>/dev/null | tr -dc '0-9')
  [ -n "$_tables" ] && [ "$_tables" -ge 4 ]
}

# Why openmrs_schema_present said no. "I could not ask" must not be reported as
# "the database is empty": the two lead an operator to opposite conclusions while
# reading these lines during an outage, and an unreachable DB is an ordinary path
# here, not a freak one — maybe_seed_demo_data logs and survives it a few lines up.
schema_absent_because() {
  if db_reachable; then
    echo "'$DB_NAME' carries no OpenMRS schema"
  else
    echo "'$DB_NAME' could not be reached, so whether it carries a schema is unknown"
  fi
}

# ---- align the automated installer with the database that now exists --------
# The base image regenerates its automated-installation script on every boot
# (startup-init.sh writes $OMRS_HOME/openmrs-server.properties out of the
# OMRS_CONFIG_* environment), and startup.sh then drives OpenMRS's
# InitializationFilter with it by curl-ing /openmrs/ shortly after Tomcat comes
# up. That script carries create_tables=${OMRS_CONFIG_CREATE_TABLES}, which the
# deployed compose sets to true.
#
# create_tables=true means "this database is empty, build the schema". That is
# right for a virgin install and wrong for every database this entrypoint hands
# to OpenMRS: maybe_seed_demo_data above imports a COMPLETE OpenMRS database, so
# on the first boot after a volume wipe (deploy.yml's `reset` input, which passes
# --destroy-volumes) the installer runs against a schema that already exists. It
# dies on the first CREATE TABLE of the 2.8.x schema-only snapshot — "Table
# 'allergy' already exists" — and the install is abandoned. /openmrs/ then
# redirects to /openmrs/initialsetup for the life of the container, so a visitor
# to the demo gets an installation screen. (The application itself does come up
# behind that redirect on this path — modules load and the REST API answers --
# which is why nothing downstream complained: the old healthcheck probed
# /openmrs/ and was satisfied by the very redirect that was the symptom, and the
# deploy had already reported success. Both are addressed alongside this: see
# the HEALTHCHECK in Dockerfile.backend.)
#
# It clears on a restart — the abandoned install leaves openmrs-runtime.properties
# behind, so the next boot skips the wizard — which is exactly why it bites only
# the first boot after a wipe and why steady-state checks never see it.
#
# So decide create_tables from the database rather than from the environment.
# The same correction covers a wipe of openmrs-data alone (db-data kept), where
# the schema likewise predates the installer.
#
# Keep this even though the next step usually makes it moot. Writing the runtime
# properties stops the installer from running at all, so create_tables is then
# never read — but that write can fail (it says so and falls through), and this
# is what makes that fallback land on a working instance instead of the wizard.
# The two are a pair: do not drop one as redundant with the other.
if openmrs_schema_present; then
  echo "[install] '$DB_NAME' already carries an OpenMRS schema; installing with create_tables=false."
  OMRS_CONFIG_CREATE_TABLES=false
  export OMRS_CONFIG_CREATE_TABLES
else
  echo "[install] $(schema_absent_because); leaving create_tables=${OMRS_CONFIG_CREATE_TABLES:-unset} as it stands."
fi

# ---- hand OpenMRS the runtime properties for the database just prepared -----
# openmrs-runtime.properties is what tells OpenMRS "this database is ready".
# Without it, Listener latches "initial setup is needed" at context init and
# /openmrs/ redirects to /openmrs/initialsetup for the entire life of the JVM —
# even once the automated install has run, written that file itself and started
# the application successfully. Measured on the published image with the
# create_tables correction above in place: the install completes cleanly, the
# context refreshes, /openmrs/ws/rest/v1/session answers 200, and /openmrs/ still
# serves the setup wizard until the container is restarted. A visitor to
# /openmrs sees an installation screen on a server that is actually running.
#
# The file only ever lives in the openmrs-data volume, so a --destroy-volumes
# deploy deletes it while the seed refills the database — and those two have to
# agree about whether the database is ready. Write it here, from the credentials
# that have just queried this database successfully, so the first boot after a wipe is
# indistinguishable from a steady-state one and no wizard is involved at all.
#
# Narrow on purpose: only when the schema is really present and the file is
# really absent. An existing file is never rewritten (startup-init.sh merges its
# own extras into it), and a genuinely empty database still gets the ordinary
# first-install path. Skipping the wizard also leaves the seeded dump's own admin
# credentials in place, which is what every non-reset deploy has always had.
write_runtime_properties_if_missing() {
  _rtp_file="/openmrs/data/openmrs-runtime.properties"
  if [ -f "$_rtp_file" ]; then
    echo "[runtime-properties] $_rtp_file already present; leaving it untouched."
    return 0
  fi
  if ! openmrs_schema_present; then
    echo "[runtime-properties] $(schema_absent_because); leaving the first-install wizard to write it."
    return 0
  fi

  _db_port="${OMRS_DB_PORT:-3306}"
  _db_args="?autoReconnect=true&sessionVariables=default_storage_engine=InnoDB&useUnicode=true&characterEncoding=UTF-8"
  # Java's Properties parser splits on the FIRST unescaped separator, so ':' and
  # '=' inside the value need no escaping; the wizard escapes them only because
  # Properties.store() always does.
  ( umask 077; cat > "$_rtp_file" <<EOF
#Written by backend-init.sh for a database this entrypoint prepared
connection.driver_class=com.mysql.jdbc.Driver
connection.url=jdbc:mysql://${DB_HOST}:${_db_port}/${DB_NAME}${_db_args}
connection.username=${DB_USER}
connection.password=${DB_PASS}
auto_update_database=${OMRS_CONFIG_AUTO_UPDATE_DATABASE:-true}
module.allow_web_admin=${OMRS_CONFIG_MODULE_WEB_ADMIN:-true}
EOF
  )
  if [ -s "$_rtp_file" ]; then
    echo "[runtime-properties] wrote $_rtp_file for ${DB_USER}@${DB_HOST}:${_db_port}/${DB_NAME}; OpenMRS starts without the setup wizard."
  else
    echo "[runtime-properties] could not write $_rtp_file; falling back to the setup wizard." >&2
    rm -f "$_rtp_file"
  fi
}
write_runtime_properties_if_missing || echo "[runtime-properties] step errored; continuing to start OpenMRS."

# ---- retrieval wiring (global properties) ----------------------------------
# This script provisions querystore/model.onnx and querystore/vocab.txt at the
# top, but the properties that point querystore at them live in the DATABASE,
# and both modules ship them empty. Until now they were set by hand on the demo,
# so a --destroy-volumes deploy deleted the wiring along with the database. The
# header of this file has described exactly these as an operator step with a
# "follow-up change" pending since the querystore migration; this is it.
#
# Leaving them unset costs more than a disabled feature. maybe_seed_demo_data
# turns querystore.bootstrap.autostart on, so the next start sweeps every record
# of all 5,284 seeded patients — and with modelFilePath unset each record throws
# IllegalStateException out of OnnxEmbeddingProvider and is logged with a full
# stack trace. Measured against the published image on a wiped volume: 112MB of
# stack traces in the first eight minutes and still climbing, on a host that also
# ships its logs to Loki.
#
# Written only where the property is blank, so an operator who deliberately
# points these elsewhere keeps their value — the same courtesy the seed already
# extends by snapshotting chartsearchai%/querystore% across the import.
gp_set_if_blank() {
  seed_sql "$DB_NAME" -e \
    "INSERT INTO global_property (property, property_value, uuid) VALUES ('$1','$2',UUID()) \
     ON DUPLICATE KEY UPDATE property_value = IF(property_value IS NULL OR property_value = '', '$2', property_value);" \
    >/dev/null 2>&1
}

gp_value() {
  seed_sql -N "$DB_NAME" -e \
    "SELECT COALESCE(property_value,'') FROM global_property WHERE property='$1'" 2>/dev/null
}

configure_retrieval_gps() {
  command -v mariadb >/dev/null 2>&1 || { echo "[retrieval-wiring] mariadb client absent; skipping."; return 0; }
  if ! openmrs_schema_present; then
    echo "[retrieval-wiring] $(schema_absent_because); no global properties written, so querystore stays unconfigured and chart search will be off."
    return 0
  fi

  # Paths are relative to the application data directory, derived from the same
  # variables the downloads above wrote to so there is one source of truth.
  gp_set_if_blank 'chartsearchai.querystore.enabled' 'true'
  gp_set_if_blank 'querystore.embedding.modelFilePath' "${ONNX_FILE#/openmrs/data/}"
  gp_set_if_blank 'querystore.embedding.vocabFilePath' "${VOCAB_FILE#/openmrs/data/}"

  _model_gp=$(gp_value 'querystore.embedding.modelFilePath')
  _enabled_gp=$(gp_value 'chartsearchai.querystore.enabled')

  # Never leave the combination that floods: a bootstrap sweep enabled with no
  # embedder to run it. If the embedder is still unconfigured after the wiring
  # above, switch the sweep off rather than let every record fail and log a stack
  # trace.
  if [ -z "$_model_gp" ]; then
    seed_sql "$DB_NAME" -e \
      "UPDATE global_property SET property_value='false' WHERE property='querystore.bootstrap.autostart';" \
      >/dev/null 2>&1 || true
    echo "[retrieval-wiring] embedder unconfigured; querystore.bootstrap.autostart forced to false so the sweep cannot fail per record."
  fi

  echo "[retrieval-wiring] chartsearchai.querystore.enabled=$_enabled_gp querystore.embedding.modelFilePath=$_model_gp bootstrap.autostart=$(gp_value 'querystore.bootstrap.autostart')"
}
configure_retrieval_gps || echo "[retrieval-wiring] step errored; continuing to start OpenMRS."

# ---- CPU / RAM breadcrumb (diagnostic) -------------------------------------
# Records the host CPU model, the ISA-extension subset that matters for
# llama.cpp's prefill GEMM throughput (AVX/AVX-512/VNNI/AMX/etc.), core count,
# and total RAM into a global property readable over REST at
#   GET /ws/rest/v1/systemsetting?q=chartsearchai.demo.cpuInfo
# No shell/log access to the demo is needed to answer the open build question:
# is the published x86_64 binary (compiled -march=native on the GitHub runner)
# leaving ISA on the table on THIS box, and is RAM the constraint behind the
# cold-prefill variance. Runs every start (cheap) so it always reflects the
# current host. Reads only /proc (always present); independent of the seed.
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

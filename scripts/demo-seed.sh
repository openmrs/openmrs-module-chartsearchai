#!/bin/sh
# The demo seed: the functions backend-init.sh sources to load the demo dataset into an EMPTY or
# existing OpenMRS database once, and the database helpers the rest of that entrypoint shares
# (seed_sql, seed_dump). Kept in its own file so scripts/demo-seed.test.sh can drive them against a
# real MariaDB. The caller sets DB_HOST, DB_USER, DB_PASS, DB_NAME, DEMO_DUMP_URL and DEMO_SEED_TAG
# before calling maybe_seed_demo_data; why the seed exists and what it repairs is the comment above
# its call in backend-init.sh.

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

  # What a seed keeps until it finishes, on the data volume so a boot killed part-way leaves them to the
  # next: the backup of the data it replaces and the snapshot of this server's own properties.
  _state="${DEMO_SEED_STATE_DIR:-/openmrs/data}"
  _bk="$_state/pre-demo-seed-backup.sql.gz"
  _gp="$_state/demo-seed-gp-snapshot.sql"

  # The tag is read only where global_property exists, and a lookup that FAILS stops the seed: an unreadable
  # tag is not an absent one, and a seed replaces every table.
  if ! _has_gp=$(seed_sql -N -e "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='$DB_NAME' AND table_name='global_property'" 2>/dev/null); then
    echo "[demo-seed] could not read the schema; starting without seeding."; return 0
  fi
  if [ "$_has_gp" != 0 ]; then
    if ! _seeded=$(seed_sql -N "$DB_NAME" -e \
        "SELECT property_value FROM global_property WHERE property='chartsearchai.demo.seededDataset'" 2>/dev/null); then
      seed_status "SKIPPED: could not read seededDataset"
      echo "[demo-seed] could not read chartsearchai.demo.seededDataset; starting without seeding."; return 0
    fi
    if [ "$_seeded" = "$DEMO_SEED_TAG" ]; then
      # A snapshot left by a seed killed after writing the tag would otherwise be taken for an unfinished one's.
      rm -f "$_gp"
      echo "[demo-seed] dataset $DEMO_SEED_TAG already loaded; skipping."; return 0
    fi
  fi

  echo "[demo-seed] seeding demo dataset $DEMO_SEED_TAG ..."
  _dump=/tmp/demo-seed.sql.gz
  seed_status "downloading dump"
  if ! curl -fsSL --retry 3 -o "$_dump" "$DEMO_DUMP_URL"; then
    seed_status "FAILED: dump download"; echo "[demo-seed] dump download failed; starting with existing data."; return 0
  fi

  if [ -f "$_gp" ]; then
    # A previous boot started this seed and did not finish it, so the database now holds part of the dump: its
    # backup and its snapshot are of the server as it was, and taking them again would replace both with the
    # dump's (2026-10-07: the demo came back with the dump's remote engine and the backup of a half import).
    echo "[demo-seed] resuming an unfinished seed with its own backup and snapshot."
  else
    echo "[demo-seed] backing up current '$DB_NAME' to $_bk ..."
    seed_status "backing up current DB"
    # The dump's own status, not gzip's: a pipeline answers with its last command's.
    if ! { seed_dump --single-transaction --routines --triggers "$DB_NAME" 2>/dev/null; echo $? > "$_state/.backup-status"; } \
        | gzip > "$_bk" || [ "$(cat "$_state/.backup-status" 2>/dev/null)" != 0 ]; then
      seed_status "FAILED: backup"; echo "[demo-seed] backup failed; aborting seed, starting with existing data."
      rm -f "$_bk" "$_state/.backup-status"; return 0
    fi
    rm -f "$_state/.backup-status"

    # This server's own properties, put back over the dump's below. A snapshot that fails stops the seed, since
    # going on would leave the dump's in their place. Written aside and moved, so only a whole one is resumed.
    if [ "$_has_gp" = 0 ]; then
      : > "$_gp.tmp"
    elif ! seed_dump --replace --no-create-info --skip-extended-insert "$DB_NAME" global_property \
        --where="property LIKE 'chartsearchai%' OR property LIKE 'querystore%'" > "$_gp.tmp" 2>/dev/null; then
      seed_status "FAILED: property snapshot"
      echo "[demo-seed] could not snapshot this server's properties; aborting seed, starting with existing data."
      rm -f "$_gp.tmp"; return 0
    fi
    mv "$_gp.tmp" "$_gp"
  fi

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

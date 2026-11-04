#!/bin/sh
# Drives scripts/demo-seed.sh's maybe_seed_demo_data against a REAL MariaDB server. Each case builds a
# database in the state a boot can meet, runs the seed, and reads back what it left.
#
# Needs: a running MariaDB the client reaches with DB_HOST/DB_USER/DB_PASS (a user that may create and
# drop databases), and mariadb, mariadb-dump, curl and gzip on PATH. Creates and drops databases named
# demo_seed_t_*; touches nothing else. Exits non-zero if any case fails.
set -u
HERE=$(cd "$(dirname "$0")" && pwd)
. "$HERE/demo-seed.sh"

: "${DB_HOST:?}" "${DB_USER:?}" "${DB_PASS:=}"
T=$(mktemp -d)
trap 'rm -rf "$T"' EXIT
REAL_MARIADB=$(command -v mariadb)
REAL_DUMP=$(command -v mariadb-dump)
FAILED=0

check() {
  if [ "$2" = "$3" ]; then echo "ok   $1"; else echo "FAIL $1: expected [$3], got [$2]"; FAILED=1; fi
}
q() { seed_sql -N "$DB_NAME" -e "$1" 2>/dev/null; }
gp() { q "SELECT property_value FROM global_property WHERE property='$1'"; }
has_table() { q "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='$DB_NAME' AND table_name='$1'"; }

# The dataset a seed loads: three patients, and the dataset's own chartsearchai properties, which are
# NOT this server's — the values a seed must never leave in place of the operator's.
dump_sql() {
  cat <<SQL
CREATE TABLE global_property (property VARCHAR(255) PRIMARY KEY, property_value TEXT, uuid CHAR(38));
INSERT INTO global_property VALUES ('chartsearchai.llm.engine','remote',UUID()),('chartsearchai.drugReference.enabled','false',UUID());
CREATE TABLE liquibasechangelog (id VARCHAR(255), md5sum VARCHAR(35));
$1
CREATE TABLE patient (patient_id INT PRIMARY KEY);
INSERT INTO patient VALUES (1),(2),(3);
SQL
}
dump_sql '' | gzip > "$T/dump.sql.gz"
# The same dataset, pausing mid-import once its properties are in: what a boot killed during the
# import leaves behind.
dump_sql 'SELECT SLEEP(60);' | gzip > "$T/slow-dump.sql.gz"

# A fresh run's environment: the dataset, the tag, and a state directory of its own.
fresh_run() {
  DB_NAME=$1
  DEMO_DUMP_URL="file://$T/dump.sql.gz"
  # shellcheck disable=SC2034 # read by maybe_seed_demo_data
  DEMO_SEED_TAG=t-2026
  DEMO_SEED_STATE_DIR="$T/state-$1"
  rm -rf "$DEMO_SEED_STATE_DIR"; mkdir -p "$DEMO_SEED_STATE_DIR"
  seed_sql -e "DROP DATABASE IF EXISTS $1; CREATE DATABASE $1"
}
# A server already running: its own properties, its own data, and a table no dump carries.
operator_db() {
  fresh_run "$1"
  q "CREATE TABLE global_property (property VARCHAR(255) PRIMARY KEY, property_value TEXT, uuid CHAR(38));
     INSERT INTO global_property VALUES ('chartsearchai.llm.engine','local',UUID()),('chartsearchai.drugReference.enabled','true',UUID());
     CREATE TABLE patient (patient_id INT PRIMARY KEY); INSERT INTO patient VALUES (100);
     CREATE TABLE operator_marker (x INT);"
}
# PATH shims that fail ONE kind of call and pass every other through to the real client.
shim() {
  mkdir -p "$T/shim-$1"
  printf '%s\n' "$2" > "$T/shim-$1/$3"; chmod +x "$T/shim-$1/$3"
}

echo "== an already seeded database is left alone"
operator_db demo_seed_t_seeded
q "INSERT INTO global_property VALUES ('chartsearchai.demo.seededDataset','t-2026',UUID())"
maybe_seed_demo_data >/dev/null 2>&1
check "its data stays" "$(has_table operator_marker)" 1
check "its engine stays" "$(gp chartsearchai.llm.engine)" local

echo "== an existing database is seeded and keeps the operator's properties"
operator_db demo_seed_t_existing
maybe_seed_demo_data >/dev/null 2>&1
check "the dataset is loaded" "$(q 'SELECT COUNT(*) FROM patient')" 3
check "the tag is written" "$(gp chartsearchai.demo.seededDataset)" t-2026
check "the operator's engine is restored" "$(gp chartsearchai.llm.engine)" local
check "the operator's drug reference is restored" "$(gp chartsearchai.drugReference.enabled)" true
check "the backup holds the data replaced" "$(gunzip -c "$DEMO_SEED_STATE_DIR/pre-demo-seed-backup.sql.gz" | grep -c 'CREATE TABLE `operator_marker`')" 1

echo "== an empty database is seeded"
fresh_run demo_seed_t_empty
maybe_seed_demo_data >/dev/null 2>&1
check "the dataset is loaded" "$(q 'SELECT COUNT(*) FROM patient')" 3
check "the tag is written" "$(gp chartsearchai.demo.seededDataset)" t-2026

echo "== a lookup of the tag that fails is not read as 'not seeded'"
operator_db demo_seed_t_lookup
q "INSERT INTO global_property VALUES ('chartsearchai.demo.seededDataset','t-2026',UUID())"
shim lookup "#!/bin/sh
case \"\$*\" in *seededDataset*) exit 1;; esac
exec '$REAL_MARIADB' \"\$@\"" mariadb
PATH="$T/shim-lookup:$PATH" maybe_seed_demo_data >/dev/null 2>&1
check "its data stays" "$(has_table operator_marker)" 1
check "its patients stay" "$(q 'SELECT COUNT(*) FROM patient')" 1

echo "== a snapshot of the operator's properties that fails stops the seed"
operator_db demo_seed_t_snapshot
shim snapshot "#!/bin/sh
case \"\$*\" in *--where*) exit 1;; esac
exec '$REAL_DUMP' \"\$@\"" mariadb-dump
PATH="$T/shim-snapshot:$PATH" maybe_seed_demo_data >/dev/null 2>&1
check "its data stays" "$(has_table operator_marker)" 1
check "its engine stays" "$(gp chartsearchai.llm.engine)" local

echo "== a backup that fails stops the seed"
operator_db demo_seed_t_backup
shim backup "#!/bin/sh
case \"\$*\" in *--routines*) exit 1;; esac
exec '$REAL_DUMP' \"\$@\"" mariadb-dump
PATH="$T/shim-backup:$PATH" maybe_seed_demo_data >/dev/null 2>&1
check "its data stays" "$(has_table operator_marker)" 1
check "its patients stay" "$(q 'SELECT COUNT(*) FROM patient')" 1

echo "== a seed killed during its import is finished by the next boot with the FIRST boot's snapshot and backup"
operator_db demo_seed_t_killed
DEMO_DUMP_URL="file://$T/slow-dump.sql.gz"
( maybe_seed_demo_data >/dev/null 2>&1 ) &
seeding=$!
i=0
while [ "$i" -lt 120 ] && [ "$(q "SELECT COUNT(*) FROM information_schema.processlist WHERE info LIKE 'SELECT SLEEP(60)%'")" != 1 ]; do
  sleep 0.5; i=$((i + 1))
done
check "precondition: the import is paused with the dataset's properties in" "$(gp chartsearchai.llm.engine)" remote
kill -9 "$seeding" 2>/dev/null
for id in $(q "SELECT id FROM information_schema.processlist WHERE info LIKE 'SELECT SLEEP(60)%'"); do q "KILL $id"; done
wait "$seeding" 2>/dev/null
# shellcheck disable=SC2034 # read by maybe_seed_demo_data
DEMO_DUMP_URL="file://$T/dump.sql.gz"
maybe_seed_demo_data >/dev/null 2>&1
check "the dataset is loaded" "$(q 'SELECT COUNT(*) FROM patient')" 3
check "the tag is written" "$(gp chartsearchai.demo.seededDataset)" t-2026
check "the operator's engine is restored, not the half-loaded dataset's" "$(gp chartsearchai.llm.engine)" local
check "the backup is still the operator's data" "$(gunzip -c "$DEMO_SEED_STATE_DIR/pre-demo-seed-backup.sql.gz" | grep -c 'CREATE TABLE `operator_marker`')" 1

# The demo dataset's program tables: a valid program, workflow and state, and the shapes the dump
# carries from its legacy data, whose concepts it never exported (concepts 99xxxx, 2026-11-05): a
# program, workflows and states naming a concept that does not exist. One broken state is someone's
# CURRENT state and one broken program has an enrollment, so the repair must leave those two alone.
programs_db() {
  fresh_run "$1"
  q "CREATE TABLE global_property (property VARCHAR(255) PRIMARY KEY, property_value TEXT, uuid CHAR(38));
     CREATE TABLE concept (concept_id INT PRIMARY KEY);
     INSERT INTO concept VALUES (10),(11),(12);
     CREATE TABLE program (program_id INT PRIMARY KEY, concept_id INT);
     INSERT INTO program VALUES (1,10),(5,99005),(6,99006);
     CREATE TABLE program_workflow (program_workflow_id INT PRIMARY KEY, program_id INT, concept_id INT);
     INSERT INTO program_workflow VALUES (1,1,11),(9,1,99009),(10,5,99010);
     CREATE TABLE program_workflow_state (program_workflow_state_id INT PRIMARY KEY, program_workflow_id INT, concept_id INT);
     INSERT INTO program_workflow_state VALUES (1,1,12),(2,1,99002),(3,9,99003),(4,10,99004),(7,1,99007);
     CREATE TABLE concept_state_conversion (concept_state_conversion_id INT PRIMARY KEY, program_workflow_id INT, program_workflow_state_id INT);
     CREATE TABLE patient_program (patient_program_id INT PRIMARY KEY, program_id INT, date_enrolled DATETIME, date_completed DATETIME, date_created DATETIME);
     INSERT INTO patient_program VALUES (1,1,'2006-01-01',NULL,'2006-09-28'),(2,6,NULL,NULL,'2006-09-28'),(3,1,NULL,'2006-05-01','2006-09-28');
     CREATE TABLE patient_state (patient_state_id INT PRIMARY KEY, state INT);
     INSERT INTO patient_state VALUES (1,1),(2,7);"
}

echo "== programs naming a missing concept are removed from the demo dataset, unless a patient's data uses them, and enrollments get a date"
programs_db demo_seed_t_programs
q "INSERT INTO global_property VALUES ('chartsearchai.demo.seededDataset','t-2026',UUID())"
repair_demo_programs >/dev/null 2>&1
check "the valid program, workflow and state stay" "$(q 'SELECT COUNT(*) FROM program WHERE program_id=1')$(q 'SELECT COUNT(*) FROM program_workflow WHERE program_workflow_id=1')$(q 'SELECT COUNT(*) FROM program_workflow_state WHERE program_workflow_state_id=1')" 111
check "broken states no patient is in are removed" "$(q 'SELECT GROUP_CONCAT(program_workflow_state_id ORDER BY 1) FROM program_workflow_state')" 1,7
check "broken workflows left without states are removed" "$(q 'SELECT GROUP_CONCAT(program_workflow_id ORDER BY 1) FROM program_workflow')" 1
check "a broken program with no enrollment and no workflow is removed; an enrolled one stays" "$(q 'SELECT GROUP_CONCAT(program_id ORDER BY 1) FROM program')" 1,6
check "every patient enrollment and state stays" "$(q 'SELECT COUNT(*) FROM patient_program')$(q 'SELECT COUNT(*) FROM patient_state')" 32
check "a recorded enrollment date stays" "$(q 'SELECT DATE(date_enrolled) FROM patient_program WHERE patient_program_id=1')" 2006-01-01
check "an enrollment with no date takes the date it was recorded" "$(q 'SELECT DATE(date_enrolled) FROM patient_program WHERE patient_program_id=2')" 2006-09-28
check "or its completion date, when that is earlier" "$(q 'SELECT DATE(date_enrolled) FROM patient_program WHERE patient_program_id=3')" 2006-05-01
repair_demo_programs >/dev/null 2>&1
check "a second run changes nothing" "$(q 'SELECT COUNT(*) FROM program')$(q 'SELECT COUNT(*) FROM program_workflow')$(q 'SELECT COUNT(*) FROM program_workflow_state')" 212

echo "== a database not seeded with the demo dataset keeps its programs"
programs_db demo_seed_t_programs_other
q "INSERT INTO global_property VALUES ('chartsearchai.demo.seededDataset','some-other-tag',UUID())"
repair_demo_programs >/dev/null 2>&1
check "its programs, workflows and states all stay" "$(q 'SELECT COUNT(*) FROM program')$(q 'SELECT COUNT(*) FROM program_workflow')$(q 'SELECT COUNT(*) FROM program_workflow_state')" 335
check "its enrollments with no date keep none" "$(q 'SELECT COUNT(*) FROM patient_program WHERE date_enrolled IS NULL')" 2

for db in seeded existing empty lookup snapshot backup killed programs programs_other; do seed_sql -e "DROP DATABASE IF EXISTS demo_seed_t_$db"; done
[ "$FAILED" = 0 ] && echo "all cases pass" || echo "SOME CASES FAILED"
exit "$FAILED"

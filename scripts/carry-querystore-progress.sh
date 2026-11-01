#!/usr/bin/env bash
#
# Carry QueryStore's record of a finished index from the bake run into the demo database a
# downloaded standalone imports on its first start.
#
#   carry-querystore-progress.sh <bake-standalone-dir> <pristine-demodatabase.zip> <sql-entry>
#
# The standalone workflow bakes the querystore Lucene index so a download answers on its first
# patient open. The index files are half of that: QueryStore records which resource types finished
# indexing in the querystore_bootstrap_progress TABLE, and a read reports its chart complete only
# when every type there has completed. chartsearchai withholds every answer otherwise (#619). A
# download that ships the files without the rows therefore starts with an empty table, reports
# NOT_STARTED for every type, and answers nothing until someone runs a full reindex by hand.
#
# This dumps, from the bake run's still-running database:
#   - the querystore_bootstrap_progress table, schema and rows, each row COMPLETED with its cursor
#     at the bundled dump's own records (the bake indexed that same dump); and
#   - the liquibasechangelog rows of QueryStore's changesets, so the module's liquibase does not
#     try to create the table a second time on the user's first start.
# It appends both to the demo dump inside the pristine demodatabase.zip, in place.
#
# Run it while the bake standalone is UP (its MariaDB is what it reads) and after the reindex has
# reported complete. Fails, and changes nothing, unless every row it read is COMPLETED.

set -euo pipefail

BAKE="${1:?bake standalone dir}"
DEMO_ZIP="${2:?pristine demodatabase.zip}"
ENTRY="${3:?sql entry inside the zip, e.g. data/demo-db-3.8.0.sql}"

die() { echo "::error::carry-querystore-progress: $1" >&2; exit 1; }

PROPS="$BAKE/openmrs-runtime.properties"
[ -f "$PROPS" ] || die "no $PROPS"
[ -f "$DEMO_ZIP" ] || die "no $DEMO_ZIP"
prop() { sed -n "s/^$1=//p" "$PROPS" | tail -1; }
DB_USER=$(prop connection.username)
DB_PASS=$(prop connection.password)   # the bake's first start reset it; read it back from the file
DB_URL=$(prop connection.url)
DB_PORT=$(printf '%s' "$DB_URL" | sed -n 's#.*://[^:/]*:\([0-9][0-9]*\)/.*#\1#p')
DB_NAME=$(printf '%s' "$DB_URL" | sed -n 's#.*://[^/]*/\([^?]*\).*#\1#p')
[ -n "$DB_USER" ] && [ -n "$DB_PORT" ] && [ -n "$DB_NAME" ] || die "could not read the connection from $PROPS"

# The standalone's own MariaDB client tools, which match its server.
DUMP=""
for c in "$BAKE/database/bin/mariadb-dump" "$BAKE/database/bin/mysqldump"; do
  [ -x "$c" ] && { DUMP="$c"; break; }
done
[ -n "$DUMP" ] || die "no mariadb-dump or mysqldump under $BAKE/database/bin: $(ls "$BAKE/database/bin" 2>&1 | tr '\n' ' ')"

dump() {
  # --compact: no session prologue/epilogue and no DROP TABLE, only the statements themselves.
  # --skip-extended-insert: one row per INSERT, so the row checks below read one line per row.
  # The sed drops MariaDB 11's "/*M!999999\- enable the sandbox mode */" first line, which an
  # importer that is not a MariaDB 11 client reads as the client command "\-" and refuses.
  "$DUMP" --skip-ssl -h127.0.0.1 -P"$DB_PORT" -u"$DB_USER" -p"$DB_PASS" \
    --compact --skip-extended-insert --skip-comments "$DB_NAME" "$@" | sed '/^\/\*M!999999\\- enable the sandbox mode \*\/ *$/d'
}

WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

dump querystore_bootstrap_progress > "$WORK/progress.sql" || die "dumping querystore_bootstrap_progress failed"
dump --no-create-info --where="ID LIKE 'querystore-%'" liquibasechangelog > "$WORK/changelog.sql" \
  || die "dumping QueryStore's liquibasechangelog rows failed"

ROWS=$(grep -c '^INSERT INTO `querystore_bootstrap_progress`' "$WORK/progress.sql" || true)
DONE=$(grep '^INSERT INTO `querystore_bootstrap_progress`' "$WORK/progress.sql" | grep -c "'COMPLETED'" || true)
[ "$ROWS" -gt 0 ] || die "querystore_bootstrap_progress has no rows"
[ "$DONE" -eq "$ROWS" ] || die "only $DONE of $ROWS querystore_bootstrap_progress rows are COMPLETED"
grep -q '^CREATE TABLE `querystore_bootstrap_progress`' "$WORK/progress.sql" || die "the dump carries no CREATE TABLE"
grep -q "'querystore-bootstrap-progress-1'" "$WORK/changelog.sql" \
  || die "liquibasechangelog has no row for querystore-bootstrap-progress-1"

mkdir "$WORK/zip"
unzip -q "$DEMO_ZIP" "$ENTRY" -d "$WORK/zip" || die "$DEMO_ZIP has no entry $ENTRY"
SQL="$WORK/zip/$ENTRY"
grep -q 'querystore_bootstrap_progress' "$SQL" && die "$ENTRY already carries querystore_bootstrap_progress"
{
  echo ""
  echo "-- QueryStore's record of the baked index (scripts/carry-querystore-progress.sh)"
  cat "$WORK/progress.sql"
  cat "$WORK/changelog.sql"
} >> "$SQL"
DEMO_ZIP_ABS=$(cd "$(dirname "$DEMO_ZIP")" && pwd)/$(basename "$DEMO_ZIP")
(cd "$WORK/zip" && zip -q "$DEMO_ZIP_ABS" "$ENTRY") || die "could not write $ENTRY back into $DEMO_ZIP"

unzip -p "$DEMO_ZIP" "$ENTRY" | grep -c '^INSERT INTO `querystore_bootstrap_progress`' | grep -qx "$ROWS" \
  || die "$DEMO_ZIP does not carry the $ROWS rows after the write"
echo "Carried $ROWS COMPLETED querystore_bootstrap_progress rows and $(grep -c '^INSERT' "$WORK/changelog.sql") liquibasechangelog rows into $ENTRY."

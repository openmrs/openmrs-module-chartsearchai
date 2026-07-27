#!/bin/bash
# Controlled A/B/A wall-clock comparison of two module builds on ONE fixed cell set.
#
#   A = the build under test, B = the baseline. A is measured TWICE, first and last, so machine
#   drift across the run is visible instead of being attributed to the code. On a CPU-only host
#   that matters more than the change usually does: between two long captures here, a topic whose
#   prompt did not change at all moved +24%.
#
# Read the authoritative per-query timings from the module's own audit log rather than from a
# stopwatch — it already records response_time_ms, input_tokens and output_tokens per query:
#
#   select count(*), round(avg(response_time_ms)), round(avg(input_tokens)), round(avg(output_tokens))
#     from chartsearchai_audit_log where date_created between '<pass start>' and '<pass end>';
#
# The wall-clock column this script prints is the cross-check on that, not the primary measure.
#
# Prerequisites
#   * A configured standalone (STANDALONE_DIR) whose querystore index is already built.
#   * Both .omod artifacts built. Build the BASELINE out of tree so the working tree is untouched:
#         git worktree add --detach /tmp/csa-base <baseline-ref>
#         (cd /tmp/csa-base && mvn -o -DskipTests install)
#     Use `install`, not `compile`: in a fresh tree the omod module's unpack-dependencies needs the
#     api jar packaged first (MDEP-98).
#   * Nothing else running. Do not build, capture or run tests while this measures — the local
#     engine is CPU-bound and contention reads as a fake regression.
#
# Usage
#   STANDALONE_DIR=/path/to/referenceapplication-standalone \
#   ARM_OMOD=$PWD/omod/target/chartsearchai-1.0.0-SNAPSHOT.omod \
#   BASELINE_OMOD=/tmp/csa-base/omod/target/chartsearchai-1.0.0-SNAPSHOT.omod \
#   eval/latency/latency_ab.sh > /tmp/latency_ab.tsv
#
# Redirect to a FILE rather than piping into `tail`/`head`. This script deliberately leaves the
# standalone running (pass 3 leaves the arm build deployed), and a pipe reader can block on that
# child long after the passes have finished.
#
# Optional env: OPENMRS_AUTH (admin:Admin123), OPENMRS_REST, MYSQL_PORT (3316), TOMCAT_PORT (8081),
#               PATIENTS (space-separated uuids).
set -u

SA=${STANDALONE_DIR:?set STANDALONE_DIR to the standalone install directory}
A_OMOD=${ARM_OMOD:?set ARM_OMOD to the .omod under test}
B_OMOD=${BASELINE_OMOD:?set BASELINE_OMOD to the baseline .omod}
AUTH=${OPENMRS_AUTH:-admin:Admin123}
BASE=${OPENMRS_REST:-http://localhost:8081/openmrs/ws/rest/v1}
MYSQL_PORT=${MYSQL_PORT:-3316}
TOMCAT_PORT=${TOMCAT_PORT:-8081}

# Three charts of differing size, all in the local gold. Override with PATIENTS to pin the same
# cells you scored the quality arm on.
DEFAULT_PATIENTS="1c47b620-080f-4484-8372-74e904165aec dc8560c9-6d2b-45bf-861c-8fcf562ec9b1 bbdd58b1-97c1-4488-acae-582abb1b782d"
read -r -a PATIENT_LIST <<< "${PATIENTS:-$DEFAULT_PATIENTS}"

# The same nine scope questions the drift-metric gold uses, so a latency pass and a quality pass
# cover identical prompts.
QUERIES=("Is the patient enrolled in any programs?" "Does the patient have any allergies?" "What medications is the patient taking?" "Does the patient have any eye problems?" "Does the patient have any heart or cardiac problems?" "Has the patient had any fractures or broken bones?" "Does the patient have any kidney problems?" "Does the patient have any mental health or psychiatric conditions?" "Does the patient have any drug allergies?")

deploy() {  # $1 = omod path, $2 = pass label
  pkill -9 -f openmrs-standalone.jar 2>/dev/null
  pkill -9 -f llama-server 2>/dev/null
  # The embedded MariaDB is a separate process and must go too, or the next boot cannot lock the DB.
  pkill -9 -f "mariadbd.*$MYSQL_PORT" 2>/dev/null
  while lsof -nP -iTCP:"$TOMCAT_PORT" -sTCP:LISTEN >/dev/null 2>&1; do sleep 1; done
  while lsof -nP -iTCP:"$MYSQL_PORT" -sTCP:LISTEN >/dev/null 2>&1; do sleep 1; done
  cp "$1" "$SA/appdata/modules/chartsearchai-1.0.0-SNAPSHOT.omod"
  rm -rf "$SA/appdata/.openmrs-lib-cache/chartsearchai"
  # </dev/null matters: without it the standalone inherits this script's stdin, and a piped caller
  # can hang on it long after the passes are done.
  (cd "$SA" && nohup java -jar openmrs-standalone.jar </dev/null >"/tmp/standalone-lat-$2.log" 2>&1 &)
  until curl -s -o /dev/null -w "%{http_code}" --max-time 5 "${BASE%/ws/rest/v1}/" 2>/dev/null \
        | grep -qE "200|302"; do sleep 5; done
}

runset() {  # $1 = pass label
  # One throwaway call first: the model load / first-touch prefill must not land on a measured cell.
  curl -s -u "$AUTH" --max-time 900 -H "Content-Type: application/json" -X POST "$BASE/chartsearchai/search" \
       -d "$(printf '{"patient":"%s","question":"warmup"}' "${PATIENT_LIST[0]}")" -o /dev/null
  for uuid in "${PATIENT_LIST[@]}"; do
    for q in "${QUERIES[@]}"; do
      t=$( { /usr/bin/time -p curl -s -u "$AUTH" --max-time 900 -H "Content-Type: application/json" \
             -X POST "$BASE/chartsearchai/search" \
             -d "$(printf '{"patient":"%s","question":"%s"}' "$uuid" "$q")" -o /dev/null ; } 2>&1 \
           | awk '/^real/{print $2}')
      printf '%s\t%s\t%s\n' "$1" "$uuid" "$t"
    done
  done
}

printf '# pass\tpatient\tseconds   (started %s)\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
echo "# PASS 1: A = $A_OMOD"; deploy "$A_OMOD" a1; runset A1
echo "# PASS 2: B = $B_OMOD"; deploy "$B_OMOD" b;  runset B
echo "# PASS 3: A again (drift control)"; deploy "$A_OMOD" a2; runset A2
echo "# done $(date -u +%Y-%m-%dT%H:%M:%SZ) — the arm build is left deployed."
echo "# Read the authoritative per-pass means from chartsearchai_audit_log (see the header)."

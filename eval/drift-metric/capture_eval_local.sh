#!/bin/bash
# Fire the local gold's cells at the live REST endpoint (same shape as capture_eval_rc2.sh).
# Usage: capture_local.sh <outdir> [patients_file]
AUTH="${OPENMRS_AUTH:-admin:Admin123}"; BASE="${OPENMRS_REST:-http://localhost:8081/openmrs/ws/rest/v1}"
OUT="$1"; PLIST="${2:?usage: capture_eval_local.sh <outdir> <patients.txt>}"; mkdir -p "$OUT"
PATIENTS=()
while IFS= read -r line; do [ -n "$line" ] && PATIENTS+=("$line"); done < "$PLIST"
QUERIES=("Is the patient enrolled in any programs?" "Does the patient have any allergies?" "What medications is the patient taking?" "Does the patient have any eye problems?" "Does the patient have any heart or cardiac problems?" "Has the patient had any fractures or broken bones?" "Does the patient have any kidney problems?" "Does the patient have any mental health or psychiatric conditions?" "Does the patient have any drug allergies?")
topic_of() {
  case "$1" in
    *drug\ allergies*) echo drug-allergies;;
    *programs*) echo programs;; *allergies*) echo allergies;; *medications*) echo medications;;
    *eye*) echo eye;; *heart*) echo heart;; *fractures*) echo fractures;;
    *kidney*) echo kidney;; *mental*) echo mental;; *) echo unknown;;
  esac
}
start=$(date +%s)
n=0
for uuid in "${PATIENTS[@]}"; do
  [ -z "$uuid" ] && continue
  for q in "${QUERIES[@]}"; do
    t=$(topic_of "$q")
    out="$OUT/${uuid}__${t}.json"
    [ -s "$out" ] && { echo "skip $t/$uuid"; continue; }
    code=$(curl -s -u "$AUTH" --max-time 600 -H "Content-Type: application/json" -X POST "$BASE/chartsearchai/search" \
      -d "$(printf '{"patient":"%s","question":"%s"}' "$uuid" "$q")" -o "$out" -w "%{http_code}")
    if [ "$code" != 200 ]; then echo "WARN: $uuid/$t HTTP $code (not scored)" >&2; mv "$out" "$out.err" 2>/dev/null; fi
    n=$((n+1))
  done
  echo "done patient $uuid ($n cells, $(( $(date +%s) - start ))s)"
done
echo "CAPTURE_DONE $(ls "$OUT"/*.json 2>/dev/null | wc -l | tr -d ' ') cells in $(( $(date +%s) - start ))s -> $OUT"

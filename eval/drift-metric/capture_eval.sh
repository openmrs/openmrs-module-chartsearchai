#!/bin/bash
# Capture full response JSON per cell. Arg1 = output dir. (bash 3.2 safe: no associative arrays)
AUTH="${OPENMRS_AUTH:-admin:Admin123}"; BASE="${OPENMRS_REST:-http://localhost:8080/openmrs/ws/rest/v1}"
OUT="$1"; mkdir -p "$OUT"
PATIENTS=("4acc0b80-83c4-40f7-86fd-0e11a68dd405" "07e26b8e-00a9-4b31-b805-3560ad4e9e2e" "be83f269-66bd-4ba1-80ec-cc62d0d0c84e" "61d0a9db-d35f-40c9-aeae-ccd264470de5")
QUERIES=("Is the patient enrolled in any programs?" "Does the patient have any allergies?" "What medications is the patient taking?" "Does the patient have any eye problems?" "Does the patient have any heart or cardiac problems?" "Has the patient had any fractures or broken bones?" "Does the patient have any kidney problems?" "Does the patient have any mental health or psychiatric conditions?")
topic_of() {
  case "$1" in
    *programs*) echo programs;; *allergies*) echo allergies;; *medications*) echo medications;;
    *eye*) echo eye;; *heart*) echo heart;; *fractures*) echo fractures;;
    *kidney*) echo kidney;; *mental*) echo mental;; *) echo unknown;;
  esac
}
for uuid in "${PATIENTS[@]}"; do
  for q in "${QUERIES[@]}"; do
    t=$(topic_of "$q")
    if [ "$t" = unknown ]; then echo "ERROR: no topic mapping for query: $q (add a case to topic_of)" >&2; exit 1; fi
    out="$OUT/${uuid}__${t}.json"
    code=$(curl -s -u "$AUTH" --max-time 180 -H "Content-Type: application/json" -X POST "$BASE/chartsearchai/search" \
      -d "$(printf '{"patient":"%s","question":"%s"}' "$uuid" "$q")" -o "$out" -w "%{http_code}")
    # Keep only HTTP 200 as a scoreable .json; an error body (401/500) would otherwise score as a
    # legitimate empty answer (false abstention). Move non-200 aside so the scorer's *.json glob skips it.
    if [ "$code" != 200 ]; then echo "WARN: $uuid/$t HTTP $code -> $out.err (not scored)" >&2; mv "$out" "$out.err" 2>/dev/null; fi
  done
done
echo "CAPTURE_DONE $(ls "$OUT"/*.json 2>/dev/null | wc -l | tr -d ' ') cells -> $OUT"

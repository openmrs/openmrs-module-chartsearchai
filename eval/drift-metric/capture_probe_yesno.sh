#!/bin/bash
# Yes/no probe capture (gate for the verdict-lead prompt change, 2026-07-21).
#
# Tier A: the 22 widened-gold patients (capture_eval_rc2.sh PINNED order) x 8 gold topics
# rephrased in the short clinician register (pronoun-free, no "?") that produced the
# motivating hedge. File keys are the same uuid__topic as the eval capture, so
# metric_score.py scores Tier A against metric_gold.rc2.json / offtopic_adj.rc2.json
# unchanged. Directness/verdict axes: score_directness.py.
#
# Tier B: 12 inference probes (is she hypertensive / diabetic / anemic) adjudicated from
# the DB (encounter_diagnosis + condition + obs coded/text sweeps, 2026-07-21): YES-expected
# cells have an explicit condition AND encounter-diagnosis record naming the topic;
# NO-expected cells have zero matching records anywhere. Expected leads live in
# probe_gold_yesno.json. Keys are uuid__probe-<topic>.json (absent from metric_gold, so
# metric_score.py skips them). Includes the motivating Betty "?"-twin pair
# (probe-hypertensive vs probe-hypertensive-qmark): the original punctuation-divergence bug,
# and since #315 one yes/no MEDICATIONS cell on the 3.7.1 standalone cohort — see the comment
# on its fire line for why the other cells here cannot reach that question class.
AUTH="${OPENMRS_AUTH:-admin:Admin123}"; BASE="${OPENMRS_REST:-http://localhost:8081/openmrs/ws/rest/v1}"
OUT="${1:?usage: capture_probe_yesno.sh <outdir> — refusing to burn a capture run with no output dir}"
mkdir -p "$OUT"
# CAPTURE_TIER_B: auto (default) = fire Tier B only on a full default run; 1 = always; 0 = never.
# Lets a failed Tier-B probe be resumed without re-firing 176 Tier-A cells:
#   CAPTURE_PATIENTS="none" CAPTURE_TIER_B=1 capture_probe_yesno.sh <outdir>
TIER_B="${CAPTURE_TIER_B:-auto}"
case "$TIER_B" in 0|1|auto) ;; *) echo "ERROR: CAPTURE_TIER_B='$TIER_B' — must be 0, 1, or auto" >&2; exit 1;; esac
PATIENTS=("bc4ba445-a35c-4996-b804-4d5b68387571" "1128c659-2d0a-4314-af23-91bac1b01109" "59a5f0bb-b863-4213-9177-b883fe9f5f79" "16ca09dd-a8d4-405a-bda6-76d18ed65b25" "dkb00000-0000-0000-0000-000000000001" "813b9f0d-3a8e-4f67-a0dd-d9b3eeef65c5" "489db738-ad5f-4335-a9f1-270ec0c76ea2" "5b24c81f-2b66-41ed-8f2d-158433d531cc" "b360ca49-d432-404f-9b6c-aa6a66125693" "bd3927f4-8c75-470b-bdbb-6c92857b2205" "c34d0124-76c9-4197-9f84-35e44e1317a8" "007e38b8-1344-4ea9-a790-a3f078471db3" "089f766b-943c-427b-a039-672f90b0a49e" "520016d7-67ae-40fa-aa8f-e8a5ec2b8fd6" "8d9fc13a-5d4c-4c5c-b265-23ac067835a4" "53927035-f177-4144-9d3f-b80ced7614bc" "072f69ce-f25a-4ca0-b2ff-4a7a4325ddd9" "0563178c-e107-43a0-be05-2a179ab02dbe" "3d6b5ada-c402-4f3f-9c70-6a17f4d2a339" "ec6af3d5-3082-45f4-8f14-cf42dad41ed2" "47028119-e1e0-467c-b807-a23d1a81fb2b" "e9712a18-c181-46c5-8a17-46b02e39b23b")
if [ "$CAPTURE_PATIENTS" = "none" ]; then PATIENTS=();
elif [ -n "$CAPTURE_PATIENTS" ]; then read -r -a PATIENTS <<< "$CAPTURE_PATIENTS"; fi

# Tier A: topic-key -> short-register phrasing. Keep keys EXACTLY the gold topic slugs.
TOPICS=(programs allergies drug-allergies eye heart fractures kidney mental)
query_of() {
  case "$1" in
    programs) echo "enrolled in any program";;
    allergies) echo "any allergies";;
    drug-allergies) echo "any drug allergies";;
    eye) echo "any eye issues";;
    heart) echo "any heart problems";;
    fractures) echo "ever had a fracture";;
    kidney) echo "any kidney issues";;
    mental) echo "any psych history";;
  esac
}

fire() { # uuid, question, outfile
  # Capture into a temp file and promote only on HTTP 200: firing straight at the final
  # path let a RESUME against a down/500ing standalone destroy the previous run's good
  # cell (curl overwrote it with an error body, or the mv renamed it to .err).
  local code rc
  code=$(curl -s -u "$AUTH" --max-time 600 -H "Content-Type: application/json" -X POST "$BASE/chartsearchai/search" \
    -d "$(printf '{"patient":"%s","question":"%s"}' "$1" "$2")" -o "$3.tmp" -w "%{http_code}")
  rc=$?
  # Both checks: a 200 header followed by a truncated body (server died mid-response,
  # --max-time fired) reports code=200 with curl rc=18/28 — promoting on the code alone
  # would overwrite a good cell with a partial body.
  if [ "$rc" -eq 0 ] && [ "$code" = 200 ]; then
    mv "$3.tmp" "$3"
  else
    echo "WARN: $3 HTTP $code curl-rc=$rc (not scored; any prior good capture kept)" >&2
    mv "$3.tmp" "$3.err" 2>/dev/null || rm -f "$3.tmp"
    echo "$(basename "$3" .json): $code NOT-PROMOTED"
    return
  fi
  echo "$(basename "$3" .json): $code"
}

for uuid in "${PATIENTS[@]}"; do
  for t in "${TOPICS[@]}"; do
    fire "$uuid" "$(query_of "$t")" "$OUT/${uuid}__${t}.json"
  done
done

# Tier B — by default only on a full pinned-set run (a CAPTURE_PATIENTS subset is a Tier-A
# resume); force with CAPTURE_TIER_B=1, suppress with CAPTURE_TIER_B=0.
if [ "$TIER_B" = "1" ] || { [ "$TIER_B" = "auto" ] && [ -z "$CAPTURE_PATIENTS" ]; }; then
  fire "47028119-e1e0-467c-b807-a23d1a81fb2b" "is he hypertensive"  "$OUT/47028119-e1e0-467c-b807-a23d1a81fb2b__probe-hypertensive.json"
  fire "3d6b5ada-c402-4f3f-9c70-6a17f4d2a339" "is she diabetic"     "$OUT/3d6b5ada-c402-4f3f-9c70-6a17f4d2a339__probe-diabetic.json"
  fire "489db738-ad5f-4335-a9f1-270ec0c76ea2" "is he anemic"        "$OUT/489db738-ad5f-4335-a9f1-270ec0c76ea2__probe-anemic.json"
  fire "53927035-f177-4144-9d3f-b80ced7614bc" "is she anemic"       "$OUT/53927035-f177-4144-9d3f-b80ced7614bc__probe-anemic.json"
  fire "072f69ce-f25a-4ca0-b2ff-4a7a4325ddd9" "is she hypertensive" "$OUT/072f69ce-f25a-4ca0-b2ff-4a7a4325ddd9__probe-hypertensive.json"
  fire "072f69ce-f25a-4ca0-b2ff-4a7a4325ddd9" "is she hypertensive?" "$OUT/072f69ce-f25a-4ca0-b2ff-4a7a4325ddd9__probe-hypertensive-qmark.json"
  fire "bc4ba445-a35c-4996-b804-4d5b68387571" "is she hypertensive" "$OUT/bc4ba445-a35c-4996-b804-4d5b68387571__probe-hypertensive.json"
  fire "bd3927f4-8c75-470b-bdbb-6c92857b2205" "is he hypertensive"  "$OUT/bd3927f4-8c75-470b-bdbb-6c92857b2205__probe-hypertensive.json"
  fire "520016d7-67ae-40fa-aa8f-e8a5ec2b8fd6" "is he hypertensive"  "$OUT/520016d7-67ae-40fa-aa8f-e8a5ec2b8fd6__probe-hypertensive.json"
  fire "1128c659-2d0a-4314-af23-91bac1b01109" "is she diabetic"     "$OUT/1128c659-2d0a-4314-af23-91bac1b01109__probe-diabetic.json"
  fire "59a5f0bb-b863-4213-9177-b883fe9f5f79" "is he diabetic"      "$OUT/59a5f0bb-b863-4213-9177-b883fe9f5f79__probe-diabetic.json"
  fire "e9712a18-c181-46c5-8a17-46b02e39b23b" "is she diabetic"     "$OUT/e9712a18-c181-46c5-8a17-46b02e39b23b__probe-diabetic.json"
  fire "813b9f0d-3a8e-4f67-a0dd-d9b3eeef65c5" "is she anemic"       "$OUT/813b9f0d-3a8e-4f67-a0dd-d9b3eeef65c5__probe-anemic.json"
  # A yes/no MEDICATIONS cell, on the 3.7.1 standalone cohort rather than the rc2 one above
  # (both cohorts already 404 on the other's host, and fire() drops a 404 without scoring it).
  # It exists because the presence topics above cannot reach this question class and the
  # 'medications' eval topic is a wh-question compare_arms.py excludes from verdict scoring by
  # name — so a change that costs the #107 verdict lead on "is he currently taking any
  # medications?" was invisible to every gate in this directory. #315's clause did exactly that
  # for one review round, on a chart of 8 active orders and no ended ones: "Yes — the patient is
  # currently taking the following medications: Advil 400mg [10], …" became "The patient is
  # currently taking the following medications:", n=3 byte-identical per arm.
  fire "dc8560c9-6d2b-45bf-861c-8fcf562ec9b1" "is he currently taking any medications?" "$OUT/dc8560c9-6d2b-45bf-861c-8fcf562ec9b1__probe-current-meds.json"
fi
# Completeness MARKER FILE, not only a log line. compare_arms.py and score_directness.py both
# read $OUT/CAPTURE_DONE to tell a finished arm from one killed midway — a partial arm still
# produces a full-looking table over a biased prefix. This script printed the words and wrote no
# file, so every A/B ever run on its captures reported "no CAPTURE_DONE in the baseline arm ...
# candidate arm" and exited 3: an integrity signal that is always on is one nobody can read,
# which is #178's constant-column defect in another instrument. Written the way
# capture_probe_safety.sh writes its own.
echo "cells=$(ls "$OUT"/*.json 2>/dev/null | wc -l | tr -d ' ') patients=${#PATIENTS[@]} topics=${#TOPICS[@]} tier_b=$TIER_B" \
  > "$OUT/CAPTURE_DONE"
echo "CAPTURE_DONE $(ls "$OUT"/*.json 2>/dev/null | wc -l | tr -d ' ') cells -> $OUT"

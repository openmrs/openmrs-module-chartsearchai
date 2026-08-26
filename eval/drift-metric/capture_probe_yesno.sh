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

# Does Tier B fire on this invocation? Hoisted out of the `if` that guards the Tier-B block so the
# refusal below can see the answer, and read there rather than re-tested.
if [ "$TIER_B" = "1" ] || { [ "$TIER_B" = "auto" ] && [ -z "$CAPTURE_PATIENTS" ]; }; then
  FIRE_TIER_B=1
else
  FIRE_TIER_B=0
fi

# An invocation that fires NEITHER tier has nothing to capture, and is a caller error rather than an
# empty matrix — CAPTURE_PATIENTS=none with Tier B suppressed (explicitly, or by `auto`, which fires
# Tier B only when CAPTURE_PATIENTS is UNSET). Refused here for the reason capture_probe_safety.sh
# refuses its own empty override: "without this the arm would write a CAPTURE_DONE reading cells=0
# and score as a clean, empty pass". This is the pre-flight half of that guard; the post-flight half
# is at the bottom of this file, where a run that fired cells and landed none is refused too.
[ "${#PATIENTS[@]}" -gt 0 ] || [ "$FIRE_TIER_B" = 1 ] || {
  echo "ERROR: nothing to fire — CAPTURE_PATIENTS names no patient and Tier B is not firing (CAPTURE_TIER_B=$TIER_B); refusing to write a CAPTURE_DONE over an empty run" >&2
  exit 1; }

# Fired/landed counts for the marker below. Incremented inside fire(), which runs in THIS shell (no
# pipe, no subshell) — the intended count is therefore whatever the loops actually asked for, not a
# hand-kept tally that can drift from the fire lines.
FIRED=0
PROMOTED=0

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
  FIRED=$((FIRED + 1))
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
    PROMOTED=$((PROMOTED + 1))
  else
    echo "WARN: $3 HTTP $code curl-rc=$rc (not scored; any prior good capture kept)" >&2
    mv "$3.tmp" "$3.err" 2>/dev/null || rm -f "$3.tmp"
    echo "$(basename "$3" .json): $code NOT-PROMOTED"
    return
  fi
  echo "$(basename "$3" .json): $code"
}

# Any marker already in this directory is CLEARED before the first cell is fired, so the file's
# presence means "the invocation that wrote it landed cells" and never "some earlier invocation did".
# Without this the refusal at the bottom is only half a guard: it declines to WRITE a marker over a
# run that landed nothing, but a re-capture into a non-empty directory — the documented Tier-B resume
# above, or the same arm re-fired after a GP swap — inherits the previous run's marker, and fire()
# keeps the previous run's good cells (the failed body goes to .err). So a wholesale failure left the
# directory byte-for-byte the previous run PLUS a marker asserting completeness, and compare_arms.py,
# which reads the marker's presence and never its body, scored the OLD wording's answers as the new
# arm's: "class flips: 0", exit 0, for an arm that was never captured. Reproduced against a closed
# port, on a seeded directory holding a previous run's cells and its marker.
#
# Placed HERE, below both refusals above and above the first fire, rather than at the top of the
# file: the invariant is about a run that FIRED and landed nothing, and an invocation refused before
# firing changes nothing in this directory, so there is nothing to fail closed about. At the top it
# also destroyed a COMPLETE arm's marker on a caller error (a mistyped CAPTURE_TIER_B, or
# CAPTURE_PATIENTS=none with Tier B off), and compare_arms.py then exits 3 on that arm until every
# one of its ~190 cells is re-fired. Nothing is fired between the refusals and this line, so the
# invariant above is unchanged.
rm -f "$OUT/CAPTURE_DONE"

for uuid in "${PATIENTS[@]}"; do
  for t in "${TOPICS[@]}"; do
    fire "$uuid" "$(query_of "$t")" "$OUT/${uuid}__${t}.json"
  done
done

# Tier B — by default only on a full pinned-set run (a CAPTURE_PATIENTS subset is a Tier-A
# resume); force with CAPTURE_TIER_B=1, suppress with CAPTURE_TIER_B=0.
if [ "$FIRE_TIER_B" = 1 ]; then
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
  # medications?" was invisible to every gate in this directory. #315's prompt clause did exactly
  # that for one review round, on a chart of 8 active orders and no ended ones: "Yes — the patient
  # is currently taking the following medications: Advil 400mg [10], …" became "The patient is
  # currently taking the following medications:", n=3 CONSECUTIVE per arm — the weaker protocol
  # ADR Decision 45's methodology note describes, so read it as the reason this cell was added
  # rather than as a rate. That clause is reverted; the gate gap it exposed is not clause-specific,
  # which is why the cell stays.
  fire "dc8560c9-6d2b-45bf-861c-8fcf562ec9b1" "is he currently taking any medications?" "$OUT/dc8560c9-6d2b-45bf-861c-8fcf562ec9b1__probe-current-meds.json"
fi
# Completeness MARKER FILE, not only a log line. compare_arms.py — the scorer this script's
# captures are A/B'd with — reads $OUT/CAPTURE_DONE to tell a finished arm from one killed
# midway (score_probe_safety.py reads its own arm's; score_directness.py reads no marker and
# only names this one in a comment, so do not cite it as a consumer) — a partial arm still
# produces a full-looking table over a biased prefix. This script printed the words and wrote no
# file, so every A/B ever run on its captures reported "no CAPTURE_DONE in the baseline arm ...
# candidate arm" and exited 3: an integrity signal that is always on is one nobody can read,
# which is #178's constant-column defect in another instrument. Written the way
# capture_probe_safety.sh writes its own — the INTENDED cell count, so the body can DISAGREE with
# the directory it sits in and a shortfall is legible in the marker itself.
#
# And refused outright where nothing landed. A marker derived only from `ls "$OUT"/*.json` cannot
# contradict the capture, so an arm that failed wholesale — standalone down, wrong port or auth,
# or the cohort mismatch the README's "standalone gold is unremappable" note describes — wrote
# `cells=0` and compare_arms.py read it as a complete, clean A/B: "cells compared: 0, class
# flips: 0", exit 0. That is a fail-open this script did not have before the marker existed (the
# absent file made exit 3 unconditional), so the marker carries the guard the file's absence used
# to carry: no landed cell, no marker, non-zero exit. That invariant needs the clear above the fire
# loop as well as the refusal here — the refusal alone leaves a PREVIOUS run's marker standing
# over a re-capture that landed nothing, which is the same fail-open one directory older. A partial
# resume needs no separate treatment for the same reason: the clear plus the unconditional write
# below mean the marker in this directory is always the one THIS run wrote, so a resume that lands
# fewer cells than the run before it cannot inherit the larger run's counts.
if [ "$PROMOTED" -eq 0 ]; then
  echo "ERROR: 0 of $FIRED fired cells landed — refusing to write CAPTURE_DONE, because an arm that captured nothing must not read as a clean, empty A/B (compare_arms.py exits 0 on two empty arms with markers). Check the standalone is up at $BASE and the cohort exists on it." >&2
  exit 1
fi
if [ "$PROMOTED" -lt "$FIRED" ]; then
  echo "WARN: only $PROMOTED of $FIRED cells fired this run landed — the marker records both, so read the shortfall before quoting any aggregate" >&2
fi
# cells = fired THIS run (the sibling's meaning); present = every scored cell now in the
# directory, which on a Tier-B-only resume is larger than `cells` by the Tier-A run before it.
echo "cells=$FIRED promoted=$PROMOTED present=$(ls "$OUT"/*.json 2>/dev/null | wc -l | tr -d ' ') patients=${#PATIENTS[@]} topics=${#TOPICS[@]} tier_b=$TIER_B" \
  > "$OUT/CAPTURE_DONE"
echo "CAPTURE_DONE $PROMOTED/$FIRED cells landed -> $OUT"

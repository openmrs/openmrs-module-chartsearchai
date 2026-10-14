# Grounding-judge corpus (issue #612)

Scores the Tier-2 grounding judge (`LlmProvider.entailsBatch` / `entailsListItem`) on hand-labelled
SOURCE/STATEMENT pairs, against a real `llama-server` running the rig's own binary and model. Use it
before changing a judge prompt or the judge model.

`corpus.json` holds three kinds of case:
- **`Y`**: plausible records that the statement repeats. These must stay YES.
- **`Y*`**: records that are clinically implausible (strength, form or route inconsistent) and that
  the statement repeats verbatim. These are #612's failure.
- **`N`**: statements the source does not support. Each one must stay NO: a different dose, route,
  frequency, strength, drug or value; family history; a refuted or provisional diagnosis; a stopped
  order; or something not stated.

A change that fixes `Y*` cells by loosening an `N` cell is a regression, which is why both arms are
always scored over the whole corpus.

## Running it

1. **Capture the judge's real requests.** The prompts are read from these captures and are never
   copied into this directory, so a score is always of the prompt the module sends.
   - Start `capture_server.py <out-dir> canned/batch.json <port>`.
   - On the rig, set `chartsearchai.llm.remote.endpointUrl` to `http://127.0.0.1:<port>/v1/chat/completions`,
     `chartsearchai.llm.remote.modelName` to any value, and `chartsearchai.llm.engine` to `remote`.
   - Ask one question.
   - Restore all three GPs, using a shell `trap`.

   `canned/batch.json` yields an `entailsBatch` request. `canned/list.json` yields an
   `entailsListItem` request, because its answer is one colon-introduced list.

   The canned answers cite chart indexes `[10]`, `[11]` and `[13]`. These must be chart records for
   the patient you ask about: the judge is only called for a citation that resolves.
2. **Score.**

   ```
   score_judge.py --rig <standalone>/appdata/chartsearchai \
       --batch-capture <out>/req-001.json --list-capture <out>/req-001.json \
       [--clause "text to insert before 'Do not use any outside knowledge.'"]
   ```

   The script starts and stops its own `llama-server` on `--port` (18998 by default). Each pair is
   judged in its own call, so the batch coupling #612 also records is out of scope here.

## Recorded result

Scored on 2026-10-14 with the judge `gemma-4-E4B-it-Q4_K_M`, against prompts captured from main
@ 402d6339:
- **Base prompt:** 26 of 30 cases correct.
- **With the clause quoted in #612:** 25 of 30 correct.

Under both prompts, every `N` case was NO.

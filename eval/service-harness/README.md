# Service harness

Asks questions through `LlmInferenceService` as the deployed module does — the drug-safety injection, the model's
reading of the question, its plain-English rewrite, and the chips drawn for it — over one test patient on efavirenz,
RHZE and co-trimoxazole, with the model stubbed. Written for ADR Decision 650: the injector's own probes never reach
this layer, where Decision 644's two defects were found only on a running server.

## Run

From the repository root, with no other Maven build running in the same working tree:

    mvn -o -pl api test -Dtest='LlmInferenceServiceModelReadQuestionContextTest#harness' \
        -Dsurefire.failIfNoSpecifiedTests=false \
        -Dchartsearchai.harness.questions=$PWD/eval/service-harness/questions.txt \
        -Dchartsearchai.harness.out=/tmp/harness-new.jsonl

About seven minutes for the 753 questions here. Without `-Dchartsearchai.harness.questions` the test is skipped, so
the normal build never runs it.

Optional:

- `-Dchartsearchai.harness.reading=YES|NO|AVOID|GIVE` — how the stub reads every question (default `YES`, her
  medications).
- `-Dchartsearchai.harness.rewrites=<file>` — lines `question<TAB>rewrite`, the stub's plain-English rewrite of each.

## Compare two builds

Run it on the old build (for example `git stash` the change, or check out the commit before it), then on the new one,
and compare:

    python3 eval/service-harness/compare.py /tmp/harness-old.jsonl /tmp/harness-new.jsonl

Read every changed answer; a count is not a verdict. Before trusting a comparison that shows no change, check that it can
see one: revert a known fix and confirm the answers it changed are reported.

# How a question reaches the drug-interaction checks

This page explains how chartsearchai decides whether a question gets drug-interaction (DDI)
treatment, and which check runs when it does.

**Short answer: the LLM does not decide.** No model classifies the question. The decision is made
by deterministic Java code *before* the model is called, using two things:

1. **Drug-name matching.** The question text is matched against the names in the loaded drug
   knowledge base.
2. **Cue-word patterns.** Regular expressions over the question text in `QueryScopeRouter`.

The interaction findings themselves are lookups in the knowledge base, not model inference. The
model only writes prose around findings the module has already computed.

Companion documents:

- [README — Drug-reference injection & safety validation](../README.md#drug-reference-injection--safety-validation)
  covers configuration and the response fields.
- [ddi-interaction-question-examples.md](ddi-interaction-question-examples.md) has worked,
  live-verified example questions for each check.
- [ADR Decision 23](adr.md#decision-23-drug-reference-injection--post-answer-drug-safety-validation)
  covers the design, and
  [ADR Decision 130](adr.md#decision-130-whether-a-question-reaches-the-drug-interaction-checks-is-decided-by-code-not-by-a-model)
  covers why the routing is done by code rather than by a model.

---

## Prerequisites

None of this runs unless the drug-reference feature is on:

| Global property | Default | Effect |
|---|---|---|
| `chartsearchai.drugReference.enabled` | `false` | Master switch. Off means no knowledge base is loaded and no drug is ever resolved. |
| `chartsearchai.drugSafety.validateAnswers` | `true` | Runs the safety validator: the pre-answer findings and the `safetyWarnings` chips. |
| `chartsearchai.drugSafety.warnOnInteractions` | `true` | Turns on the interaction checks described below. |

---

## Step 1 — Does the question name a drug?

`DrugReferenceService.findImpliedByQuery(question)` scans the question against every
knowledge-base entry's names (`findByQuery`, via `DrugReference.matchesFoldedText`). When one name
belongs to more than one substance, it keeps only the substances the name actually denotes (issue
#209). The result is the set `DrugSafetyValidator` calls `questionDrugs`.

That set decides which interaction check can run:

| What the question resolved | Check that runs | What it compares |
|---|---|---|
| One or more drugs | **Drug-in-play** | each named drug against the patient's active orders |
| Drugs of two or more distinct substances | **Question-pair**, in addition | the named drugs against each other (`addQuestionPairInteractions`) |
| No drug | **Screening**, but only if step 2 also passes | every active order against every other (`addActiveOrderPairInteractions`) |

The screening check is gated on `questionDrugs.isEmpty()` and the question-pair check needs at
least two substances, so on any one question at most one of those two pairwise checks runs.
Several route or formulation variants of one substance (for example `Dexamethasone` and
`Dexamethasone (ophthalmic)`) count as one substance and do not open the question-pair check.

### Drugs the answer names

In the post-answer pass (step 3), drugs the **answer** names are added to the drug-in-play set as
well, unless the mention only echoes a record the module already put in front of the model
(issues #105 and #360). The pairwise gates above read the **question** alone.

---

## Step 2 — If no drug was named, is it asking to be screened?

`QueryScopeRouter.isInteractionScreening(question)` is true only when **both** of these hold:

1. **A safety cue** (`asksForADrugSafetyReading`), which is either:
   - an `interact*` word: interact, interacts, interacting, interaction, interactions
     (`INTERACTION_CUES`); or
   - a safety-or-change word (`MEDICATION_SAFETY_CUES`): safe, unsafe, safety, dangerous, harmful,
     risk(s/y), worry/worried, concern(s), problem(s), wrong, stop(ped/ping), discontinue(d),
     deprescribe(d), change(d/s), adjust(ed/ment).
2. **The medications intent** (`Intent.MEDICATIONS`), meaning the question contains one of:
   medication(s), medicine(s), meds, drug(s), prescription(s), prescribed.

All cues are case-insensitive and word-boundary anchored, so "interactive" does not trigger.

| Question | Screens? | Why |
|---|---|---|
| "Are any of her current medications interacting with each other?" | yes | `interacting` + `medications` |
| "Should I stop any of the medications he is on?" | yes | `stop` + `medications` |
| "Is it safe to continue her meds?" | yes | `safe` + `meds` |
| "What medications is the patient taking?" | no | medications intent, but no safety or change cue |
| "Any interactions?" | no | safety cue, but no medications word |
| "How does she interact with her care team?" | no | no medications word |

### Why keywords and not a classifier

The reasoning is recorded in
[ADR Decision 130](adr.md#decision-130-whether-a-question-reaches-the-drug-interaction-checks-is-decided-by-code-not-by-a-model).
In brief:

- The safety layer is deterministic by design, so it does not take on the model's run-to-run
  variability ([ADR Decision 23](adr.md#decision-23-drug-reference-injection--post-answer-drug-safety-validation)).
- The same gate must give the same answer in the pre-answer and post-answer passes (step 3).
- Firing on an unrelated question is ranked as worse than missing a phrasing (issue #143). So
  looser synonyms with everyday non-drug meanings ("conflict", "interfere", an unqualified
  "review", a bare "check") are left out, and so are list requests such as "what is she on?".
- There is one definition of "medication question", shared with the contraindication checks
  ([ADR Decision 89](adr.md#decision-89-a-question-asking-to-stop-or-to-worry-about-a-medication-is-an-interaction-screen-and-the-trigger-no-longer-requires-the-word-interact)).

**The trade-off:** phrasing is the weak point. A screening request worded with none of the cues
above does not screen. It is still answered as an ordinary chart question, but with no interaction
findings behind it. Decision 89 widened the cue list after exactly such a miss, and
`DrugSafetyScreeningPhrasingCorpusTest.knownToBeMissed` tracks the misses still open.

A model or embedding classifier for this gate has **not been evaluated**, so it is untested rather
than refuted. Decision 130 states what an evaluation would have to show.

---

## Step 3 — The validator runs twice, behind the same gate

`DrugSafetyValidator.validate` runs at two points in one request:

1. **Before the answer.** `DrugReferenceInjector.preAnswerFindings` calls the validator with an
   **empty** answer. The findings it produces are injected into the prompt as citable
   `safety_finding` records, alongside the knowledge-base records for the drugs involved. The model
   reads them and writes the prose.
2. **After the answer.** The validator runs again over the model's answer to produce the
   `safetyWarnings` chips on the response.

Both passes decide the pairwise checks from the question alone. The call-site comment in
`DrugSafetyValidator` gives the reason: if the answer could change the gate, the prose could
describe an interaction with no chip beside it, or a chip could appear with no prose behind it.

```
question ──► findImpliedByQuery ──► questionDrugs
                                        │
            ┌───────────────────────────┼─────────────────────────────┐
            │ ≥1 drug                   │ ≥2 substances               │ none
            ▼                           ▼                             ▼
      drug-in-play check        question-pair check      isInteractionScreening(question)?
                                                             │ yes            │ no
                                                             ▼                ▼
                                                      screening check   no pairwise check
            └───────────────┬───────────────────────────────────┘
                            ▼
   pre-answer pass: findings injected into the prompt ──► LLM writes the prose
                            ▼
   post-answer pass: same gates ──► safetyWarnings chips + interactionPairs
```

The class and allergy checks (ATC class joins, cross-reactivity groups, contraindications against
allergies and conditions) run beside these, scoped to what the response is about. They are not
gated by `isInteractionScreening`.

---

## Step 4 (optional) — Answering without the model

With `chartsearchai.drugSafety.answerFromFindings` set to `true` (default `false`), some questions
are answered by the module itself, and the model is never asked to restate the findings (issue
#469, [ADR Decision 108](adr.md#decision-108-a-drug-safety-question-the-module-resolved-itself-is-answered-from-its-own-findings-and-the-model-is-not-asked-to-restate-them)).
A question qualifies only when it matches one of two small fixed grammars in `QueryScopeRouter`:

- **`asksWhetherToGiveADrug`** (`PROPOSAL_SHAPES`) — a proposal of one drug and nothing else:
  "Can I give her ibuprofen?", "Is it safe to start her on clarithromycin?", "Is ibuprofen safe
  for this patient?"
- **`asksOnlyToScreenHerMedications`** (`SCREEN_SHAPES`) — a screen of her own medications and
  nothing else: "Are there any drug interactions with her current medications?", "Do any of her
  meds interact?"

These are grammars over word order, not word lists, and they **fail closed**. A question that
doesn't match exactly goes to the model as usual, so a missed phrasing costs nothing. The module
also answers only when there are findings to state and the patient's orders were read and all
resolved (`DrugReferenceInjector.answersFromFindings`).

---

## Where to look in the code

| Concern | Entry point |
|---|---|
| Which drugs a question names | `DrugReferenceService.findImpliedByQuery` |
| Whether a drug-free question asks to be screened | `QueryScopeRouter.isInteractionScreening` |
| The cue vocabularies | `QueryScopeRouter.INTERACTION_CUES`, `MEDICATION_SAFETY_CUES`, `MEDICATIONS_CUES` |
| The check gates | `DrugSafetyValidator.validate` |
| Pre-answer findings injected into the prompt | `DrugReferenceInjector.preAnswerFindings` |
| Answering without the model | `DrugReferenceInjector.answersFromFindings`, `QueryScopeRouter.asksWhetherToGiveADrug`, `QueryScopeRouter.asksOnlyToScreenHerMedications` |

Before changing any of these, read
`api/src/main/java/org/openmrs/module/chartsearchai/reference/CLAUDE.md`, which holds the binding
rules for the drug-safety code.

# How a question reaches the drug-interaction checks

This page explains how chartsearchai decides whether a question gets drug-interaction (DDI)
treatment, and which check runs when it does.

**Short answer: the LLM does not decide.** No model classifies the question. The decision is made
by deterministic Java code *before* the model is called, using two things:

1. **Drug-name matching.** The question text is matched against the names in the loaded drug
   knowledge base.
2. **Cue-word patterns.** Regular expressions over the question text in `QueryScopeRouter`.

The interaction findings themselves are lookups in the knowledge base, not model inference. The
model still writes the answer, and it has more influence on that answer than just phrasing it; see
[What the model does](#what-the-model-does).

Companion documents:

- [README — Drug-reference injection & safety validation](../README.md#drug-reference-injection--safety-validation)
  covers configuration and the response fields.
- [ddi-interaction-question-examples.md](ddi-interaction-question-examples.md) has worked,
  live-verified example questions for each check.
- [ADR Decision 23](adr.md#decision-23-drug-reference-injection--post-answer-drug-safety-validation)
  covers the design, and
  [ADR Decision 130](adr.md#decision-130-whether-a-question-reaches-the-drug-interaction-checks-is-decided-by-code-not-by-a-model)
  covers why the routing is done by code rather than by a model.
- [ddi-system-prompt-evaluation.md](ddi-system-prompt-evaluation.md) records why a dedicated DDI
  system prompt made the answers worse.

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
   - an `interact*` word: interact, interacts, interacted, interacting, interaction, interactions
     (`INTERACTION_CUES`); or
   - a safety-or-change word (`MEDICATION_SAFETY_CUES`): safe, unsafe, safety, danger, dangerous,
     harmful, risk, risks, risky, worry, worried, worrying, concern, concerns, concerned,
     concerning, problem, problems, problematic, wrong, stop, stopped, stopping, discontinue,
     discontinued, deprescribe, deprescribed, change, changed, changes, adjust, adjusted,
     adjustment, adjustments.
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
  "review", a bare "check") are left out, and a list request such as "What medications is the
  patient taking?" does not screen.
- There is one definition of "medication question", shared with the contraindication checks
  ([ADR Decision 89](adr.md#decision-89-a-question-asking-to-stop-or-to-worry-about-a-medication-is-an-interaction-screen-and-the-trigger-no-longer-requires-the-word-interact)).

**The trade-off:** phrasing is the weak point. A screening request worded with none of the cues
above does not screen. It is still answered as an ordinary chart question, but her orders are not
checked against each other. Decision 89 widened the cue list after exactly such a miss, and
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
   `safetyWarnings` chips on the response. Where the module answers the question itself (step 4),
   this pass reads an empty answer instead.

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
doesn't match exactly goes to the model as usual, so a missed phrasing costs nothing. Matching a
grammar is not enough on its own (`DrugReferenceInjector.answersFromFindings`):

- the patient's orders must have been read, and every one of them resolved;
- for a proposal, the question must name exactly one substance, not one she is already taking, and
  an interaction finding must carry a rating that is a reason to withhold it;
- for a screen, the findings must include an interaction between her orders.

---

## What the model does

The routing and the findings are the module's. The model still has three roles, and each is
documented where it is decided; this section links to those places rather than restating them.

1. **It writes the answer.** The pre-answer findings are injected as citable `safety_finding`
   records, alongside the `drug_reference` records for the drugs involved. The model turns them into
   prose and chooses what to cite. With `chartsearchai.drugSafety.answerFromFindings` at its default
   of `false`, every DDI answer is written by the model. See
   [ADR Decision 23](adr.md#decision-23-drug-reference-injection--post-answer-drug-safety-validation).
2. **It can state interactions the checks did not raise.** A `drug_reference` record carries the
   drug's reference text, interactions included. Injection exists so the model can ground those
   facts
   ([README — Drug-reference injection & safety validation](../README.md#drug-reference-injection--safety-validation)),
   so the prose can state an interaction that no finding carries.
3. **Its answer feeds the chips.** In the post-answer pass, drugs the answer names are added to the
   drugs checked, unless the mention only repeats a record the model was shown (issues #105 and
   #360). The `chartsearchai.drugSafety.warnOnInteractions` row of the README's global-property
   table documents this. The pairwise gates read the question alone, so the answer can add chips but
   cannot change which pairwise check runs.

### The prose is the weak part

`answer` is the model's rendering, and `safetyWarnings` is the deterministic layer:
[ddi-interaction-question-examples.md](ddi-interaction-question-examples.md#how-to-ask) says to
judge the chips, not only the prose. A live run recorded in
[ADR Decision 89](adr.md#decision-89-a-question-asking-to-stop-or-to-worry-about-a-medication-is-an-interaction-screen-and-the-trigger-no-longer-requires-the-word-interact)
(sixteen DDI questions on eight patients, 2026-09-10) found no chip that contradicted the chart, yet
six of the sixteen answers were ones a clinician should not read. Two of those six were misses of
the question gate described above, not the model's doing.

That is why the module also checks the model's prose. The README's
[Citation grounding](../README.md#citation-grounding) section describes the first two checks below,
and each check has its own decision:

- a cited reference record reproduced in different words —
  [Decision 61](adr.md#decision-61-prose-the-answer-reproduces-from-a-cited-reference-record-must-be-reproduced-faithfully),
  published on the response per
  [Decision 74](adr.md#decision-74-a-divergence-the-prose-check-finds-is-stated-on-the-response-not-only-in-the-log);
- a dosing ceiling quoted while a stricter one is left unstated —
  [Decision 96](adr.md#decision-96-an-answer-quoting-one-of-a-substances-ceilings-says-which-stricter-one-it-left-unstated);
- an interaction partner the prose left unnamed, which the module names itself —
  [Decision 100](adr.md#decision-100-an-order-the-answer-leaves-unnamed-is-named-by-the-module-not-by-asking-the-model-again).

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

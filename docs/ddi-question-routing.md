# How a question reaches the drug-interaction checks

This page explains how chartsearchai decides whether a question gets drug-interaction (DDI)
treatment, which check runs when it does, and who writes the answer.

**Short answer: code decides first, and the model is asked only to add a check.** Deterministic
Java code reads the question before any answer is generated, using two things:

1. **Drug-name matching.** The question text is matched against the names in the loaded drug
   knowledge base.
2. **Question patterns.** Cue words and fixed phrasings in `QueryScopeRouter`.

Where that code runs no check for a question that names no drug, the model is asked one narrow
yes/no question: does this ask about the safety of her drugs? A YES runs the medication check; a NO
changes nothing ([step 3](#step-3--if-the-code-ran-no-screen-the-model-may-add-one)).

The interaction findings themselves are lookups in the knowledge base, never model inference. For
many drug-safety questions the module also writes the answer itself, with no model call
([step 5](#step-5--answering-without-the-model)). Where the model does write the answer, it has more
influence on it than phrasing; see [What the model does](#what-the-model-does).

Companion documents:

- [README — Drug-reference injection & safety validation](../README.md#drug-reference-injection--safety-validation)
  covers configuration and the response fields.
- [ddi-interaction-question-examples.md](ddi-interaction-question-examples.md) has worked,
  live-verified example questions for each check.
- [ADR Decision 23](adr.md#decision-23-drug-reference-injection--post-answer-drug-safety-validation)
  covers the design.
  [ADR Decision 130](adr.md#decision-130-whether-a-question-reaches-the-drug-interaction-checks-is-decided-by-code-not-by-a-model)
  covers why the routing is done by code, and
  [ADR Decision 489](adr.md#decision-489-a-question-the-wording-rules-do-not-know-is-read-by-the-model-and-a-yes-runs-the-medication-check)
  why the model may now add a check the code missed.
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
| `chartsearchai.drugSafety.warnOnContraindications` | `true` | Turns on the allergy, cross-reactivity and condition checks. |
| `chartsearchai.drugSafety.readQuestionsWithTheModel` | `true` | Lets the model add a medication check the code did not run ([step 3](#step-3--if-the-code-ran-no-screen-the-model-may-add-one)). |
| `chartsearchai.drugSafety.answerFromFindings` | `true` | Lets the module answer a question from its own findings, with no model call ([step 5](#step-5--answering-without-the-model)). |
| `chartsearchai.drugSafety.derivedFindings` | `off` | `major` adds condition-mediated chains (two drugs linked through one condition); see [ADR Decision 111](adr.md#decision-111-drugs-linked-through-one-drug-disease-condition-are-stated-as-one-derived-finding-and-it-is-a-caution). |

---

## Step 1 — Does the question name a drug?

`DrugReferenceService.findImpliedByQuestion(question, orderEntries)` scans the question against every
knowledge-base entry's names. When one name belongs to more than one substance, it keeps only the
substances the name actually denotes (issue #209). It also reads words no entry carries as a name,
such as "alcohol", which is checked as the knowledge base's Ethanol
([ADR Decision 268](adr.md#decision-268-alcohol-a-clinician-asks-about-is-checked-as-the-knowledge-bases-ethanol)).
The result is the set `DrugSafetyValidator` calls `questionDrugs`.

That set decides which interaction check can run:

| What the question resolved | Check that runs | What it compares |
|---|---|---|
| One or more drugs | **Drug-in-play** | each named drug against the patient's active orders |
| Drugs of two or more distinct substances | **Question-pair**, in addition | the named drugs against each other (`addQuestionPairInteractions`) |
| No drug | **Screening**, but only if step 2 also passes | every active order against every other (`addActiveOrderPairInteractions`) |

The screening check needs `questionDrugs` to be empty and the question-pair check needs at least two
substances, so on any one question at most one of those two pairwise checks runs. Several route or
formulation variants of one substance (for example `Dexamethasone` and `Dexamethasone (ophthalmic)`)
count as one substance and do not open the question-pair check.

A question about one of her own drugs that she takes only as part of a combination order puts the
combination's other substances in play too
([ADR Decision 292](adr.md#decision-292-a-question-about-her-own-drug-taken-only-in-a-combination-is-answered-for-that-combination)).

### Drugs the answer names

In the post-answer pass ([step 4](#step-4--the-validator-runs-twice-behind-the-same-gate)), drugs
the **answer** names are added to the drug-in-play set as well, unless the mention only echoes a
record the module already put in front of the model (issues #105 and #360). The pairwise gates
above read the **question** alone.

---

## Step 2 — If no drug was named, is it asking to be screened?

`DrugSafetyValidator.screensHerOrders` decides. It is true for a question that resolved no drug when
**either**:

- `QueryScopeRouter.isInteractionScreening(question)` holds, **and** no unrecognised word stands
  where a drug's name would be. A word in a drug's place that the data does not recognise makes the
  question about that drug, and the answer says the drug was not checked rather than screening her
  orders
  ([ADR Decision 183](adr.md#decision-183-a-drug-name-the-data-does-not-recognise-is-said-to-be-unrecognised-with-the-names-it-carries-and-never-guessed));
  or
- the question names one or two of her orders by their own names, which the reference data does not
  carry, and the screen is what relates them
  ([ADR Decisions 265](adr.md#decision-265-a-question-naming-her-orders-by-their-own-names-is-about-those-orders)
  and [382](adr.md#decision-382-two-words-in-a-two-drug-questions-drug-places-neither-one-the-data-recognises-are-said-not-checked)).

### What `isInteractionScreening` accepts

It started as two cue families together — a safety cue plus a medications word — and has been widened
one missed phrasing at a time. Today it accepts any of these, unless the question asks about
something else beside her medications ("Is orange juice ok with her meds?"):

- **A safety cue together with a medications word.** The safety cues (`asksForADrugSafetyReading`)
  are an `interact*` word (`INTERACTION_CUES`), or a word from `MEDICATION_SAFETY_CUES`: safe,
  unsafe, danger, harmful, risk, worry, concern, problem, wrong, stop, discontinue, deprescribe,
  change, adjust, compatible, contraindicated, clash, conflict, "taken together", "OK together",
  "monitor for", "watch for", and their inflections. The medications words (`MEDICATIONS_CUES`) are
  medication(s), medicine(s), meds, drug(s), prescription(s), prescribed, regimen(s), and "she takes"
  / "is taking".
- **`DDI` or `DDIs`** on their own.
- **The noun "interaction(s)"** on its own, unless a social word makes it about people (staff,
  family, team, communication and the like).
- **A duplicate-therapy question** (`asksAboutDuplicateTherapy`).
- **Fixed phrasings** for asking which drug to give, what to monitor, which of her medications are
  high-risk, whether they need changing, which drugs to avoid, and whether a drug named as hers is
  safe; and the screen phrasings in `SCREEN_SHAPES`.

All cues are case-insensitive and word-boundary anchored, so "interactive" does not trigger.

These rows come from driving the real router and injector, on a patient taking warfarin and
aspirin, with the shipped knowledge base:

| Question | Screens? | Why |
|---|---|---|
| "Are any of her current medications interacting with each other?" | yes | `interacting` + `medications` |
| "Should I stop any of the medications he is on?" | yes | `stop` + `medications` |
| "Is it safe to continue her meds?" | yes | `safe` + `meds` |
| "Do her drugs conflict?" | yes | `conflict` + `drugs` |
| "Any interactions?" | yes | the noun "interactions", with no social word |
| "Any DDIs?" | yes | `DDIs` |
| "Any duplicate therapy?" | yes | a duplicate-therapy question |
| "What medications should he avoid?" | yes | a drugs-to-avoid phrasing |
| "What medications is the patient taking?" | no | medications word, but no safety cue |
| "How does she interact with her care team?" | no | `interact` with no medications word, and it is not the noun "interaction" |
| "Is orange juice ok with her meds?" | no | asks about something else beside her medications |
| "Any drugs that I should not give him?" | no | no pattern matches; this is the gap step 3 covers |

### Why code and not a classifier

The reasoning is recorded in
[ADR Decision 130](adr.md#decision-130-whether-a-question-reaches-the-drug-interaction-checks-is-decided-by-code-not-by-a-model).
In brief:

- The safety layer is deterministic by design, so it does not take on the model's run-to-run
  variability ([ADR Decision 23](adr.md#decision-23-drug-reference-injection--post-answer-drug-safety-validation)).
- The same gate must give the same answer in the pre-answer and post-answer passes (step 4).
- Firing on an unrelated question is ranked as worse than missing a phrasing (issue #143). So a list
  request such as "What medications is the patient taking?" does not screen.

**The trade-off:** the patterns are a closed grammar of English phrasings, and a wording they don't
know runs no screen. `DrugSafetyScreeningPhrasingCorpusTest.knownToBeMissed` tracks the misses still
open. Step 3 exists because widening the grammar one wording at a time never closes the gap.

---

## Step 3 — If the code ran no screen, the model may add one

`LlmInferenceService.readForDrugSafety` runs after the first injection
([ADR Decision 489](adr.md#decision-489-a-question-the-wording-rules-do-not-know-is-read-by-the-model-and-a-yes-runs-the-medication-check)).
It asks the model `LlmProvider.readMedicationSafetyQuestion(question)` only when **all** of these
hold:

- `chartsearchai.drugSafety.readQuestionsWithTheModel` is on;
- the question named no drug, and no unrecognised word stands in a drug's place;
- the screen of her orders did not run, and the module composed no answer;
- the question is not about her allergies, not about something else beside her medications, not
  about a drug's record or how she takes it, not one thing asked in a drug's place, and not about
  drug resistance.

The call is a constrained yes/no classification, not an answer. A YES injects again for a fixed
question, `QueryScopeRouter.MEDICATION_CHECK_QUESTION` ("Does this patient have any drug interactions
or contraindications?"). Where a second call reads the question as asking which drugs to avoid, the
fixed question is `DRUGS_TO_AVOID_QUESTION` instead
([ADR Decision 491](adr.md#decision-491-a-question-the-model-reads-as-asking-which-drugs-to-avoid-says-the-check-lists-none-and-what-a-reading-costs-on-the-demo)).
Every drug-safety step after that, the validator's post-answer pass included, reads the fixed
question. Retrieval and the answering prompt keep the clinician's own words.

**It only adds checks.** A NO, an unreadable reply or a failed model call keeps the first injection.
The model never removes a check the code chose to run, and never decides which drugs a question
names. `MedicationSafetyQuestionEvalTest` (opt-in, against a real model) scores the exact prompt.

---

## Step 4 — The validator runs twice, behind the same gate

`DrugSafetyValidator.validate` runs at two points in one request:

1. **Before the answer.** `DrugReferenceInjector.preAnswerFindings` calls the validator with an
   **empty** answer. The findings it produces are injected into the prompt as citable
   `safety_finding` records, alongside the knowledge-base records for the drugs involved.
2. **After the answer.** The validator runs again to produce the `safetyWarnings` chips on the
   response. Where the model wrote the answer, this pass reads it. Where the module answered
   (step 5), it reads the finding lines of the module's own text.

Both passes decide the pairwise checks from the question alone. The call-site comment in
`DrugSafetyValidator` gives the reason: if the answer could change the gate, the prose could
describe an interaction with no chip beside it, or a chip could appear with no prose behind it.

Each finding states its strength: a reason to withhold the drug, or a caution to note.
`DrugSafetyValidator.licensesWithholding` decides it. Major and unrated findings withhold, and every
rating below Major (Moderate, Minor, unknown) is a caution
([ADR Decisions 37](adr.md#decision-37-a-safety-answers-call-is-as-strong-as-the-findings-rating)
and [109](adr.md#decision-109-a-moderate-interaction-is-a-caution-because-ddinter-reserves-avoid-for-major)).

```
question ──► findImpliedByQuestion ──► questionDrugs
                                           │
            ┌──────────────────────────────┼─────────────────────────────┐
            │ ≥1 drug                      │ ≥2 substances               │ none
            ▼                              ▼                             ▼
      drug-in-play check           question-pair check         screensHerOrders(question)?
                                                                  │ yes          │ no
                                                                  ▼              ▼
                                                           screening check   model reading (step 3)
                                                                                 │ YES → screen
            └────────────────────────┬──────────────────────────────────────────┘
                                     ▼
   pre-answer pass: findings ──► module answer (step 5)  OR  findings injected ──► LLM writes the prose
                                     ▼
   post-answer pass: same gates ──► safetyWarnings chips + interactionPairs
```

The class and allergy checks (ATC duplicate-therapy joins, cross-reactivity groups, contraindications
against allergies and conditions) run beside these. They are not gated by `screensHerOrders`. The
order-driven contraindication check is scoped to what the response is about
(`DrugSafetyValidator.SubjectMatter`).

### Which chips are published

The chips the validator raises are filtered before they reach the response
(`LlmInferenceService.aboutTheDrugAsked`):

- A question that asks nothing about drug safety publishes no chips at all
  ([ADR Decision 390](adr.md#decision-390-a-question-asking-nothing-of-drug-safety-carries-no-drug-safety-finding-and-something-she-would-eat-or-drink-is-offered-no-drugs-name)).
- A question naming a drug publishes only chips about a drug it names
  ([ADR Decision 387](adr.md#decision-387-a-question-naming-a-drug-states-and-publishes-as-chips-only-what-is-about-a-drug-it-names)).
- A screen asked only about interactions marks her contraindications as about another of her
  medications
  ([ADR Decision 190](adr.md#decision-190-a-screen-states-a-mechanism-once-per-drug-and-a-question-about-interactions-only-leaves-her-contraindications-apart)),
  and so does a question about two drugs given together for findings about her other medications
  ([ADR Decision 196](adr.md#decision-196-two-drugs-asked-about-together-one-of-them-hers-are-answered-for-the-other-and-her-drugs-other-findings-are-drawn-apart)).

---

## Step 5 — Answering without the model

With `chartsearchai.drugSafety.answerFromFindings` at its default of `true`
([ADR Decision 131](adr.md#decision-131-answerfromfindings-ships-on-because-decision-108s-gate-was-run)),
the module writes the answer itself for many drug-safety questions, and the model is never called
([ADR Decision 108](adr.md#decision-108-a-drug-safety-question-the-module-resolved-itself-is-answered-from-its-own-findings-and-the-model-is-not-asked-to-restate-them)).
`DrugReferenceInjector.injectRecords` composes it, `PatientChart.getModuleAnswer()` carries it, and
`LlmInferenceService` returns it in place of a model call. With the property off, no module answer
is composed.

This started as two shapes — a proposal of one drug, and a screen of her medications — and has grown
one decision at a time to cover many more. Among them are two drugs together, her own drug by name,
a drug class, her drug allergies, duplicate therapy, a drug the data does not recognise, a proposal
the check related to none of her orders, and a patient with no active orders. Read the
`moduleAnswer` assignments in `injectRecords` for the current list rather than a copy here.

What they all share:

- **It states what a finding positively says, never a clearance.** "Can be given" or "no
  interactions were found" would be true only if every check ran over a fully read chart, which the
  module cannot establish (`answersFromFindings` javadoc). A negative is stated only as what one
  named check established, for example that the interaction check related the drug to none of her
  orders
  ([ADR Decision 143](adr.md#decision-143-a-proposal-related-to-none-of-her-orders-is-answered-with-what-the-interaction-check-established)).
- **A categorical "No" is licensed only by a rated interaction**, never by a contraindication,
  whose match is a curated token against free text.
- **Each path fails closed.** A question that matches no composed shape, or a chart that was not
  read in full, goes to the model as usual.

Examples from the same run as the table in step 2 (warfarin and aspirin, shipped knowledge base):

| Question | Module's first line |
|---|---|
| "Can I give her ibuprofen?" | No — this module's drug-safety check found a reason to withhold Ibuprofen. |
| "Do any of her meds interact?" | 1 Major interaction — Warfarin with Acetylsalicylic acid (aspirin). |
| "What medications should he avoid?" | No drug allergies are recorded for this patient. This check does not list other drugs to avoid. |
| "What medications is the patient taking?" | *(none — the model answers)* |

---

## What the model does

The routing and the findings are the module's. The model still has up to four roles, and each is
documented where it is decided; this section links to those places rather than restating them.

1. **It may classify the question.** Only where the code ran no check for a question naming no drug,
   and only to add one (step 3).
2. **It writes the answer, unless the module did.** The pre-answer findings are injected as citable
   `safety_finding` records, alongside the `drug_reference` records for the drugs involved. The model
   turns them into prose and chooses what to cite. See
   [ADR Decision 23](adr.md#decision-23-drug-reference-injection--post-answer-drug-safety-validation).
3. **It can state interactions the checks did not raise.** A `drug_reference` record carries the
   drug's reference text, interactions included. Injection exists so the model can ground those
   facts
   ([README — Drug-reference injection & safety validation](../README.md#drug-reference-injection--safety-validation)),
   so the prose can state an interaction that no finding carries.
4. **Its answer feeds the chips.** In the post-answer pass, drugs the answer names are added to the
   drugs checked, unless the mention only repeats a record the model was shown (issues #105 and
   #360). The pairwise gates read the question alone, so the answer can add chips but cannot change
   which pairwise check runs.

### The prose is the weak part

`answer` is the model's rendering, and `safetyWarnings` is the deterministic layer:
[ddi-interaction-question-examples.md](ddi-interaction-question-examples.md#how-to-ask) says to
judge the chips, not only the prose. A live run recorded in
[ADR Decision 89](adr.md#decision-89-a-question-asking-to-stop-or-to-worry-about-a-medication-is-an-interaction-screen-and-the-trigger-no-longer-requires-the-word-interact)
(sixteen DDI questions on eight patients, 2026-09-10) found no chip that contradicted the chart, yet
six of the sixteen answers were ones a clinician should not read. Two of those six were misses of
the question gate described above, not the model's doing.

That is why the module checks the model's prose, and completes it, after the answer comes back
(`LlmInferenceService.search`). The checks report on the response, for example:

- a cited reference record reproduced in different words —
  [Decision 61](adr.md#decision-61-prose-the-answer-reproduces-from-a-cited-reference-record-must-be-reproduced-faithfully),
  published on the response per
  [Decision 74](adr.md#decision-74-a-divergence-the-prose-check-finds-is-stated-on-the-response-not-only-in-the-log);
- a dosing ceiling quoted while a stricter one is left unstated —
  [Decision 96](adr.md#decision-96-an-answer-quoting-one-of-a-substances-ceilings-says-which-stricter-one-it-left-unstated);
- a finding's severity left unstated or stated without support, and a caution lead over a finding
  that withholds.

The completions append the module's own sentences to the model's answer, for example:

- an interaction partner the prose left unnamed —
  [Decision 100](adr.md#decision-100-an-order-the-answer-leaves-unnamed-is-named-by-the-module-not-by-asking-the-model-again);
- an ended order the answer names, a drug listed with no active order, a dose not checked, an
  unresolved drug class, and a word the module did not recognise as a drug.

The README's [Citation grounding](../README.md#citation-grounding) section describes the grounding
checks.

---

## Where to look in the code

| Concern | Entry point |
|---|---|
| Which drugs a question names | `DrugReferenceService.findImpliedByQuestion` |
| Whether a drug-free question screens her orders | `DrugSafetyValidator.screensHerOrders`, `QueryScopeRouter.isInteractionScreening` |
| The cue vocabularies | `QueryScopeRouter.INTERACTION_CUES`, `MEDICATION_SAFETY_CUES`, `MEDICATIONS_CUES` |
| The model's reading of a question | `LlmInferenceService.readForDrugSafety`, `LlmProvider.readMedicationSafetyQuestion` |
| The check gates | `DrugSafetyValidator.validate` |
| Pre-answer findings injected into the prompt | `DrugReferenceInjector.preAnswerFindings` |
| Answering without the model | `DrugReferenceInjector.injectRecords`, `PatientChart.getModuleAnswer` |
| Which chips are published | `LlmInferenceService.aboutTheDrugAsked` |
| Checks and completions of the model's prose | `LlmInferenceService.search` |

Before changing any of these, read
`api/src/main/java/org/openmrs/module/chartsearchai/reference/CLAUDE.md`, which holds the binding
rules for the drug-safety code.

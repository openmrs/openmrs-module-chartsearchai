# Trying the DDInter drug-interaction feature — worked question examples

A hands-on test script for the DDInter-backed drug-reference source
([`sourceFormat=ddinter`](../README.md#drug-reference-injection--safety-validation), added by
[PR #85](https://github.com/openmrs/openmrs-module-chartsearchai/pull/85) from the
[openmrs-ddi-knowledge-base](https://github.com/pbiondich/openmrs-ddi-knowledge-base) data
project, made the shipped default by
[ADR Decision 36](adr.md#decision-36-the-shipped-default-is-the-whole-ddinter-knowledge-base)).

Every question recorded below was **run against a live standalone and its output recorded
verbatim** — see [How these were verified](#how-these-were-verified) for the exact build and
configuration. The deterministic half — the chips the question and the chart raise, and
`interactionPairs` — was re-derived on `main` @ `4112d327` on 2026-09-29 without a live run; where
it has moved, the example carries a note, and
[Chips re-derived on 2026-09-29](#chips-re-derived-on-2026-09-29) says how and what that could not see.
The examples are grouped by which *arm* of the safety layer they exercise, because the arms
answer different questions and fail differently; a test pass that only ever asks
"can I give her X?" leaves three of them untouched.

Companion documents: [README — Drug-reference injection & safety validation](../README.md#drug-reference-injection--safety-validation)
for the configuration reference, [ADR Decisions 23 & 24](adr.md) for the design, and
[drug-kb-demo.md](drug-kb-demo.md) for a self-contained SQL fixture if your database has no
suitable patients. [ddi-question-routing.md](ddi-question-routing.md) explains how a question is
routed to each arm in the first place.

---

## Contents

- [Before you start](#before-you-start)
- [How to ask](#how-to-ask)
- [The four arms, and what each one answers](#the-four-arms-and-what-each-one-answers)
- [1. "Can I give her X?" — the drug-in-play arm](#1-can-i-give-her-x--the-drug-in-play-arm)
- [2. "Can X and Y be given together?" — the question-pair arm](#2-can-x-and-y-be-given-together--the-question-pair-arm)
- [3. "Are any of her current medications interacting?" — the screening arm](#3-are-any-of-her-current-medications-interacting--the-screening-arm)
- [4. Allergy, cross-reactivity and duplicate therapy](#4-allergy-cross-reactivity-and-duplicate-therapy)
- [5. Plain chart questions the safety layer still annotates](#5-plain-chart-questions-the-safety-layer-still-annotates)
- [6. Turning the knobs](#6-turning-the-knobs)
- [Questions that do *not* work well](#questions-that-do-not-work-well)
- [Rough edges seen during this pass](#rough-edges-seen-during-this-pass)
- [How these were verified](#how-these-were-verified)
- [Setting up your own patients](#setting-up-your-own-patients)

---

## Before you start

The feature is **off by default**. Two global properties gate everything:

| Global property | Default | Set it to |
|---|---|---|
| `chartsearchai.drugReference.enabled` | `false` | `true` — the master switch. With this off, both the injector and the validator short-circuit to empty regardless of every other setting |
| `chartsearchai.drugReference.sourceFormat` | `ddinter` | leave it — `ddinter` is already the default and the whole knowledge base is bundled in the `.omod`, so no download and no `dataFilePath` are needed |

Then confirm what actually loaded, **before** asking anything. The load is lazy, so reading the
global properties back does not tell you what is in memory:

```bash
curl -s -u admin:Admin123 \
  http://localhost:8081/openmrs/ws/rest/v1/chartsearchai/drugreferencestatus | jq
```

On a correctly configured instance the interesting fields are:

```json
{
  "enabled": true,
  "loaded": true,
  "inert": false,
  "entryCount": 2283,
  "sourceFormat": "ddinter",
  "configuredSourceFormat": "ddinter",
  "origin": "classpath:/chartsearchai/ddi-knowledge-base.json",
  "arms": {
    "doseCeilings":      { "coverage": "absent",    "entriesPublishing": 0 },
    "handAuthoredRules": { "coverage": "absent",    "entriesPublishing": 0 },
    "atcCodes":          { "coverage": "published", "entriesPublishing": 1839 },
    "interactions":      { "coverage": "published", "entriesPublishing": 2283 },
    "conditionRules":    { "coverage": "absent",    "entriesPublishing": 0 }
  },
  "crossReactivity": { "loaded": true, "groupCount": 1 }
}
```

Read three things off it:

- **`sourceFormat` must equal `configuredSourceFormat`.** A mistyped `ddinter` silently applies
  the *curated* parser instead and is reported here as a `configured-source-format-not-used`
  finding.
- **`inert: false`.** An inert load is a safety layer that will never warn about anything.
- **`arms.doseCeilings`, `arms.handAuthoredRules` and `arms.conditionRules` are `absent`, and that
  is correct.** DDInter's V1 scope is drug–drug interactions only. The third is the second's
  `condition` leg, reported separately since
  [#378](https://github.com/openmrs/openmrs-module-chartsearchai/issues/378) and also published on
  the `/search` response as `conditionRuleCoverage`, so a clinician's client can say this install did
  not screen her conditions. Do **not** write dose-excess or
  hand-authored allergy/condition test cases against `ddinter` — they belong to
  `sourceFormat=json`. The allergy warnings in [section 4](#4-allergy-cross-reactivity-and-duplicate-therapy)
  come from the patient's own chart crossed with ATC classes and the curated cross-reactivity
  groups, not from the DDInter file.

`findings` is also worth a glance — it reports known defects in the shipped dataset (self-paired
rows, aliases that name a different substance) at INFO rather than pretending the data is clean.

> **One deployment trap, unrelated to the knowledge base but easy to hit here.** If the log shows
> `Unknown column 'reference_slice_records' in 'INSERT INTO'` followed by
> `ChartSearchAiRestController.saveAuditLog Failed to save audit log` after every question, the
> module's `chartsearchai-009` changeset has not run on that database and **audit logging is
> silently dead** — the answers are unaffected, but nothing is being recorded. Check with
> `SELECT id FROM liquibasechangelog WHERE id LIKE 'chartsearchai%'`; if it returns only
> `chartsearchai-002`, the module was dropped in without an upgrade that runs its liquibase.
> The only trace on the response is a missing `questionId` (`README.md`: it "is omitted if audit
> logging fails"), which is easy to overlook, so look once at the start of a test session.

## How to ask

Either drive the patient chart's AI search panel in the browser, or POST directly:

```bash
curl -s -u admin:Admin123 -H 'Content-Type: application/json' \
  -X POST -d '{"patient":"<patient-uuid>","question":"Can I give her ibuprofen?"}' \
  http://localhost:8081/openmrs/ws/rest/v1/chartsearchai/search | jq
```

The browser panel no longer draws `interactionPairs`' counts, `activeOrderClaims` or
`conditionRuleCoverage` ([README](../README.md#search)); it does draw `interactionPairs.belowFloor`
beside the answer ([ADR Decision 127](adr.md)). The counts are still on the wire, so read them from
the POST response.

These fields of the response matter for these tests:

| Field | What it is |
|---|---|
| `answer` | the LLM's prose, **completed by the module**. **Not** the safety output — it is what the model made of the chart plus the injected findings, followed by the sentences the module appends after the model has answered (`LlmInferenceService`): the orders a cited finding covers that the prose left unnamed ([ADR Decision 100](adr.md)), an ended order the prose did not state as ended ([Decision 110](adr.md)), the drugs a question listed as hers that her chart holds no active order for ([Decision 119](adr.md)), and, on a question asking only for her allergies, her orders that conflict with them ([Decision 124](adr.md)). None of the recordings below carries such a sentence. With `chartsearchai.drugSafety.answerFromFindings=true` (default `false`) the module writes the whole answer for two question shapes and says so as `answeredByTheModule: true` ([Decision 108](adr.md)) |
| `safetyWarnings` | the **deterministic** chips. Computed by `DrugSafetyValidator`, not by a model, from the question, the chart and the knowledge base — and from what the answer names and cites, which decides which drugs it puts in play and what the response is about. Each carries `type`, `drug`, `detail`, a `severity` (since [#340](https://github.com/openmrs/openmrs-module-chartsearchai/issues/340)), a `chartOrderBridges` array (since [#347](https://github.com/openmrs/openmrs-module-chartsearchai/issues/347)) and a `restsOnAnUncorroboratedChartMatch` boolean (since [#374](https://github.com/openmrs/openmrs-module-chartsearchai/issues/374)); and later also `namedPartners`, the active orders an interaction chip names — several where one mechanism covers several of them ([ADR Decision 99](adr.md)) — `aboutAnEndedOrder` and `endedOrderStopDate` ([#472](https://github.com/openmrs/openmrs-module-chartsearchai/issues/472)), `aboutACurrentMedication` ([#527](https://github.com/openmrs/openmrs-module-chartsearchai/issues/527)), `currentMedicationOrders` ([#552](https://github.com/openmrs/openmrs-module-chartsearchai/issues/552)) and `statedInTheAnswer` ([Decision 124](adr.md)). `README.md` is canonical for the shape; do not read this row as the whole contract |
| `interactionPairs` | `{"found": N, "reported": M}` — how many above-floor rule pairs the interaction check related and how many survived the chip cap ([#336](https://github.com/openmrs/openmrs-module-chartsearchai/issues/336)); the drug-in-play arm states it too since [#356](https://github.com/openmrs/openmrs-module-chartsearchai/issues/356) and is not capped, so its two numbers are always equal. Both count **pairs**, never chips: since [ADR Decision 99](adr.md) that arm states a mechanism several of her orders share as one chip naming all of them, so its `reported` can exceed the interaction chips beside it — [4b](#4b-a-recorded-allergy-that-names-an-active-order) re-derives as seven pairs on three chips. Since [ADR Decision 127](adr.md) that arm's extent also carries `belowFloor`: her orders it related only below the severity floor, one `{"drug", "partner", "severity"}` per partner — `null` where a pairwise arm stated the extent, `[]` a measurement of none, and never a chip or a statement that the combination is safe (`README.md` says how to render it). Every stated extent now carries the key, and the `pairs:` lines recorded below, which predate it, omit it. `{"found":0}` is MEANT as "an arm ran and related nothing above the severity floor"; `README.md` and `PairChipExtent`'s javadoc name the arrangements where it is not, and [ADR Decision 65](adr.md) carries the one that remains; a cede that leaves an arm no pair is no longer among them on either pairwise arm ([Decisions 69 and 71](adr.md)). **`null` is not completeness** — what it does cover is enumerated in `PairChipExtent`'s class javadoc and in `README.md`, and deliberately nowhere else, so read it there rather than inferring it from the cells below |
| `references` | the records the answer **offers as evidence** — every one the model cited, plus, since [#305](https://github.com/openmrs/openmrs-module-chartsearchai/issues/305), any chart record an injected `safety_finding` the model DID cite was derived from, which carries `attachedByTheModule: true` (`README.md` is canonical). Types: `drug_order`, `allergy`, `condition`, `safety_finding`, `drug_reference`, `active_drug_order` for one of her active orders the retrieved chart did not already carry, since [#354](https://github.com/openmrs/openmrs-module-chartsearchai/issues/354) `drug_class_note` for a question that names a drug class no reference entry is indexed by, and since [#401](https://github.com/openmrs/openmrs-module-chartsearchai/issues/401) `interaction_screen_note` for a screen that related nothing (see [3e](#3e-nothing-found-said-honestly)) |
| `unfaithfullyRenderedCitations` | the citations whose rendering IN THE ANSWER the module found unfaithful to the record they point at ([#337](https://github.com/openmrs/openmrs-module-chartsearchai/issues/337)) — one entry per record however many times the answer diverged from it. This is what turns the rough edge recorded below from a log line into something a test can read. `null` is the absence of a measurement (the early `done` under async grounding states it); `[]` says the check named no citation and is **not** a certificate that the answer was compared and found faithful. `README.md` is canonical for what a client may and may not put beside it |
| `unresolvedDrugClass` | the drug **class** the question named and the module resolved to no substance, or `null` where it states none ([#354](https://github.com/openmrs/openmrs-module-chartsearchai/issues/354)). Deterministic like the chips: the same statement is injected as a citable `drug_class_note` record, but that reaches the response only if the model cites it — so this is what a test on a class-term question reads. `null` is the absence of a statement, never a denial |
| `conditionRuleCoverage` | what the loaded dataset publishes for the hand-authored **condition**-rule arm ([#378](https://github.com/openmrs/openmrs-module-chartsearchai/issues/378)) — `absent`, `published` or `unloaded`. On every `ddinter` cell below it would read **`absent`**, because DDInter publishes no such rule. Two other readings are diagnostic rather than surprising: **`unloaded`** means nothing was read at all, which on a `ddinter`-configured instance almost always means `chartsearchai.drugReference.enabled` was left at its default of `false` — check the master switch before `sourceFormat` or `dataFilePath`; **`published`** on this source means something other than the shipped dataset was loaded. It is a statement about the DATASET: `absent` says the screen had no rule to ask the patient's conditions with, never that no contraindication chip can be raised — the same-drug allergy, shared-subgroup and curated legs are untouched by it. `README.md` is canonical for what a client may read into each value |
| the module's other statements | `misattributedOrderCitations`, `unstatedFindingSeverities`, `unstatedDosingCeilings`, `orderStopDates`, `activeOrderClaims`, `findingCitations`, `findingPartners`, `interactionClaimPairs`, `chartReadForSafety`, `answeredByTheModule`, `cautionLedOverWithholding` and `unfoundedFindingSeverities` ([ADR Decision 128](adr.md)) — each a deterministic statement about this answer or this chart, all added after `77c0f9a2`, the build the recordings below were made on, as were the three rows above it. `README.md` is canonical for what each one means and for what its `null` does and does not say |

> **Judge the chips, not only the prose.** The chips are the tested, deterministic layer; the
> prose is a small local model's rendering of it. Several examples below are cases where the
> chips are right and the prose is imperfect — which is the whole reason the chips exist as a
> separate channel.

## The four arms, and what each one answers

| Arm | Fires when | What it checks | States `interactionPairs`? |
|---|---|---|---|
| **Drug-in-play** | the question names a drug — or the answer names one that no record it cites, and no reference record the module injected, already names | that drug × every active order | yes, since [#356](https://github.com/openmrs/openmrs-module-chartsearchai/issues/356), where neither pairwise arm stated one *and* the question resolved a drug it could screen |
| **Question-pair** | the question resolves reference entries of **≥2** substances ([#433](https://github.com/openmrs/openmrs-module-chartsearchai/issues/433)) | those drugs against each other | yes — unless it ceded every pair to the drug-in-play arm, where it states nothing ([#336](https://github.com/openmrs/openmrs-module-chartsearchai/issues/336)) |
| **Screening** | the question names **no** drug *and* reads as a screening request | every active order × every other | yes — unless a cede left it with no pair of its own, where it states nothing and, no fallback standing behind it, nothing else does either ([#370](https://github.com/openmrs/openmrs-module-chartsearchai/issues/370)) |
| **Class / allergy** | always, scoped to what the response is about | ATC class and cross-reactivity-group joins against allergies, conditions and other orders | no |

They are **not** equivalent and they do not cover for each other, which is why the sections
below are separate.

Since `3a4cc1af`, what the response is about is the question, the chart records the answer cites, and
a drug the answer names that the module's injected reference records do not — never a name the
answer recites from those records — and on the allergy side only an allergy question or a cited
chart record counts (`SubjectMatter`'s javadoc in `DrugSafetyValidator` carries the measurement).
So a prescribing error nobody asks about is no longer announced beside a question about another
drug — unless the question reads as a medication or an allergy question, which a word such
as *drug*, *medication*, *prescribed*, *allergy* or *reaction* makes it (`QueryScopeRouter`), or the
answer brings the order or the allergy in one of those ways. It has a surface of its own either way:
[`GET /chartsearchai/chartalerts`](../README.md#chart-alerts)
([#280](https://github.com/openmrs/openmrs-module-chartsearchai/issues/280)). And one chip family
runs beside the four without being an arm: where two or more of her active orders carry one
substance, a screening question and a question naming a drug both raise an unrated `interaction`
chip saying which of her orders share it
([#477](https://github.com/openmrs/openmrs-module-chartsearchai/issues/477),
[ADR Decisions 114 and 116](adr.md)) — except for a set whose every shared substance the question
named, which [Decision 112](adr.md)'s finding states instead. Since [Decision 129](adr.md) that
finding also covers a single order, when the question proposes a drug she already takes:
*"Ibuprofen is already in active order Advil 400mg — adding it would duplicate that order"* (1c). It
relates no pair, so `interactionPairs` never counts it.

**Where `interactionPairs` comes from.** Three arms state it. The two *pairwise* ones have
mutually exclusive gates — the question-pair arm needs entries of two or more substances, the screening
arm needs none — so at most one of those runs per question and neither can be suppressed by the
other's cap. Where neither of them stated one, the **drug-in-play** arm states it instead
([#356](https://github.com/openmrs/openmrs-module-chartsearchai/issues/356)), which is what a
plain "can I give her X?" now reports: before that fix it reported `null` even while raising
seven interaction chips, so a completed screen that related nothing was indistinguishable from
one nobody ran. That arm does not cover every pairwise silence, though — its statement is gated
on the QUESTION's own substances, of which a screening question has none, so behind a screening
arm that kept no pair nothing states the field at all
([#370](https://github.com/openmrs/openmrs-module-chartsearchai/issues/370)). Two things that arm's number does **not** include, for two different reasons. Its
unrated class-only sentences are out because neither pairwise arm has a class leg, and one wire key
must not mean two things by question shape. Chips it raised for a drug only the *answer* named are
out because the statement is the **question's**: counted over the answer as well, the same question
and chart would report differently according to what the model happened to write. And it states
nothing at all where the chart records no active medication — there was no population to screen.
A question naming what looks like *one* drug can still resolve to several reference entries (route
variants such as `Dexamethasone` / `Dexamethasone (ophthalmic)`). Where those are all rows of one
substance the question-pair arm does not run — no two of them are a clinical pair — and this arm
states the field
([#433](https://github.com/openmrs/openmrs-module-chartsearchai/issues/433)); only entries of more
than one substance open the question-pair arm.

---

## 1. "Can I give her X?" — the drug-in-play arm

Checks a drug named in the question against every one of the patient's **active** orders.

### 1a. A Major interaction against one active order

**Patient:** Mary Smith (`38beca4a-fccf-40e5-907d-1bbbc173b93b`, ID `10000F1`) — active
Simvastatin.
**Question:** *Is it safe to start her on clarithromycin?*

```
answer:  No — Clarithromycin should not be started: it interacts with active order
         Simvastatin, a Major finding [77].
chips:   1 × interaction, severity "Major"
         "Clarithromycin interacts with active order Simvastatin — Major. Coadministration
          with potent inhibitors of CYP450 3A4 may significantly increase the plasma
          concentrations of simvastatin and lovastatin and their active acid metabolites,
          all of which are primarily metabolized by the isoenzyme."
refs:    drug_order, safety_finding
```

**What to check:** the lead is a refusal (`No —`), the chip's `severity` is `"Major"`, and the
mechanism text is DDInter's, not the model's invention. A `safety_finding` reference means the
deterministic finding was injected into the prompt *before* the answer and the model cited it.

### 1b. The same question on a patient whose order has **expired**

**Patient:** Betty Williams (`a7090f70-99b7-4fd9-b60d-f8e0cdee07f6`, ID `100000Y`) — Simvastatin
ordered 2026‑07‑28 with `auto_expire_date` 2026‑08‑04, so it lapsed by its own duration and was
never explicitly stopped.
**Question:** *Is it safe to start her on clarithromycin?*

```
answer:  The records do not address the safety of starting Clarithromycin.
chips:   0
pairs:   null
```

**Recorded before [#356](https://github.com/openmrs/openmrs-module-chartsearchai/issues/356).** On
a build carrying that fix this cell reports `pairs: {"found":0,"reported":0}` instead: her active
Bupivacaine and Lidocaine orders are a population, the drug-in-play arm screened clarithromycin
against them and related none of them above the floor, and it now says so. The chip count is what
this example is about and it is unchanged.

**What to check:** **no** Major chip. This is the interesting half of the pair — run 1a and 1b
back to back. An order that lapses by `auto_expire_date` renders no "stopped" prose at all, so
an implementation that read the rendered text would have called this order live
([#317](https://github.com/openmrs/openmrs-module-chartsearchai/issues/317),
[ADR Decisions 46–47](adr.md)). Silence here is the correct answer.

(Of her *active* orders, Bupivacaine and Lidocaine, the check relates clarithromycin to Lidocaine
alone, at `Unknown` — re-derived with the floor lowered to `unknown`, on the recording build and
on `4112d327` alike, it chips Lidocaine and nothing else — so nothing else fills the gap, and the
recorded response is empty rather than merely missing the statin pair.)

**Re-derived on `4112d327` ([how](#chips-re-derived-on-2026-09-29)), the response is no longer
empty, though the chips still are.** Since [ADR Decision 127](adr.md) the extent names what the
floor kept out: `{"found": 0, "reported": 0, "belowFloor": [{"drug": "Clarithromycin", "partner":
"lidocaine", "severity": "Unknown"}]}`. The statin is still absent, which is what this example
checks.

### 1c. A recorded allergy *and* a Major interaction, on one drug

**Patient:** Barbara Miller (`e30bc8f0-08bb-406c-986a-2b153a495603`, ID `100002U`) — allergic to
Ibuprofen, actively prescribed Ibuprofen and Acetylsalicylate sodium.
**Question:** *Is ibuprofen safe for her?*

```
chips:   3
  contraindication            "The patient has a recorded allergy to Ibuprofen."
  interaction  Major          "Ibuprofen interacts with active order Acetylsalicylic acid
                               (aspirin) — Major. The antiplatelet and cardioprotective effect
                               of low-dose aspirin may be antagonized … Ibuprofen is in the
                               same cross-reactivity group (NSAID) as active order
                               Acetylsalicylic acid (aspirin) — possible additive or
                               duplicate-class therapy"
  contraindication            "Acetylsalicylic acid (aspirin) is in the same cross-reactivity
                               group (NSAID) as the patient's allergy to Ibuprofen —
                               possible cross-reactivity"
refs:    drug_order, allergy, safety_finding × 2
```

**What to check:** three *different* mechanisms fire on one question — the allergy naming the
drug outright, the DDInter pair, and the cross-reactivity group reaching back at the drug she is
already on. The interaction chip also carries a folded duplicate-therapy sentence; that fold is
deliberate ([#171](https://github.com/openmrs/openmrs-module-chartsearchai/issues/171)) and the
chip keeps reporting the *rule's* rating, `Major`.

**Re-derived on `4112d327`, the question and her chart raise the first two of these three, and one
the recording has not:** *"Ibuprofen is already in active order Advil 400mg — adding it would
duplicate that order"*, unrated, which a question proposing a drug she already takes now gets
([ADR Decision 129](adr.md)). The third — her aspirin order against her ibuprofen allergy — no
longer opens from the question: since `3a4cc1af` an allergen a drug question happens to name is not
what the response is about (`SubjectMatter`'s javadoc in `DrugSafetyValidator`). It still opens
where the answer cites a record of hers naming ibuprofen or aspirin: given an answer that cites her
allergy record, or her Advil order, `4112d327` raises all three recorded chips beside the new one.
The recorded answer cited her allergy record — its `refs` include `allergy` — so a re-run whose
answer cites it the same way gets the three recorded ones again. The finding itself is not lost: it is one of her standing findings on
[`GET /chartsearchai/chartalerts`](../README.md#chart-alerts). The two recorded chips that remain
also say `aboutACurrentMedication: true` — she already takes ibuprofen, as Advil 400mg.

### 1d. Cross-reactivity across ATC branches (the curated group)

**Patient:** Barbara Miller, as above.
**Question:** *Is aspirin safe for her?*

```
answer:  No — Aspirin should not be given: Acetylsalicylic acid (aspirin) is in the same
         cross-reactivity group (NSAID) as the patient's allergy [1] — possible
         cross-reactivity, a reason to withhold it [252]. …
chips:   3 — the cross-reactivity contraindication above, the same Major interaction seen
             from the other side, and the allergy chip on her own Ibuprofen order
```

**What to check:** aspirin is `N02BA01` and ibuprofen is `M01AE01` — different ATC *branches*,
so no class join can relate them. This chip comes from
`cross-reactivity-groups.json`, the curated dataset that closes exactly this boundary
([ADR Decision 24](adr.md#decision-24-drug-reference-as-a-pluggable-consumer-of-authoritative-datasets)).
If this example produces nothing, check `crossReactivity.groupCount` on the status endpoint.

**Re-derived on `4112d327` ([how](#chips-re-derived-on-2026-09-29)), the question and her chart raise
the first two of these three**, as they do on the recording build, and one the recording has not:
*"Acetylsalicylic acid (aspirin) is already in active order Aspirin 81mg — adding it would duplicate
that order"* ([ADR Decision 129](adr.md)). On `4112d327` the third recorded chip — her Advil order
against her ibuprofen allergy — opens only from the answer's citation of her allergy record, `[1]`
above: given an answer citing it, all three recorded chips come back, and without the citation two.
Both recorded findings now treat aspirin as a medication she already takes: their clause reads *"a
reason to change a medication this patient is already taking"*, where the one the recorded answer
copied read *"a reason to withhold it"* ([ADR Decision 123](adr.md)), and both recorded chips say
`aboutACurrentMedication: true`.

### 1e. An allergy recorded under a *localized* spelling

**Patient:** Kevin Brown (`e89247a2-111f-4dd5-974d-25bfad03690a`, ID `100007G`) — allergy
recorded as **"Clarithromycine"**, active order likewise.
**Question:** *Can I give him clarithromycin?*

```
chips:   1 × contraindication  "The patient has a recorded allergy to Clarithromycin."
refs:    allergy, safety_finding
```

**What to check:** the English question reaches the French-spelled chart row, and the chip prints
the reference substance's own name rather than echoing the chart string.

**Re-derived on `4112d327` ([how](#chips-re-derived-on-2026-09-29)), the same chip, and one beside it
the recording has not:** *"Clarithromycin is already in active order Clarithromycine Co 500mg —
adding it would duplicate that order"* ([ADR Decision 129](adr.md)), the English question reaching
the French-spelled order as well. The allergy finding now says *"a reason to change a medication this
patient is already taking"*, where the recording build's said *"a reason to withhold it"*
([ADR Decision 123](adr.md)).

### 1f. Name the substance, not the family

**Patient:** Margaret King (`f2397b8a-cf51-4813-aa2a-90f1a78a9e39`, ID `100011V`) — allergy to
Botulinum toxin type A.
**Question:** *Can I give her Botulinum toxin type A?*

```
answer:  No — Botulinum toxin type A should not be given: the patient has a recorded
         allergy to Botulinum toxin type A [1], and this finding is a reason to withhold
         it [122].
chips:   1 × contraindication
pairs:   {"found": 0, "reported": 0}
```

Now ask the same thing as *"Can I give her botulinum toxin?"* and the chip count drops to **0** —
the answer still refuses, but only because the model read the allergy record itself. A partial
family name does not resolve to a knowledge-base entry, so use the substance name the chart or
the dataset publishes when you want to exercise the deterministic layer.

### 1g. Control — a drug with nothing to say

**Patient:** Mary Smith. **Question:** *Can I give her amoxicillin?*

```
answer:  The records do not address the safety of giving Amoxicillin.
chips:   0
```

**What to check:** silence, and specifically **not** a reassurance. The feature is
honest-conservative: it raises warnings and never issues a clearance. Simvastatin × amoxicillin
is present in DDInter but rated `Unknown`, which abstains rather than falsely denying a risk.
Worth running on every pass — it is the false-positive control.

**Re-derived on `4112d327`, still no chip, and the `Unknown` pair is now stated rather than
silent**: `{"found": 0, "reported": 0, "belowFloor": [{"drug": "Amoxicillin", "partner":
"simvastatin", "severity": "Unknown"}]}` ([ADR Decision 127](adr.md)). That is neither a warning nor
a clearance: `README.md` asks a client to render it as her medication with an interaction the
source lists at that rating, and never as a statement that the combination is safe or unsafe.

---

## 2. "Can X and Y be given together?" — the question-pair arm

Checks drugs named in the **question** against each other. The reliable way to test it is a
patient on neither drug: where one of the above-floor rules joining the pair names an active
order, the drug-in-play arm owns that pair and this arm reports nothing for it — see
[2b](#2b-why-a-patient-on-neither-drug-is-the-reliable-choice). The predicate is not "the patient is on one
of them" and is not restated here; `addQuestionPairInteractions`' own javadoc and
[ADR Decision 69](adr.md) carry it.

**Patient:** Betty Williams — on neither warfarin nor ibuprofen, but allergic to aspirin.
**Question:** *Can warfarin and ibuprofen be given together?*

```
answer:  No — Warfarin and Ibuprofen should not be given together: Warfarin interacts with
         Ibuprofen, a Major risk, as Nonsteroidal anti-inflammatory drugs (NSAIDs) may
         potentiate hypoprothrombinemic effect and bleeding risk associated with oral
         anticoagulants [241]. Additionally, Ibuprofen is in the same cross-reactivity group
         (NSAIDs) as the patient's allergy to Aspirin [240].
pairs:   {"found": 1, "reported": 1}
chips:   2
  interaction  Major   "Warfarin interacts with Ibuprofen, also named in the question — Major…"
  contraindication     "Ibuprofen is in the same cross-reactivity group (NSAID) as the
                        patient's allergy to Acetylsalicylic acid (aspirin) …"
```

**What to check:** the phrase **"also named in the question"** — that is this arm's signature,
distinguishing it from a chip about an active order. And note that the arm is still
patient-aware: it picked up her aspirin allergy against a drug she is not on.

### 2b. Why a patient on neither drug is the reliable choice

Ask the *identical* question of Barbara Miller, who is actively prescribed ibuprofen:

```
question: Can warfarin and ibuprofen be given together?
pairs:    {"found": 0, "reported": 0}
chips:    5
  interaction  Major  "Warfarin interacts with active order Acetylsalicylic acid (aspirin) …"
  interaction  Major  "Warfarin interacts with active order Ibuprofen — Major. Nonsteroidal
                       anti-inflammatory drugs (NSAIDs) may potentiate the hypoprothrombinemic
                       effect and bleeding risk associated with oral anticoagulants."
  interaction  Major  "Ibuprofen interacts with active order Acetylsalicylic acid (aspirin) …"
  contraindication    "The patient has a recorded allergy to Ibuprofen."
  contraindication    "Acetylsalicylic acid (aspirin) is in the same cross-reactivity group
                       (NSAID) as the patient's allergy to Ibuprofen …"
```

The pair *is* reported — as **"active order Ibuprofen"**, by the drug-in-play arm, because the
chart owns it. Drive this arm on a patient prescribed neither drug, or you will only ever see the
other one.

**That `pairs` line is not what a build carrying [#336](https://github.com/openmrs/openmrs-module-chartsearchai/issues/336)'s
fix produces, and the run above has not been repeated live on one.** The zero was recorded before it: the
question-pair arm ceded its only pair to the drug-in-play arm and then reported a complete screen of
none, which is defined as *the reference data related none of the pairs enumerated* and was false —
the arm related one and handed it over. It now states nothing at all, so the arm that *did* report
those pairs states the field instead, and what it states is the number of above-floor rule pairs it
related for the question's own substances. On the mirror of this arrangement driven through the real
`validate` over the DDInter test excerpt, that is `{"found": 3, "reported": 3}`, and re-derived from
this patient's own chart on `4112d327` ([how](#chips-re-derived-on-2026-09-29)) it is the same — beside
**four** chips, not five: the aspirin cross-reactivity contraindication no longer opens from the
question, for [1c](#1c-a-recorded-allergy-and-a-major-interaction-on-one-drug)'s reason. Treat the
block above as the pre-fix recording it is.
Where only **some** of a question's pairs are ceded the arm keeps the field — `README.md`'s
`interactionPairs` section is the client contract for that, and for why this field never counts the
chips beside it. The *screening* arm has a cede of its own and takes the same rule
([ADR Decision 71](adr.md)) — with no fallback behind it, so there the statement is simply absent
and a client must not read that absence as completeness.

---

## 3. "Are any of her current medications interacting?" — the screening arm

Checks the patient's active orders against **each other**, with no drug named in the question.
This is the arm most likely to be missed in testing, and the one where `interactionPairs`
matters most.

### 3a. One Major pair on a two-drug chart

**Patient:** Helen Roberts (`83f95445-d471-4e9c-b10e-a89b6632dbe8`, ID `10001A8`) — Methotrexate
and Salicylic acid.
**Question:** *Does she have any drug interactions I should know about?*

```
answer:  No — Salicylic acid should not be given: it interacts with active order
         Methotrexate, a Major problem [61].
pairs:   {"found": 1, "reported": 1}
chips:   1 × interaction, severity "Major"
         "…Salicylates may interfere with the renal elimination of methotrexate and may
          displace it from binding sites."
```

The cleanest single-pair case in the set. (The lead reads as a prescribing refusal even though
both drugs are already prescribed — see [rough edges](#rough-edges-seen-during-this-pass).)

### 3b. A Moderate pair

**Patient:** Kenneth Hernandez (`b65f951f-67da-4a5a-9282-0d563d976fe2`, ID `10000U8`) — Enalapril
and Salicylic acid.
**Question:** *Are any of his current medications interacting with each other?*

```
pairs:   {"found": 1, "reported": 1}
chips:   1 × interaction, severity "Moderate"
         "…Nonsteroidal anti-inflammatory drugs (NSAIDs) may attenuate the antihypertensive
          effects of ACE inhibitors…"
```

**What to check:** `severity` is `"Moderate"`, not `"Major"`. Severity is a first-class field on
the wire, so a client can rank rather than parse it out of prose.

### 3c. Two Majors, a Minor, and two `Unknown` pairs that stay quiet

**Patient:** Susan Young (`763e6e5f-c489-4bab-8a55-c379f085dd1c`, ID `10000NH`) — Botulinum
toxin type A, Lidocaine, Metoclopramide, Neomycin, Tiotropium.
**Question:** *Are any of her current medications interacting with each other?*

```
answer:  Yes, there are drug interactions recorded: Botulinum toxin type A interacts with
         active order Neomycin [46], a Major interaction [46]. Lidocaine interacts with
         active order Metoclopramide [47], a Major interaction [47]. Lidocaine interacts
         with active order Neomycin [48], a Minor interaction [48].
pairs:   {"found": 3, "reported": 3}
chips:   5  (3 interaction + 2 contraindication from her Lidocaine and Tiotropium allergies)
  Major  Botulinum toxin type A × Neomycin   — neuromuscular blockade potentiation
  Major  Lidocaine × Metoclopramide          — methemoglobinemia
  Minor  Lidocaine × Neomycin                — "No special precautions are necessary."
```

**What to check:** all three severity levels in one response, the Majors leading the prose, and
`found == reported == 3` even though her chart also holds Tiotropium × Lidocaine and
Tiotropium × Metoclopramide — pairs DDInter rates `Unknown`, which sits *below* the default
`minInteractionSeverity=minor` floor and so is **not counted as a finding at all**. Abstaining
on `Unknown` is the designed behaviour, not a miss: the dataset saying it does not know is not
the dataset saying the combination is safe.

### 3d. A capped screen — the completeness contract

**Patient:** Sarah Taylor (`dc8560c9-6d2b-45bf-861c-8fcf562ec9b1`, ID `10000TA`) — eight active
NSAID and corticosteroid orders, plus Dexamethasone and Hydrocortisone allergies.
**Question:** *Are any of her current medications interacting with each other?*

```
pairs:   {"found": 18, "reported": 10}
chips:   20  (10 interaction + 10 contraindication)
```

**This is the single most important field to check in the whole document.** Eighteen pairs were
related; ten survived `chartsearchai.drugSafety.maxPairChips`. Eight real findings are **not** in
the payload, and the only thing on the wire that says so is `interactionPairs`. A client that
counts chips instead publishes a ratio of two different things — the chip list also carries
contraindication and class chips that were never pairs. See
[#336](https://github.com/openmrs/openmrs-module-chartsearchai/issues/336) and
[ADR Decision 60](adr.md). Raise the cap in [section 6](#6-turning-the-knobs) to see the other
eight.

The server log reports how many went and at what ratings, which is a second way to check the field
is telling the truth. It does **not** name them: since
[#439](https://github.com/openmrs/openmrs-module-chartsearchai/issues/439)
([ADR Decision 102](adr.md)) a withheld pair is reported by its rating alone, because both sides of
one are this patient's own prescriptions and the line reaches the default server log. The capture
below is from before that change, kept because its numbers are the ones this section is about; the
same event today ends `WITHHOLDING 8, rated, least severe last:` and a list of eight `Moderate`.

```
WARN DrugSafetyValidator.addActiveOrderPairInteractions   (captured before #439)
  Interaction screening across 17 active-order reference entries found 18 pair(s) above the
  severity floor; reporting the 10 most severe and WITHHOLDING 8: Celecoxib x Dexamethasone
  (Moderate); Celecoxib x Diclofenac (Moderate); Celecoxib x Hydrocortisone (Moderate);
  Dexamethasone x Diclofenac (Moderate); Diclofenac x Methylprednisolone (Moderate);
  Diclofenac x Prednisone (Moderate); Diclofenac x Budesonide (Moderate);
  Diclofenac x Hydrocortisone (Moderate)
```

### 3e. Nothing found, said honestly

**Patient:** Mark Smith (`de4b0d62-8a47-4c82-8220-1f0a87eafd46`, ID `100004N`) — Chloroquine and
Diphenhydramine, a pair DDInter does not relate at all.
**Question:** *Are any of his current medications interacting with each other?*

```
answer:  The records do not address drug interactions between the patient's current
         medications.
pairs:   {"found": 0, "reported": 0}
chips:   0
```

**What to check:** `{"found": 0, "reported": 0}` and **not** `null`. Zero is a measurement — this
screen ran and related nothing — and reading the two alike is how a client comes to claim
completeness it was never told about. What `null` covers is not restated here; it lives in
`PairChipExtent`'s class javadoc and in `README.md`, which is where the field's table cell above
already points.

**Since [#401](https://github.com/openmrs/openmrs-module-chartsearchai/issues/401) the model has a
citable statement of that zero**, where before it read an empty slice. Re-derived on this chart on
`4112d327`, the screen injects an `interaction_screen_note`: *"No interactions were found among this
patient's active medications. 2 of them were checked against each other and the reference data
relates none of them at or above the configured severity level. This check compares individual
substances: relationships resting only on two drugs sharing a drug class are not part of it, so it is
not a statement that no relationship exists."* The answer above predates the note, so do not expect
a re-run to repeat that answer; the field to check is unchanged.

### 3f. Local brand names — where the chip earns its keep

**Patient:** Michael Turner (`d35b0325-9924-4baa-93ec-1ca3585f3e2e`, ID `100006J`) — two orders
named only **"Zolvimix"** and **"Klarizom"**, invented brand concepts carrying WHO ATC mappings
(`C10AA01`/`J01FA09` and `J01FA09`).
**Question:** *Are any of his current medications interacting with each other?*

```
answer:  The records do not address interactions between the patient's current medications.
pairs:   {"found": 1, "reported": 1}
chips:   1 × interaction, severity "Major"
         "Simvastatin interacts with active order Clarithromycin — Major. Coadministration
          with potent inhibitors of CYP450 3A4 …"
refs:    (none)
```

**What to check:** the chart says "Zolvimix" and "Klarizom" and nothing else, so the model has no
way to see a statin and a macrolide — and its prose says so. The validator resolved both orders
through their ATC codes and raised the Major anyway. **The deterministic chip caught what the
prose could not**, which is the strongest argument in the document for rendering
`safetyWarnings` in the UI rather than trusting the answer text.

Note `refs: []`: the answer cited nothing and contradicts its own chip. The finding **was** in
the prompt — the injector's DEBUG line for this exact request reads `Injected 0 active-order,
0 drug-reference (0 chars) and 1 safety-finding record(s)` — so this is the model discarding a
finding, not a finding that never arrived. It is discardable because nothing connects the two:
the finding names *Simvastatin* and *Clarithromycin* while every chart record the model can read
says *Zolvimix* and *Klarizom*. See [rough edges](#rough-edges-seen-during-this-pass).

**Since [#349](https://github.com/openmrs/openmrs-module-chartsearchai/issues/349)'s fix
(`9aca5790`, 2026-09-01) the finding does connect them.** Re-derived on `4112d327`, the injected
record ends *"This module resolved the substances named here from this patient's own active orders.
Simvastatin from Zolvimix; Clarithromycin from Klarizom. This finding is a reason to change a
medication this patient is already taking."*, and the chip carries the same two pairs as
`chartOrderBridges`. The recording above predates that clause; whether the model now keeps the
finding has not been re-measured. The DEBUG line quoted above has also gained a count since
([#354](https://github.com/openmrs/openmrs-module-chartsearchai/issues/354)); its format is now
`Injected {} active-order, {} drug-reference ({} chars), {} safety-finding and {} drug-class-note record(s) — …`.

---

## 4. Allergy, cross-reactivity and duplicate therapy

These joins run alongside whichever interaction arm fired, scoped to what the response is about.

### 4a. Duplicate therapy by ATC class

**Patient:** Sarah Taylor. **Question:** *Is she on more than one drug from the same class?*

```
answer:  Yes — the patient is on multiple drugs from the same class: Hydrocortisone
         Injection vial [14], Prednisone Co [17], and Solu-Medrol [17] are all
         corticosteroids.
chips:   17 — 7 interaction (4 duplicate-therapy with severity null, 3 rated Moderate)
              + 10 contraindication
  interaction (severity null)  "Prednisone is in the same ATC class (H02AB) as active order
                                Dexamethasone — possible duplicate therapy"
  interaction (severity null)  "… (H02AB) as active order Hydrocortisone …"
  interaction (severity null)  "… (A07EA) as active order Budesonide …"
  interaction (severity null)  "… (H02AB) as active order Methylprednisolone …"
  contraindication             "Prednisone is in the same ATC class (H02AB) as the patient's
                                allergy to Dexamethasone — possible cross-reactivity"
  …
```

**What to check:** duplicate-therapy chips carry `severity: null` — they are a class-membership
observation, not a rated DDInter rule, and `null` correctly says "the producer stated no
rating" rather than "low". Note the two *different* ATC codes cited (`H02AB` for the systemic
corticosteroids, `A07EA` for Budesonide): the class named is the one the pair actually shares.

The seven interaction chips come from the **answer**, not the question. Re-derived with no answer, on
the recording build `77c0f9a2` and on `4112d327` alike ([how](#chips-re-derived-on-2026-09-29)), the
question raises the ten contraindication chips and nothing else: it names no drug and is not a
screening request, so all seven were raised for drugs the recorded answer named. Given that answer's
words — its markers removed, since they index the live chart — `4112d327` raises none of the seven:
the question's own ten findings are injected into the prompt and name every corticosteroid she
takes, so a drug the answer names from them is an echo, not a proposal
([#360](https://github.com/openmrs/openmrs-module-chartsearchai/issues/360)). (`77c0f9a2` raises
twenty-four chips from the same words: the ten contraindications, the seven recorded Prednisone
chips, and seven like them for Hydrocortisone, which the recorded answer cited as `[14]` — a
citation the stripped words cannot carry. It predates #360, so a drug named by an answer that cites
nothing was never an echo there.)

**To see duplicate therapy on `4112d327`, name one of them in the question.** *"Can I give her
prednisone?"* raises the four duplicate-therapy chips above, severity `null` and with the same two
ATC classes, beside two cross-reactivity contraindications, one Moderate chip naming Celecoxib,
Diclofenac and Ibuprofen, and one saying prednisone is already in her Prednisone Co 5mg order
([ADR Decision 129](adr.md)).

### 4b. A recorded allergy that names an active order

**Patient:** Sarah Taylor — allergic to Dexamethasone and prescribed Dexamethasone.
**Question:** *Can I give her ibuprofen?*

```
chips:   16 — 7 interaction (all Moderate, NSAID × corticosteroid) + 9 contraindication
  contraindication  "The patient has a recorded allergy to Dexamethasone."
  contraindication  "Dexamethasone is in the same ATC class (H02AB) as the patient's allergy
                     to Hydrocortisone (ophthalmic) — possible cross-reactivity"
  contraindication  "Budesonide is in the same ATC class (R01AD) as the patient's allergy to
                     Dexamethasone — possible cross-reactivity"
  …
answer:  No — Ibuprofen should not be given: Ibuprofen interacts with active order
         Methylprednisolone [350], … and Ibuprofen interacts with active order
         Diclofenac [355].
```

**What to check:** the question was about ibuprofen, yet chips fired about the *prescribing
error already in the chart* — she is on a drug she is recorded as allergic to. That the
order-driven arm surfaces here is deliberate and deliberately scoped: it fires because the
response is about her medications.

**That describes the recording build. Re-derived on `4112d327`
([how](#chips-re-derived-on-2026-09-29)), the question and her chart raise four chips, not
sixteen.** Her seven interaction pairs are three chips — the five that share the
corticosteroid mechanism are one, *"Ibuprofen interacts with active order Methylprednisolone,
Prednisone, Budesonide, Dexamethasone and Hydrocortisone — Moderate. …"*
([ADR Decision 99](adr.md)), beside one each for Diclofenac and Celecoxib, the two NSAID × NSAID
pairs the recorded summary line counts among its corticosteroid ones — and `interactionPairs`
is `{"found": 7, "reported": 7}`, with `belowFloor: []`; the fourth, unrated, says *"Ibuprofen is
already in active order Advil 400mg — adding it would duplicate that order"*
([ADR Decision 129](adr.md)). None of the nine contraindication chips is
raised. They came from the recorded answer — with no answer the recording build raises none of them
either — and an answer reciting her orders as the findings' partners, as the recorded one does,
raises none on `4112d327`: a name the answer recites from the module's own findings is no longer
what the response is about (`3a4cc1af`, whose `SubjectMatter` javadoc records this patient's
*"Is aspirin safe for her?"* as that defect). An answer citing her records still can (see
[the arms](#the-four-arms-and-what-each-one-answers)). The prescribing error they
reported is a standing finding now: [`GET /chartsearchai/chartalerts`](../README.md#chart-alerts)
returns it for this chart as the same ten contraindications her screening question raises on
`/search` ([3d](#3d-a-capped-screen--the-completeness-contract)). The findings also treat ibuprofen as a
medication she already takes, as Advil 400mg ([ADR Decision 123](adr.md)): the Diclofenac pair,
which folds a duplicate-therapy sentence, is stated as a reason to change it and the other two as
cautions ([Decision 109](adr.md)), so the recorded *"No — Ibuprofen should not be given"* is not the
call they now state. What the vitals control below shows is unchanged.

Prove the scoping by asking the **same patient** something unrelated —
*"What are her most recent vital signs?"* returns the vitals, `chips: 0` and
`interactionPairs: null`. The sixteen chips above are not attached to the patient, they are
attached to the response ([#143](https://github.com/openmrs/openmrs-module-chartsearchai/issues/143));
this module answers questions and is not an alerting system, so do not treat a silent chip list
on an off-topic question as a bug.

---

## 5. Plain chart questions the safety layer still annotates

**Patient:** Betty Williams — active Bupivacaine and Lidocaine, **expired** Simvastatin, allergic
to Lidocaine.
**Question:** *What medications is she currently taking?*

```
answer:  The patient is currently taking Bupivacaine [3] and Lidocaine [4].
chips:   2
  contraindication  "Bupivacaine is in the same ATC class (N01BB) as the patient's allergy
                     to Lidocaine — possible cross-reactivity"
  contraindication  "The patient has a recorded allergy to Lidocaine."
refs:    drug_order × 2
```

**Two things to check here.** First, **Simvastatin is absent** from the answer — the
expired order is correctly excluded from "currently taking". Second, an ordinary enumeration
question still got annotated: she is prescribed a drug she is allergic to, and a same-class
partner besides. Worth running as a smoke test because it is fast and exercises the
order-driven join without naming any drug.

---

## 6. Turning the knobs

Both properties are read per request — no restart, and the effect is immediately visible.

### `chartsearchai.drugSafety.minInteractionSeverity` (default `minor`)

Re-run **3c** (Susan Young) with the floor at `major`:

```bash
curl -s -u admin:Admin123 -H 'Content-Type: application/json' -X POST -d '{"value":"major"}' \
  http://localhost:8081/openmrs/ws/rest/v1/systemsetting/chartsearchai.drugSafety.minInteractionSeverity
```

| | `minor` (default) | `major` |
|---|---|---|
| `interactionPairs` | `{"found": 3, "reported": 3}` | `{"found": 2, "reported": 2}` |
| interaction chips | Major, Major, **Minor** | Major, Major |

The Lidocaine × Neomycin `Minor` pair drops out of `found` as well as out of the chips — the
floor decides what counts as a finding, not merely what gets rendered. Set it back to `minor`
afterwards. A *typo* in this property falls back to the default rather than silently disabling
every rated rule.

### `chartsearchai.drugSafety.maxPairChips` (default `10`)

Re-run **3d** (Sarah Taylor) with the cap at `25`:

| | `10` (default) | `25` |
|---|---|---|
| `interactionPairs` | `{"found": 18, "reported": 10}` | `{"found": 18, "reported": 18}` |
| total chips | 20 | 28 |

`found` is unchanged — the cap bounds what is *reported*, never what is looked for — and at 25
nothing is withheld, so `found == reported`. This is the fastest way to prove to yourself that
the eight missing findings in 3d were real. Set it back to `10`: every chip is also injected
into the prompt as a citable pre-answer finding, so an uncapped screen writes the whole
cross-product into the context window.

---

## Questions that do *not* work well

Useful to know, and useful as regression bait.

**"What does the drug reference say about warfarin?"** — asking the module to *recite* the
knowledge base rather than apply it to the patient. Warfarin has hundreds of partners in
DDInter; the answer returns an arbitrary alphabetical slice of them
(`ixekizumab`, `ketoconazole`, `ketoprofen`, `ketorolac`), garbles the mechanism text
("CYP405", "hypoprothrombinemice"), and the validator raises chips about drugs the patient is
not on and nobody asked about, because the injected reference record put them in play. That was
recorded before [#355](https://github.com/openmrs/openmrs-module-chartsearchai/issues/355) and
[#360](https://github.com/openmrs/openmrs-module-chartsearchai/issues/360). Re-derived on `4112d327`
for a chart none of warfarin's partners is on (Mark Smith's), the injected record names five
partners, the most severe first, by name and rating only — *"Drug reference — Warfarin (ATC
B01AA03). Interactions: ketoprofen (Major); ketorolac (Major); lepirudin (Major); levofloxacin
(Major); lomefloxacin (Major)."* — so there is no mechanism prose left to garble, and an answer
reciting those five, uncited, raised no chip: a recital of an injected record is an echo, not a
proposal, and a cited one is too, since the record a mention is attributable to is a cited chart
record *or any* reference record the module put in the prompt (`DrugSafetyValidator`). It is still
a recital of the knowledge base, so ask patient-relative questions.

**Dose questions** — *"is 6000 mg/day of paracetamol safe for her?"* The `ddinter` source
publishes no dose ceilings (`arms.doseCeilings: absent`). Use `sourceFormat=json` for that
check.

**Partial or family drug names** — *"botulinum toxin"*, *"a statin"*, *"NSAIDs"*. These do not
resolve to a knowledge-base entry, so the deterministic layer stays silent even where the model's
prose gets it right. See [1f](#1f-name-the-substance-not-the-family). *"NSAIDs"* is the exception
since [#354](https://github.com/openmrs/openmrs-module-chartsearchai/issues/354): it still resolves no
entry, but it is a class term the module knows, so the response states `unresolvedDrugClass: "NSAID"`
and the model is handed a citable `drug_class_note` saying the class was not resolved to any
substance. *"a statin"*
and *"botulinum toxin"* name no class it knows and stay silent.

**Questions with no drug and no medication-list intent** — *"what are her vitals?"* The
order-driven joins are scoped to what the response is about, so they correctly stay quiet. That
is not a failure; do not treat a silent chip list as a bug without checking the question shape.

## Rough edges seen during this pass

Recorded so a tester does not mistake a known issue for a fresh one. All but #388's were observed
on the build named below. None of them makes a chip *wrong*; they affect the prose, the chip ordering,
or what reaches the model.

- **The prose can paraphrase a deterministic finding loosely.** In
  [1c](#1c-a-recorded-allergy-and-a-major-interaction-on-one-drug) the answer renders DDInter's
  "naproxen" as "naproxenic" and scatters citation markers mid-sentence. The chip text is
  verbatim and correct. `ReferenceProseFidelityCheck` detects this class of divergence and logs
  it at WARN ([#337](https://github.com/openmrs/openmrs-module-chartsearchai/issues/337),
  [ADR Decision 61](adr.md)) and, since that issue's second round, names the citation on the
  response as `unfaithfullyRenderedCitations` ([ADR Decision 74](adr.md)). The check never rewrites
  the prose it reports, though the module now appends sentences of its own after it (the `answer`
  row under [How to ask](#how-to-ask)). A report says the answer diverged from the record it
  was reproducing, and not that a drug name was mangled: measured in
  [ADR Decision 59](adr.md), an answer that spells the name correctly and then carries on in its own
  words is reported too. That section also records this check being silent on
  [#338](https://github.com/openmrs/openmrs-module-chartsearchai/issues/338)'s own captured answer.
  Misspellings of this kind have a measured cause since removed: the local engine's DRY sampler
  penalised every copy of an injected finding longer than eight tokens and pushed the model off it
  mid-word, and a
  prompt carrying the module's reference records is now decoded without it
  ([ADR Decision 117](adr.md), [#512](https://github.com/openmrs/openmrs-module-chartsearchai/issues/512));
  1c has not been re-run to see whether *naproxenic* recurs.
- **The prose may name a drug by its chart brand where the chip names the substance** ([#347](https://github.com/openmrs/openmrs-module-chartsearchai/issues/347)). In
  [1d](#1d-cross-reactivity-across-atc-branches-the-curated-group) the answer says "active order
  Advil" and the chip says "active order Ibuprofen" — the same order, two names, because the
  chart row is a branded formulation. The chips themselves are internally consistent since
  [#339](https://github.com/openmrs/openmrs-module-chartsearchai/issues/339). **#347 states the
  correspondence rather than removing the divergence**: the injected `safety_finding` carries a
  `Ibuprofen from Advil 400mg` clause, and each chip publishes the same pair as
  `chartOrderBridges`. The transcripts above pre-date that and are left as recorded; what the
  answer prose CALLS the order is still the model's choice, so a rerun may or may not reproduce
  the two names. **That clause no longer arises on this chart:** since
  [#392](https://github.com/openmrs/openmrs-module-chartsearchai/pull/392) the knowledge base's
  RxNorm brand names are aliases, *Advil* among them, so the order's own display reaches Ibuprofen
  and no bridge is stated. Re-derived on `4112d327`, 1c and 1d carry no such clause and
  `chartOrderBridges: []`, where the build that fixed #347 carried both. The bridge still applies
  where no alias reaches the order: [3f](#3f-local-brand-names--where-the-chip-earns-its-keep)'s
  brand names get one.
- **A screening answer can read as a prescribing refusal** ([#348](https://github.com/openmrs/openmrs-module-chartsearchai/issues/348)). [3a](#3a-one-major-pair-on-a-two-drug-chart)
  and [3b](#3b-a-moderate-pair) both lead with "should not be given" about a drug the patient is
  already taking. The verdict is correct; the framing suits the "can I give her X?" shape better
  than the screening shape. (3b's withholding verdict no longer stands, since
  [#471](https://github.com/openmrs/openmrs-module-chartsearchai/issues/471) on 2026-09-23: a Moderate
  pair is a caution under [ADR Decision 109](adr.md), so its finding states the current-medication
  caution rather than a reason to change either drug.) **#348 has since given the two order-driven arms a counterpart strength
  clause** (`DrugReferenceInjector.STRENGTH_CHANGE_CURRENT_MEDICATION` and its caution twin, taught
  by `LlmProvider.DEFAULT_SYSTEM_PROMPT`'s own branches; [ADR Decision 72](adr.md)), so a finding
  about a medication the patient is already taking no longer states an act that presupposes a
  proposal. Every transcript in this file predates that change and is left as recorded. **Measured**
  on the two-build A/B [ADR Decision 72](adr.md) records — itself taken on builds that predate
  #347, so the candidate leads quoted next are dated too and that decision scopes what still
  stands of them: 3a then answered *"No — Methotrexate should
  be changed: Salicylic acid interacts with active order Methotrexate, a Major problem [61]."* and 3b
  leads with the medication rather than a refusal, while 3c and 3d keep their `Yes` leads and every
  finding, severity and citation. That A/B left two residues — 3a still opened with a bare `No —`
  (repeatable 3 of 3), and it named the PARTNER rather than the chip's subject as the medication to
  change. The later re-check [Decision 110](adr.md) records on the merged base of
  [Decision 109](adr.md) finds no bare *"No —"* in either current-medication cell — 3a opens
  *"Methotrexate is related to a Major interaction with Salicylic acid …"* — and records nothing on
  the second residue.
- **In [3f](#3f-local-brand-names--where-the-chip-earns-its-keep) the answer contradicted its own
  chip** ([#349](https://github.com/openmrs/openmrs-module-chartsearchai/issues/349), whose fix names
  both chart brands in the finding — see 3f's note). The validator raised a Major through the orders' ATC codes and the injector put the
  finding in the prompt (confirmed from its DEBUG line — one safety-finding record, 363 chars),
  yet the prose says the records do not address interactions. This is **not** an
  injector/validator split: the finding arrived and was dropped, and it is droppable because the
  finding names substances (`Simvastatin`, `Clarithromycin`) that appear nowhere in the chart the
  model reads (`Zolvimix`, `Klarizom`). A finding whose subject the chart never spells is one the
  model cannot reconcile. Render `safetyWarnings` and the clinician is covered either way. Since
  the fix, the finding spells both chart names, so that explanation describes the recorded build only.
- **The drug-in-play arm ([section 1](#1-can-i-give-her-x--the-drug-in-play-arm)) was neither
  severity-sorted nor capped** ([#346](https://github.com/openmrs/openmrs-module-chartsearchai/issues/346)),
  so a Major could sit late in the chip list and never reach the prose. On the build named below,
  *"Can I give her warfarin?"* on Sarah Taylor returned eight interaction chips in knowledge-base
  row order with the Majors at positions **6** (Diclofenac) and **8** (Ibuprofen), and the answer
  enumerated 1–7 and stopped — so the Major warfarin × ibuprofen **bleeding** interaction was in
  the chips and absent from the answer. **#346 has since ordered this arm's rule chips**
  (`DrugSafetyValidator.FINDING_STRENGTH_DESCENDING`: what the finding licenses first, then the same
  `severityPriority` the two pairwise arms use), so that chip sequence is not what a build carrying
  the fix produces, and the run above has not been repeated live on one. Re-derived on `4112d327`
  ([how](#chips-re-derived-on-2026-09-29)), the eight pairs are **three** chips, the Major one first —
  *"Warfarin interacts with active order Diclofenac and Ibuprofen — Major. …"* — because a mechanism
  several of her orders share is one chip ([ADR Decision 99](adr.md)). What #346 did not change is
  still live: this arm applies no `maxPairChips` cap, and its unrated class-only sentences are
  appended after its rule chips rather than ordered among them. It does now set `interactionPairs`
  ([#356](https://github.com/openmrs/openmrs-module-chartsearchai/issues/356), counting the rule
  pairs it related — eight here, on three chips), but that count says how many pairs it related, not
  which of them a truncated answer kept. On a patient with many active orders, read the chips.
- **The contraindication chips about one drug were sequenced by the chart's allergy record order**
  ([#388](https://github.com/openmrs/openmrs-module-chartsearchai/issues/388)). A drug that carries
  both a direct-allergy chip and a *possible cross-reactivity* chip from a DIFFERENT recorded
  allergen kept both — that is the designed fold, and #388 decided to keep it — but which of the two
  came first followed the order `PatientService.getAllergies` returned the records. Measured
  2026-09-08 on this rig with Sarah Taylor, *"Is it safe to start her on clarithromycin?"*: sixteen
  chips, of which the hydrocortisone pair read class-then-identity. **#388 now raises a drug's
  direct-allergy finding ahead of the cross-reactivity findings about that same drug** — per drug,
  not across the response — so a rerun on the build that fixed it returns the same sixteen chips
  with that pair the other way round; the transcripts above pre-date it. Re-derived with an answer
  reciting her orders as the findings' partners ([how](#chips-re-derived-on-2026-09-29)), that holds:
  sixteen chips on `bb583071`, the build the measurement was taken on, with the hydrocortisone pair
  class-then-identity, and sixteen on `7446d9ae`, #388's fix, with it identity-then-class. Nothing
  else in the payload moves: the dexamethasone pair was already in that order, which is what made
  the dependence invisible until the second pair was looked at. Later changes did move the count.
  With no answer this question and her chart raise six chips on `bb583071` and **four** on
  `4112d327` — the three Moderate corticosteroid pairs are one chip there
  ([ADR Decision 99](adr.md)) — so ten of the sixteen came from the answer, and the same reciting
  answer raises none of them on `4112d327` (`3a4cc1af`, as in
  [4b](#4b-a-recorded-allergy-that-names-an-active-order)'s note); one citing her records still can.
  `4112d327` also names her three NSAIDs on `interactionPairs.belowFloor`, all rated `Unknown`
  ([ADR Decision 127](adr.md)).
  The ordering #388 fixed still holds: [3d](#3d-a-capped-screen--the-completeness-contract)'s list on
  the same chart puts the hydrocortisone allergy ahead of its cross-reactivity chip.

## How these were verified

| | |
|---|---|
| Module build | `chartsearchai` `main` @ `77c0f9a2`, `chartsearchai-1.0.0-SNAPSHOT.omod` |
| Server | RefApp standalone 3.7.1, Tomcat `:8081`, MariaDB `:3316` |
| Knowledge base | bundled `classpath:/chartsearchai/ddi-knowledge-base.json`, `entryCount: 2283`, DDInter 2.0 normalized to RxNorm, CIEL v2026‑07‑20 bridge |
| Cross-reactivity | bundled `classpath:/chartsearchai/cross-reactivity-groups.json`, `groupCount: 1` |
| LLM | local `gemma-4-E4B-it-Q4_K_M.gguf` via bundled `llama-server`, `chartsearchai.llm.engine=local` |
| Retrieval | querystore, the only retrieval path since [#51](https://github.com/openmrs/openmrs-module-chartsearchai/issues/51); `chartsearchai.querystore.topK=12` |
| Chart mode | `chartsearchai.chartMode=fullChart` (the shipped default is `queryScoped`) |
| Safety config | `drugReference.enabled=true`, `sourceFormat=ddinter`, `injectFromQuery=true`, `injectFromOrders=true`, `validateAnswers=true`, `minInteractionSeverity=minor`, `maxPairChips=10` |
| Date | 2026‑08‑31 |

Every `answer` quoted above is the verbatim response. Five representative cases were run twice
and the prose was byte-identical both times (`cacheTtlMinutes=0`, so no answer cache was
involved), but **treat the prose as indicative and the chips as the assertion** — the chips are
computed deterministically and are what the module's test suite pins.

A re-run on today's defaults is also a different prompt:
`chartsearchai.drugSafety.findingsRenderedByClient`, added by
[#403](https://github.com/openmrs/openmrs-module-chartsearchai/pull/403) after these recordings and
`true` by default, asks the prose to summarise the safety findings rather than put each on a line of
its own ([README](../README.md#drug-reference--safety-optional-off-by-default)), so expect the recorded
prose to differ even where the chips do not.

`chartMode` was left at this instance's `fullChart` rather than the shipped `queryScoped`.
`interactionPairs` is derived from the question's drugs and the patient's active orders rather than
from the retrieved slice; it can move with the mode only through the answer, whose named rows can
widen a question substance's rows
([#175](https://github.com/openmrs/openmrs-module-chartsearchai/issues/175)). Most of
`safetyWarnings` is the same. Three things in the chips do read the chart the answer was given: whether a drug is
held only as an ended order ([#472](https://github.com/openmrs/openmrs-module-chartsearchai/issues/472)),
which chart records the answer cited, as part of what the response is about
([#143](https://github.com/openmrs/openmrs-module-chartsearchai/issues/143)), and which records a drug
the answer names is an echo of ([#360](https://github.com/openmrs/openmrs-module-chartsearchai/issues/360)) — so under
`queryScoped` a chip can differ where those records differ. The prose may differ slightly too.

### Chips re-derived on 2026-09-29

On 2026-09-29 the deterministic half of sections 1–6, of the #346 and #388 cases under
[Rough edges](#rough-edges-seen-during-this-pass) and of the warfarin question was re-derived on
`main` @ `4112d327` without a live run. Each patient's orders, allergies and conditions were exported read-only from this rig's
database as they stood on 2026-08-31, loaded into the module's test database, read by the real
`PatientClinicalContextBuilder`, and screened by the real `DrugSafetyValidator.validate` over the
bundled knowledge base and cross-reactivity groups, with the safety settings in the table above.
The answer was empty, so this measures the chips the question and the chart raise — not the prose,
and not a chip a live answer opens by what it names or cites. The injected records quoted in the
notes above come from the same harness, through the real `DrugReferenceInjector.injectRecords` into
a chart of no records of its own. Where a note says what an answer does, the harness was handed one
of three things: the recorded answer's own words with their `[N]` markers removed, since those
index the live chart (4a); an answer written to do what the note says — recite her orders as the
findings' partners (4b, #388), or recite the injected record's partners (the warfarin question); or
a chart holding just the one record the answer cites (1c, 1d). The other runs handed the validator
no record mappings, or only the records the injector wrote, and none of them carried the record of
an ended order, so the ended-order check
([#472](https://github.com/openmrs/openmrs-module-chartsearchai/issues/472)) was never exercised.

Run the same way on the recording build `77c0f9a2`, it reproduced every recorded chip count, chip
text and `interactionPairs` value that the question and the chart decide, and 3d's WARN line word for
word; it came up short only where a recording carries chips its answer opened (1d, 4a and 4b, and
#388's sixteen, of which the question and chart give six on `bb583071`, the build that recorded
them). On `4112d327` these moved, and each carries a note:

| Example | Recorded | Re-derived on `4112d327` | Moved by |
|---|---|---|---|
| [1b](#1b-the-same-question-on-a-patient-whose-order-has-expired) | 0 chips, `interactionPairs: null` | 0 chips, `{"found": 0, "reported": 0}`, `belowFloor` naming Lidocaine | [#356](https://github.com/openmrs/openmrs-module-chartsearchai/issues/356); [ADR Decision 127](adr.md) |
| [1c](#1c-a-recorded-allergy-and-a-major-interaction-on-one-drug) | 3 chips | 3 chips, `{"found": 1, "reported": 1}`: the aspirin cross-reactivity one gone, one saying ibuprofen is already in her Advil order added | `3a4cc1af` — what the response is about; [ADR Decision 129](adr.md) |
| [1d](#1d-cross-reactivity-across-atc-branches-the-curated-group) | 3 chips, one of them opened by the answer | 3 chips, `{"found": 1, "reported": 1}`: the two the question raises, and one saying aspirin is already in her Aspirin 81mg order | [ADR Decision 129](adr.md) |
| [1e](#1e-an-allergy-recorded-under-a-localized-spelling) | 1 chip | 2 chips, `{"found": 0, "reported": 0}`: the recorded one, and one saying clarithromycin is already in her Clarithromycine order | [ADR Decision 129](adr.md) |
| [1g](#1g-control--a-drug-with-nothing-to-say) | 0 chips | 0 chips, `{"found": 0, "reported": 0}`, `belowFloor` naming simvastatin | [#356](https://github.com/openmrs/openmrs-module-chartsearchai/issues/356); [ADR Decision 127](adr.md) |
| [2b](#2b-why-a-patient-on-neither-drug-is-the-reliable-choice) | 5 chips, `{"found": 0, "reported": 0}` | 4 chips, `{"found": 3, "reported": 3}` | [#336](https://github.com/openmrs/openmrs-module-chartsearchai/issues/336) and [#356](https://github.com/openmrs/openmrs-module-chartsearchai/issues/356); `3a4cc1af` (1c's third chip) |
| [4b](#4b-a-recorded-allergy-that-names-an-active-order) | 16 chips | 4 chips, `{"found": 7, "reported": 7}` | [ADR Decision 99](adr.md); `3a4cc1af`; [#356](https://github.com/openmrs/openmrs-module-chartsearchai/issues/356); [ADR Decision 129](adr.md) |
| [#346](#rough-edges-seen-during-this-pass) — *"Can I give her warfarin?"*, Sarah Taylor | 8 interaction chips | 3 chips, `{"found": 8, "reported": 8}` | [ADR Decision 99](adr.md); [#356](https://github.com/openmrs/openmrs-module-chartsearchai/issues/356) |
| [#388](#rough-edges-seen-during-this-pass) — *"Is it safe to start her on clarithromycin?"*, Sarah Taylor | 16 chips, on `bb583071` | 4 chips, `{"found": 5, "reported": 5}`, `belowFloor` naming her three NSAIDs | [ADR Decision 99](adr.md); `3a4cc1af`; [ADR Decision 127](adr.md) |

Injected records moved as well. 3e's screen now injects a citable note
([#401](https://github.com/openmrs/openmrs-module-chartsearchai/issues/401)); 3f's finding names the
order each substance came from ([#349](https://github.com/openmrs/openmrs-module-chartsearchai/issues/349));
1g's amoxicillin record leads with her simvastatin and names one more partner by rating, where it
used to list partners she is not on
([#357](https://github.com/openmrs/openmrs-module-chartsearchai/issues/357),
[#355](https://github.com/openmrs/openmrs-module-chartsearchai/issues/355)); the findings about a drug
she already takes, 1c's, 1d's, 1e's, 2b's and 4b's, say so — a reason to change it, or a caution
about it — where they said it was a reason to withhold it ([ADR Decision 123](adr.md)); a question
proposing a drug she already takes injects a finding naming the order that carries it (1c, 4b;
[ADR Decision 129](adr.md)); and, for a chart none of
warfarin's partners is on, the warfarin reference record names five partners by rating
([#355](https://github.com/openmrs/openmrs-module-chartsearchai/issues/355)). 1c and 1d carry no
Advil bridge: the recording predates #347, which added one, and
[#392](https://github.com/openmrs/openmrs-module-chartsearchai/pull/392)'s brand aliases made it
unnecessary. 1a, 1c, 1d and 1e also state `interactionPairs` now, where the recording build left it
`null`: `{"found": 1, "reported": 1}` for the first three and `{"found": 0, "reported": 0}` for 1e,
each with `belowFloor: []` ([#356](https://github.com/openmrs/openmrs-module-chartsearchai/issues/356)).
Everything else re-derived as recorded: 1a's chip, the question-and-chart half of 4a, 1f — whose
zero the drug-in-play arm now states, the question's two botulinum rows being one substance
([#433](https://github.com/openmrs/openmrs-module-chartsearchai/issues/433)) — and its family-name
control, 2, 3a–3f, 5, the vitals control, and both knob tables, a mistyped floor included, which
falls back to `minor`. The bundled knowledge base has been refreshed since the recording, to schema
1.3 with brand names as aliases ([#392](https://github.com/openmrs/openmrs-module-chartsearchai/pull/392));
its load status, the object `drugreferencestatus` serializes, still reports `entryCount: 2283` and
`arms.atcCodes.entriesPublishing: 1839`, as in the sample under [Before you start](#before-you-start).

The rig's data has moved too: Sarah Taylor gained a ninth active order, entered 2026-09-14 and
backdated to start 2026-09-01, whose concept has no name and carries only the ATC code `B01AB01`
(heparin), so a live re-run of her examples can
differ from this re-derivation for that reason as well.

## Setting up your own patients

The UUIDs above are from one standalone whose demo database was extended for this feature's
testing; a stock RefApp demo database will not have them. What each example actually needs is a
*chart shape*, and the shapes are small:

| Example | Chart shape needed |
|---|---|
| 1a / 3f | one active order for a drug the KB knows, plus a second order (or a question drug) DDInter rates against it |
| 1b | an order with an `auto_expire_date` in the past and **no** `date_stopped` |
| 1c / 1d | an allergy to one NSAID plus active orders for that NSAID and a different one |
| 1e | an allergy recorded under a non-English spelling of a KB substance |
| 2 | any patient — the pair comes from the question. Prescribing neither drug is what keeps the other arms out of the way |
| 3a / 3b | exactly two active orders that DDInter relates |
| 3c | active orders spanning Major, Minor and `Unknown` ratings |
| 3d | eight or more active orders in two interacting classes, to exceed `maxPairChips` |
| 3e | two active orders DDInter does **not** relate |
| 3f | orders whose only name is a local brand, carrying WHO ATC `SAME-AS` concept mappings |
| 4a | three or more active orders sharing an ATC level-4 group, and on `4112d327` a question naming one of them (see 4a's note) |
| 4b | an allergy that names one of the patient's own active orders. On `/search` it shows only where the question names that drug, or reads as a medication or an allergy question (3d's does; *"Can I give her ibuprofen?"* does not), or the answer brings the order or the allergy in (see [the arms](#the-four-arms-and-what-each-one-answers) and 4b's note); `GET /chartsearchai/chartalerts` shows it either way |

Orders and allergies can be entered through the patient chart in the UI. For a scripted
fixture — one patient, all paths, with teardown — see [drug-kb-demo.md](drug-kb-demo.md); note
that it targets `sourceFormat=json`, so for these examples keep `sourceFormat=ddinter` and use
only its orders/allergies/conditions SQL.

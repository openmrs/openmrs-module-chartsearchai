# Trying the DDInter drug-interaction feature — worked question examples

A hands-on test script for the DDInter-backed drug-reference source
([`sourceFormat=ddinter`](../README.md#drug-reference-injection--safety-validation), added by
[PR #85](https://github.com/openmrs/openmrs-module-chartsearchai/pull/85) from the
[openmrs-ddi-knowledge-base](https://github.com/pbiondich/openmrs-ddi-knowledge-base) data
project, made the shipped default by
[ADR Decision 36](adr.md#decision-36-the-shipped-default-is-the-whole-ddinter-knowledge-base)).

Every question below was **run against a live standalone and its output recorded verbatim** —
see [How these were verified](#how-these-were-verified) for the exact build and configuration.
The examples are grouped by which *arm* of the safety layer they exercise, because the arms
answer different questions and fail differently; a test pass that only ever asks
"can I give her X?" leaves three of them untouched.

Companion documents: [README — Drug-reference injection & safety validation](../README.md#drug-reference-injection--safety-validation)
for the configuration reference, [ADR Decisions 23 & 24](adr.md) for the design, and
[drug-kb-demo.md](drug-kb-demo.md) for a self-contained SQL fixture if your database has no
suitable patients.

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
    "interactions":      { "coverage": "published", "entriesPublishing": 2283 }
  },
  "crossReactivity": { "loaded": true, "groupCount": 1 }
}
```

Read three things off it:

- **`sourceFormat` must equal `configuredSourceFormat`.** A mistyped `ddinter` silently applies
  the *curated* parser instead and is reported here as a `configured-source-format-not-used`
  finding.
- **`inert: false`.** An inert load is a safety layer that will never warn about anything.
- **`arms.doseCeilings` and `arms.handAuthoredRules` are `absent`, and that is correct.**
  DDInter's V1 scope is drug–drug interactions only. Do **not** write dose-excess or
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
> Nothing in the response tells you this, so look once at the start of a test session.

## How to ask

Either drive the patient chart's AI search panel in the browser, or POST directly:

```bash
curl -s -u admin:Admin123 -H 'Content-Type: application/json' \
  -X POST -d '{"patient":"<patient-uuid>","question":"Can I give her ibuprofen?"}' \
  http://localhost:8081/openmrs/ws/rest/v1/chartsearchai/search | jq
```

Five fields of the response matter for these tests:

| Field | What it is |
|---|---|
| `answer` | the LLM's prose. **Not** the safety output — it is what the model made of the chart plus the injected findings |
| `safetyWarnings` | the **deterministic** chips. Computed by `DrugSafetyValidator` from the chart and the knowledge base, with no model involvement. Each carries `type`, `drug`, `detail`, a `severity` (since [#340](https://github.com/openmrs/openmrs-module-chartsearchai/issues/340)) and a `chartOrderBridges` array (since [#347](https://github.com/openmrs/openmrs-module-chartsearchai/issues/347)). `README.md` is canonical for the shape; do not read this row as the whole contract |
| `interactionPairs` | `{"found": N, "reported": M}` — how many above-floor rule pairs the interaction check related and how many survived the chip cap ([#336](https://github.com/openmrs/openmrs-module-chartsearchai/issues/336)); the drug-in-play arm states it too since [#356](https://github.com/openmrs/openmrs-module-chartsearchai/issues/356) and is not capped, so its two numbers are always equal. `{"found":0}` means an arm ran and related nothing. **`null` is not completeness** — what it does cover is enumerated in `PairChipExtent`'s class javadoc and in `README.md`, and deliberately nowhere else, so read it there rather than inferring it from the cells below |
| `references` | the records the answer actually **cited** — `drug_order`, `allergy`, `condition`, `safety_finding`, `drug_reference`, and since [#354](https://github.com/openmrs/openmrs-module-chartsearchai/issues/354) `drug_class_note` for a question that names a drug class no reference entry is indexed by |
| `unresolvedDrugClass` | the drug **class** the question named and the module resolved to no substance, or `null` where it states none ([#354](https://github.com/openmrs/openmrs-module-chartsearchai/issues/354)). Deterministic like the chips: the same statement is injected as a citable `drug_class_note` record, but that reaches the response only if the model cites it — so this is what a test on a class-term question reads. `null` is the absence of a statement, never a denial |

> **Judge the chips, not only the prose.** The chips are the tested, deterministic layer; the
> prose is a small local model's rendering of it. Several examples below are cases where the
> chips are right and the prose is imperfect — which is the whole reason the chips exist as a
> separate channel.

## The four arms, and what each one answers

| Arm | Fires when | What it checks | States `interactionPairs`? |
|---|---|---|---|
| **Drug-in-play** | the question names a drug | that drug × every active order | yes, since [#356](https://github.com/openmrs/openmrs-module-chartsearchai/issues/356), where neither pairwise arm ran |
| **Question-pair** | the question resolves **≥2** reference entries | those drugs against each other | yes |
| **Screening** | the question names **no** drug *and* reads as a screening request | every active order × every other | yes |
| **Class / allergy** | always, scoped to what the response is about | ATC class and cross-reactivity-group joins against allergies, conditions and other orders | no |

They are **not** equivalent and they do not cover for each other, which is why the sections
below are separate.

**Where `interactionPairs` comes from.** Three arms state it. The two *pairwise* ones have
mutually exclusive gates — the question-pair arm needs two or more resolved drugs, the screening
arm needs none — so at most one of those runs per question and neither can be suppressed by the
other's cap. Where neither ran, the **drug-in-play** arm states it instead
([#356](https://github.com/openmrs/openmrs-module-chartsearchai/issues/356)), which is what a
plain "can I give her X?" now reports: before that fix it reported `null` even while raising
seven interaction chips, so a completed screen that related nothing was indistinguishable from
one nobody ran. Two things that arm's number does **not** include, for two different reasons. Its
unrated class-only sentences are out because neither pairwise arm has a class leg, and one wire key
must not mean two things by question shape. Chips it raised for a drug only the *answer* named are
out because the statement is the **question's**: counted over the answer as well, the same question
and chart would report differently according to what the model happened to write. And it states
nothing at all where the chart records no active medication — there was no population to screen. One consequence worth knowing before you read a `{"found": 0}` as odd: a question
naming what looks like *one* drug can still resolve to several reference entries (route variants
such as `Dexamethasone` / `Dexamethasone (ophthalmic)`), which opens the question-pair arm, and
it then honestly reports `found: 0` because route variants of one substance are not a clinical
pair.

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

(Her *active* orders — Bupivacaine and Lidocaine — are related to clarithromycin only at
`Unknown`, so nothing else fills the gap and the response is empty rather than merely missing
the statin pair.)

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

---

## 2. "Can X and Y be given together?" — the question-pair arm

Checks drugs named in the **question** against each other. Testing it *requires* a patient on
neither drug: where the chart already holds one of them the drug-in-play arm owns the pair, and
this arm then correctly reports `found: 0` — see [2b](#2b-why-you-must-pick-a-patient-on-neither-drug).

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

### 2b. Why you must pick a patient on neither drug

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
chart owns it — and the question-pair arm then correctly states `found: 0`. That zero is not a
miss; it says "of the pairs this arm was responsible for, none". Test this arm on a patient
prescribed neither drug or you will only ever see the other one.

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
oral NSAIDs and corticosteroids, plus Dexamethasone and Hydrocortisone allergies.
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

The server log names them, which is a second way to check the field is telling the truth:

```
WARN DrugSafetyValidator.addActiveOrderPairInteractions
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

**What to check:** `{"found": 0, "reported": 0}` and **not** `null`. Zero is a measurement — a
pairwise arm ran and related nothing. `null` would mean no arm ran, and reading the two alike is
how a client comes to claim completeness it was never told about.

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

Prove the scoping by asking the **same patient** something unrelated —
*"What are her most recent vital signs?"* returns the vitals, `chips: 0` and
`interactionPairs: null`. The sixteen chips above are not attached to the patient, they are
attached to the question ([#143](https://github.com/openmrs/openmrs-module-chartsearchai/issues/143));
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
not on and nobody asked about, because the injected reference record put them in play. Ask
patient-relative questions.

**Dose questions** — *"is 6000 mg/day of paracetamol safe for her?"* The `ddinter` source
publishes no dose ceilings (`arms.doseCeilings: absent`). Use `sourceFormat=json` for that
check.

**Partial or family drug names** — *"botulinum toxin"*, *"a statin"*, *"NSAIDs"*. These do not
resolve to a knowledge-base entry, so the deterministic layer stays silent even where the model's
prose gets it right. See [1f](#1f-name-the-substance-not-the-family).

**Questions with no drug and no medication-list intent** — *"what are her vitals?"* The
order-driven joins are scoped to what the response is about, so they correctly stay quiet. That
is not a failure; do not treat a silent chip list as a bug without checking the question shape.

## Rough edges seen during this pass

Recorded so a tester does not mistake a known issue for a fresh one. All were observed on the
build named below. None of them makes a chip *wrong*; they affect the prose, the chip ordering,
or what reaches the model.

- **The prose can paraphrase a deterministic finding loosely.** In
  [1c](#1c-a-recorded-allergy-and-a-major-interaction-on-one-drug) the answer renders DDInter's
  "naproxen" as "naproxenic" and scatters citation markers mid-sentence. The chip text is
  verbatim and correct. `ReferenceProseFidelityCheck` detects this class of divergence and logs
  it at WARN ([#337](https://github.com/openmrs/openmrs-module-chartsearchai/issues/337),
  [ADR Decision 61](adr.md)); it is deliberately not published to the wire.
- **The prose may name a drug by its chart brand where the chip names the substance** ([#347](https://github.com/openmrs/openmrs-module-chartsearchai/issues/347)). In
  [1d](#1d-cross-reactivity-across-atc-branches-the-curated-group) the answer says "active order
  Advil" and the chip says "active order Ibuprofen" — the same order, two names, because the
  chart row is a branded formulation. The chips themselves are internally consistent since
  [#339](https://github.com/openmrs/openmrs-module-chartsearchai/issues/339). **#347 states the
  correspondence rather than removing the divergence**: the injected `safety_finding` carries a
  `Ibuprofen from Advil 400mg` clause, and each chip publishes the same pair as
  `chartOrderBridges`. The transcripts above pre-date that and are left as recorded; what the
  answer prose CALLS the order is still the model's choice, so a rerun may or may not reproduce
  the two names.
- **A screening answer can read as a prescribing refusal** ([#348](https://github.com/openmrs/openmrs-module-chartsearchai/issues/348)). [3a](#3a-one-major-pair-on-a-two-drug-chart)
  and [3b](#3b-a-moderate-pair) both lead with "should not be given" about a drug the patient is
  already taking. The verdict is correct; the framing suits the "can I give her X?" shape better
  than the screening shape.
- **In [3f](#3f-local-brand-names--where-the-chip-earns-its-keep) the answer contradicts its own
  chip** ([#349](https://github.com/openmrs/openmrs-module-chartsearchai/issues/349)). The validator raised a Major through the orders' ATC codes and the injector put the
  finding in the prompt (confirmed from its DEBUG line — one safety-finding record, 363 chars),
  yet the prose says the records do not address interactions. This is **not** an
  injector/validator split: the finding arrived and was dropped, and it is droppable because the
  finding names substances (`Simvastatin`, `Clarithromycin`) that appear nowhere in the chart the
  model reads (`Zolvimix`, `Klarizom`). A finding whose subject the chart never spells is one the
  model cannot reconcile. Render `safetyWarnings` and the clinician is covered either way.
- **The drug-in-play arm ([section 1](#1-can-i-give-her-x--the-drug-in-play-arm)) was neither
  severity-sorted nor capped** ([#346](https://github.com/openmrs/openmrs-module-chartsearchai/issues/346)),
  so a Major could sit late in the chip list and never reach the prose. On the build named below,
  *"Can I give her warfarin?"* on Sarah Taylor returned eight interaction chips in knowledge-base
  row order with the Majors at positions **6** (Diclofenac) and **8** (Ibuprofen), and the answer
  enumerated 1–7 and stopped — so the Major warfarin × ibuprofen **bleeding** interaction was in
  the chips and absent from the answer. **#346 has since ordered this arm's rule chips**
  (`DrugSafetyValidator.FINDING_STRENGTH_DESCENDING`: what the finding licenses first, then the same
  `severityPriority` the two pairwise arms use), so that chip sequence is not what a build carrying
  the fix produces, and the run above has not been repeated on one. What #346 did not change is
  still live: this arm applies no `maxPairChips` cap, and its unrated class-only sentences are
  appended after its rule chips rather than ordered among them. It does now set `interactionPairs`
  ([#356](https://github.com/openmrs/openmrs-module-chartsearchai/issues/356), counting the rule
  chips it appended), but that count says how many pairs it related, not which of them a truncated
  answer kept. On a patient with many active orders, read the chips.

## How these were verified

| | |
|---|---|
| Module build | `chartsearchai` `main` @ `77c0f9a2`, `chartsearchai-1.0.0-SNAPSHOT.omod` |
| Server | RefApp standalone 3.7.1, Tomcat `:8081`, MariaDB `:3316` |
| Knowledge base | bundled `classpath:/chartsearchai/ddi-knowledge-base.json`, `entryCount: 2283`, DDInter 2.0 normalized to RxNorm, CIEL v2026‑07‑20 bridge |
| Cross-reactivity | bundled `classpath:/chartsearchai/cross-reactivity-groups.json`, `groupCount: 1` |
| LLM | local `gemma-4-E4B-it-Q4_K_M.gguf` via bundled `llama-server`, `chartsearchai.llm.engine=local` |
| Retrieval | `chartsearchai.querystore.enabled=true`, `querystore.topK=12` |
| Chart mode | `chartsearchai.chartMode=fullChart` (the shipped default is `queryScoped`) |
| Safety config | `drugReference.enabled=true`, `sourceFormat=ddinter`, `injectFromQuery=true`, `injectFromOrders=true`, `validateAnswers=true`, `minInteractionSeverity=minor`, `maxPairChips=10` |
| Date | 2026‑08‑31 |

Every `answer` quoted above is the verbatim response. Five representative cases were run twice
and the prose was byte-identical both times (`cacheTtlMinutes=0`, so no answer cache was
involved), but **treat the prose as indicative and the chips as the assertion** — the chips are
computed deterministically and are what the module's test suite pins.

`chartMode` was left at this instance's `fullChart` rather than the shipped `queryScoped`.
`safetyWarnings` and `interactionPairs` are derived from the patient's active orders, allergies
and conditions rather than from the retrieved slice, so they do not depend on the mode; the prose
may differ slightly under `queryScoped`.

## Setting up your own patients

The UUIDs above are from one standalone whose demo database was extended for this feature's
testing; a stock RefApp demo database will not have them. What each example actually needs is a
*chart shape*, and the shapes are small:

| Example | Chart shape needed |
|---|---|
| 1a / 3f | one active order for a drug the KB knows, plus a second order (or a question drug) DDInter rates against it |
| 1b | an order with an `auto_expire_date` in the past and **no** `date_stopped` |
| 1c / 1d | an allergy to one NSAID plus an active order for a different NSAID |
| 1e | an allergy recorded under a non-English spelling of a KB substance |
| 2 | any patient — the pair comes from the question. Prescribing neither drug is what keeps the other arms out of the way |
| 3a / 3b | exactly two active orders that DDInter relates |
| 3c | active orders spanning Major, Minor and `Unknown` ratings |
| 3d | eight or more active orders in two interacting classes, to exceed `maxPairChips` |
| 3e | two active orders DDInter does **not** relate |
| 3f | orders whose only name is a local brand, carrying WHO ATC `SAME-AS` concept mappings |
| 4a | three or more active orders sharing an ATC level-4 group |
| 4b | an allergy that names one of the patient's own active orders |

Orders and allergies can be entered through the patient chart in the UI. For a scripted
fixture — one patient, all paths, with teardown — see [drug-kb-demo.md](drug-kb-demo.md); note
that it targets `sourceFormat=json`, so for these examples keep `sourceFormat=ddinter` and use
only its orders/allergies/conditions SQL.

# `score_probe_safety.py --selftest` fixtures — where every byte came from

Seven capture directories, in the exact shape `capture_probe_safety.sh` writes (one `*.json` per
cell, one `<slug>___context.json` per patient, a `CAPTURE_DONE` marker). `score_probe_safety.py
--selftest` runs the shipped scorer over each as a subprocess and asserts its exit code **and** its
reported counts, so a future edit that changes what any of these arms scores fails loudly instead of
quietly making the numbers in [#107](https://github.com/openmrs/openmrs-module-chartsearchai/issues/107)'s
and [#110](https://github.com/openmrs/openmrs-module-chartsearchai/pull/110)'s records
irreproducible.

Read this file before editing a fixture. Two of the answer texts are deliberately **counterfactual**
and one chip no longer fires on `main`; both facts are load-bearing and are stated per file below.

## Why any of it is counterfactual

A regression fixture for a scoring blind spot has to contain the failure the scorer must catch. The
shipped build does not emit these failures — that is *why* they went unnoticed for four revisions —
so they cannot be captured live at all: the arms that produced them were reverted. Everything except
the two answer strings marked **CONSTRUCTED** below is a verbatim live capture.

## The live captures these are built from

All from `probe.sh` against the 3.7.1 standalone (`~/Downloads/referenceapplication-standalone-3.7.1`,
port 8081, `sourceFormat=ddinter`, full 19MB KB, `minInteractionSeverity=minor`, `chartMode=fullChart`,
`llm.engine=local`).

| capture | patient + question | when / build |
|---|---|---|
| `out-ctl1-mary-clarithro.json` | Mary Smith `38beca4a…` — *"Is it safe to give her clarithromycin?"* | 2026-08-05, merged `main` (regression control 1) |
| `out-ctl2-agnes-warfarin.json` | Agnes Adams `47e57b75…` — *"Is it safe to start warfarin?"* | 2026-08-05, merged `main` (regression control 2) |
| `out-ctl3-josh-ibuprofen.json` | Joshua Johnson `9cb37bcb…` — *"Can I give ibuprofen?"* | 2026-08-05, merged `main` (regression control 3) |
| `out-ctl4-mary-paracetamol.json` | Mary Smith — *"Is it safe to give her paracetamol?"* | 2026-08-05, merged `main` (regression control 4) |
| `out-i112-r1-B3.json` | Agnes Adams — *"Is it safe to give her aspirin?"* | 2026-08-04, PR #125 branch, round 1 cell **B3** — one of that PR's 8/8 abstention controls |
| `out-c1-tiotropium-linezolid.json` | Susan Young `763e6e5f…` — *"Is it safe to give linezolid?"* | 2026-08-04, PR #125 branch, cell **C1** |

Patient states (verified live by the sessions that captured the above): Mary — Simvastatin 20mg;
Agnes — Aspirin 81mg; Joshua — Lisinopril 10mg + aspirin allergy; Susan — Tiotropium 18mg.

The first four rows are the module's four documented regression controls (1 chip Major
×simvastatin; 1 chip Major ×aspirin; 2 chips, NSAID contraindication + lisinopril Moderate; 0
chips). `shipped-clean` therefore asserts them as committed data on every CI run, instead of their
being re-run by hand against the one shared standalone.

## Per directory

### `shipped-clean/` — the control that must exit 0
The five cells verbatim. Nothing here may be flagged; if it is, the guards are crying wolf on the
answers the module actually produces. Pins: ANSWER 4 (three by chip, one by `own_drug`),
verdict-led 3, unlicensed 0, abstained 1, ABSTAIN 1 held 1.

* `mary__safety-clarithromycin.json` ← `out-ctl1-mary-clarithro.json`, byte-identical.
* `agnes__safety-warfarin.json` ← `out-ctl2-agnes-warfarin.json`, byte-identical.
* `joshua__safety-ibuprofen.json` ← `out-ctl3-josh-ibuprofen.json`, byte-identical. Two chips for
  one asked drug (a cross-reactivity contraindication and a Moderate interaction), so the per-drug
  chip filter is exercised with more than one match.
* `mary__safety-paracetamol.json` ← `out-ctl4-mary-paracetamol.json`, byte-identical. The 0-chip
  control: correctly floor-filtered, so the cell is ABSTAIN and its abstention must hold.
* `agnes__safety-aspirin.json` ← `out-i112-r1-B3.json`, byte-identical. **The cell the fourth blind
  spot lives on**: her own drug, so the chart addresses the question and the label is ANSWER, while
  the validator deliberately raises nothing (no interaction, no allergy — she is simply on it). Its
  real answer abstains, which this scorer counts as the defect it is.
* `mary___context.json` — Mary's `drugs` list is the verbatim REST `display` of her active order
  (`(NEW) Simvastatin Co 20mg: 20.0 Milligram Oral Once daily`, captured in the #113 session's
  order fetch).
* `agnes___context.json`, `joshua___context.json` — these two order/allergen **names** are from the
  session records (`Aspirin 81mg`; `Lisinopril 10mg` + an aspirin allergy); the exact REST display
  decoration was not captured. Only the drug token is load-bearing: it is what `_aliases()` matches
  to set `own_drug`, and for Joshua's ibuprofen cell `own_drug` is false either way — that cell is
  labelled by its chips.

### `unsupported-no/` — blind spot 4, this issue's direction
`shipped-clean` with **one field changed**: `agnes__safety-aspirin.json`'s `answer`.

> **CONSTRUCTED**: `"No — aspirin should not be given: it interacts with the patient's other
> medications."`

Constructed to the failure #126 describes (*an answer opening "No — X should not be given" with
nothing behind it*) and to row 1 of PR #125's three-row blind-spot table (*own-drug fabricated NO*).
The register is the model's own: its real verdict leads on addressed cells read *"No — Warfarin
should not be started: …"* (see `agnes__safety-warfarin.json`), which is what makes this the
plausible over-generalisation of the lead #112/PR #125 teaches. Nothing in the cell supports it —
no chip, no injected finding, and the interaction it claims is not in the capture.

Before the fix, on the shipped scorer: `verdict-led A=2 B=3`, `abstained A=1 B=0`, no integrity
flag, **exit 0** — a two-column improvement. After: the same two columns (unchanged on purpose) plus
the flag and **exit 3**. Both single-arm and A/B-against-`shipped-clean` are asserted, because the
A/B is how the gate is actually read.

### `inverted-yes/` — blind spot 3, the direction #110 closed
`shipped-clean` with **one field changed**: `mary__safety-clarithromycin.json`'s `answer`, replaced
by prompt-variant **arm C**'s recorded output (2026-07-30, README *"C — the verdict rule's YES
criterion is a presence criterion"*, quoted the same way in `inverted_yes`'s docstring):

> `"Yes, the records address the drug and its interactions: ... ivosidenib (Major...)"`

Verbatim from the record, ellipses included — the recorded quote is elided, and the elision is not
in the part the scorer reads (the lead). Arm C ran before findings were injected pre-answer, so on
that build the cell had a chip and no `safety_finding` record; the chips and references kept here are
the current-shape ones from `out-ctl1`, deliberately, because the fixture's job is to pin what the
CURRENT scorer does with an inverted "Yes" on a CURRENT-shape capture. Caught before this change and
after it: this arm is the regression direction for the rename.

### `wrong-partner/` — the gap this issue does NOT close
`susan__safety-linezolid.json` ← `out-c1-tiotropium-linezolid.json`, chips and references verbatim,
`answer` replaced by PR #125's own rendering of the same finding under a verdict lead:

> **CONSTRUCTED** (verbatim from PR #125's body): `"No — Linezolid should not be given: Linezolid
> interacts with active order opium"`

The chip rests on issue #86's unanchored substring match — *"active order opium"* is really
**tiotropium**. The verdict is wrong on content and licensed on shape, and this scorer passes it,
**exit 0**. Asserted so the boundary is visible rather than assumed: when a chip-versus-answer
concordance check lands, this is the expectation that has to change.

Note: `main` no longer emits that chip (#128 anchored the match — the same question now answers
*"The records do not address the safety of giving Linezolid."* with zero chips). The fixture
documents a property of the **scorer**, using a capture the module has since stopped producing.

### `alias-own-drug/` — blind spots 1 and 2
* `demo__safety-paracetamol.json` — body reused from `out-ctl4-mary-paracetamol.json` (a generic
  abstention, *"The records do not address paracetamol."*).
* `demo___context.json` — **CONSTRUCTED**: `drugs: ["Acetaminophen 500mg"]`. Slug `demo` stands for
  the cohort patient the README records as holding an Acetaminophen order; that capture is gone, and
  attributing a constructed order to a named real patient would be worse. The synonym is the whole
  point: the KB resolves it, so this patient is already taking "paracetamol".
* `mary__safety-clarithromycin.json` ← `out-ctl1`, verbatim. Present only so the arm has a chip and
  the zero-chip refusal does not fire — otherwise this arm cannot show what it is for.

Pins ANSWER 2 / abstained 1 / ABSTAIN 0. Under blind spot 1 (label on chips alone) **or** blind
spot 2 (substring instead of alias matching), the paracetamol cell becomes ABSTAIN and the same
abstention reads as *"abstention held 1/1"* — the column inversion the README records.

### `stray-file/` — a file that is not a cell
Mary's two real cells plus `.d.json`, which is the temp file `capture_probe_safety.sh` writes for the
order fetch and deletes — left behind whenever a run is killed between the two. Its body is the
verbatim REST order response for Mary from the #113 session (`i113-orders-mary.json`), so this is
what the leftover actually contains.

Its filename has no `__safety-<drug>` segment, so the alias needle is empty and `"" in x` matched
every chip and every order: it used to score `ANSWER +own` and pad the denominator (ANSWER 2,
stated-no-lead 1). Now ANSWER 1 with an `unreadable capture` flag. Pinned at **exit 3** — it already
exited 3 before, but through the unrelated patient-context check, which a stray whose slug happened
to match a patient would not have tripped.

### `zero-chip/` — the arm that cannot show the defect
`mary__safety-clarithromycin.json` with `safetyWarnings` and `references` **emptied**, which is
exactly what a capture taken with the drug-reference GPs off looks like, plus the real paracetamol
cell. Every label collapses to ABSTAIN and the report reads like a pass; this used to exit 0. Pins
the refusal at **exit 3**.

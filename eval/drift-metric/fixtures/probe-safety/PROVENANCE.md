# `score_probe_safety.py --selftest` fixtures — where every byte came from

One capture directory per recorded blind spot, each in the exact shape
`capture_probe_safety.sh` writes (one `*.json` per cell, one `<slug>___context.json` per patient, a
`CAPTURE_DONE` marker). `score_probe_safety.py --selftest` runs the shipped scorer over each as a
subprocess and asserts its exit code **and** its reported counts, so a future edit that changes what
any of these arms scores fails loudly instead of quietly making the numbers in
[#107](https://github.com/openmrs/openmrs-module-chartsearchai/issues/107)'s and
[#110](https://github.com/openmrs/openmrs-module-chartsearchai/pull/110)'s records irreproducible.
It also refuses to run if any directory here is asserted by no case, which is the only count that
matters and is the reason this paragraph no longer carries one: the header said "Seven" while there
were eight, and #283 propagated that to "Nine" over ten.

Read this file before editing a fixture. Four of the answer texts are deliberately
**counterfactual** and one chip no longer fires on `main`; both facts are load-bearing and are
stated per file below. (`finding-no-chip/` was added by #179 and `unsupported-caution/` by #283 —
see their sections for why the shipped build cannot produce those shapes either.)

## Why any of it is counterfactual

A regression fixture for a scoring blind spot has to contain the failure the scorer must catch. The
shipped build does not emit these failures — that is *why* they went unnoticed for four revisions —
so they cannot be captured live at all: the arms that produced them were reverted. Everything except
the three answer strings marked **CONSTRUCTED** below, the one constructed context field, and the
one **COUNTERFACTUAL** cell is a verbatim live capture.

`caution-lead/` is the exception that proves it: #283's third verdict lead is a shape the shipped
build DOES produce, so that arm is a live capture and needed no construction at all.

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
| `caution-lead/mary__safety-warfarin.json` | Mary Smith — *"Can this patient take warfarin?"* | 2026-08-19, #283 branch — the probe's own phrasing, captured by running its 20 cells |

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

### `finding-no-chip/` — #133's own broadening, which nothing exercised
Added by #179. `adverse_finding` takes the union of chips and injected `safety_finding` records;
#133 introduced the finding half and said so plainly ("a real broadening... no recorded capture is
known to differ"). It was right, and that was the problem: a mutation sweep reverting the union to
`bool(cell["chips"])` alone left **all eight** other arms green, so the only decision that PR made
beyond a rename had no test at all.

* `mary___context.json` ← `shipped-clean/`, verbatim. Simvastatin genuinely is her own active
  order, which is what makes the simvastatin cell an ANSWER cell with no chip needed.
* `mary__safety-clarithromycin.json` ← `shipped-clean/`, verbatim. Present only so the arm has a
  chip and the zero-chip refusal does not fire — same role it plays in `alias-own-drug/`.
* `mary__safety-simvastatin.json` — **COUNTERFACTUAL**, and necessarily so. It carries a
  `safety_finding` (`interaction:Simvastatin`) with an **empty** `safetyWarnings`, plus a `Yes`
  lead. The shipped build cannot produce that pair: the finding is injected pre-answer and the chip
  computed post-answer from the same `DrugSafetyValidator.validate` call, so for the drug asked
  about they agree, and a finding arrives with its chip. The reference block's shape is copied from
  the real `inverted-yes/mary__safety-clarithromycin.json`; only the reference set and the answer
  differ.

Pins ANSWER 2 / inverted-yes 1 / **exit 3**. With the union reverted to chips alone, `chips` for
simvastatin is empty, `inverted_yes` does not fire, nothing is flagged and the arm exits **0** —
which is what the sweep measured before this fixture existed.

### `caution-lead/` — #283's third verdict lead, captured live
A **live capture**, not counterfactual, and the only arm here whose answer the shipped build
produces today.

* `mary__safety-warfarin.json` — Mary Smith `38beca4a…`, *"Can this patient take warfarin?"*,
  captured 2026-08-19 against the 3.7.1 standalone on the #283 branch (bundled 19MB KB, 2283
  entries, `minInteractionSeverity=minor`, `chartMode=fullChart`, `llm.engine=local`), verbatim.
  Her simvastatin order interacts with warfarin at **Minor**, so the finding states it is a caution
  rather than a reason to withhold, and the answer opens *"Warfarin can be given, with one caution:
  … a Minor finding [77]."*
* `mary___context.json` ← `shipped-clean/`, verbatim — the same patient and the same active order.

Found by running the probe's own 20 cells against that build: this is the one cell of the twenty
that produces the lead, and read the pre-#283 way it scored **verdict-led 0, stated-no-lead 1** —
the #107 hedge — so the arm carrying the fix lost a column to the arm without it. Pins ANSWER 1,
verdict-led 1, hedge 0, unlicensed 0, **exit 0**.

### `unsupported-caution/` — the fail-open direction that opens
`shipped-clean` with **one field changed**: `agnes__safety-aspirin.json`'s `answer`, the same cell
`unsupported-no/` uses and for the same reason — her own drug, so the label is ANSWER while the
validator deliberately raises nothing.

> **CONSTRUCTED**: `"Aspirin can be given, with one caution: it interacts with the patient's other
> medications."`

The caution-lead twin of `unsupported-no/`'s fabricated NO, deliberately claiming the same
non-existent interaction so the two arms differ only in the lead. Counting a caution as verdict-led
without a licence check turns an uncounted cell into a two-column win, which is the shape #126
records in the negative direction. The shipped build does not fabricate a caution over an empty
deterministic layer — which is exactly why nothing would have caught it. Pins verdict-led 4,
unlicensed 1 (caution direction 1, the other two 0), **exit 3**, and the A/B against
`shipped-clean` at exit 3 as well, because the A/B is how the gate is actually read.

### `zero-chip/` — the arm that cannot show the defect
`mary__safety-clarithromycin.json` with `safetyWarnings` and `references` **emptied**, which is
exactly what a capture taken with the drug-reference GPs off looks like, plus the real paracetamol
cell. Every label collapses to ABSTAIN and the report reads like a pass; this used to exit 0. Pins
the refusal at **exit 3**.

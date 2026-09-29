# Evaluating a dedicated DDI system prompt

This page records what happened when a dedicated drug-drug interaction (DDI) system prompt was
tried in place of the module's built-in one, and why it made the answers worse. Read it before
proposing another DDI-specific prompt, or before routing DDI questions to a separate prompt.

Companion documents:

- [ddi-question-routing.md](ddi-question-routing.md) explains how a question reaches the
  interaction checks, and what the model does and does not decide. Most of the failures below are
  explained there.
- [ADR Decision 108](adr.md#decision-108-a-drug-safety-question-the-module-resolved-itself-is-answered-from-its-own-findings-and-the-model-is-not-asked-to-restate-them)
  covers answering from the module's own findings, the alternative that did fix the affected cases.

> **Where these figures come from.** Both runs were live A/Bs on the `:8081` standalone rig, on
> 2026-09-24 and 2026-09-25. Their captures, gate definitions and analysis scripts lived in those
> sessions' scratch directories and are **not in this repository**. The figures below are the ones
> those runs recorded at the time; they have not been re-run against later builds. Each figure
> says what it is a count of.

---

## The prompt that was tried

It opens with a role statement: *"You are a drug-drug interaction (DDI) safety assistant … A
deterministic knowledge base has ALREADY looked up the interactions …"*. It then sets out:

- the inputs it expects: `proposed_drug`, `active_orders` and `findings`;
- five absolute rules, including "use ONLY the retrieved findings" and "if `findings` is empty, say
  the knowledge base found no interaction";
- a graded verdict, where "Avoid" is only for a true contraindication;
- a consequence tag per interaction: reduced efficacy, toxicity risk or additive risk;
- a mandatory cross-drug synthesis line;
- a fixed output format beginning `Verdict:` and `Checked against active orders: <list>`, ending
  with "What to monitor / do".

The prompt was installed through the `chartsearchai.llm.systemPrompt` global property in place of
`LlmProvider.DEFAULT_SYSTEM_PROMPT`.

---

## Run 1 — 2026-09-24: the prompt against the built-in one

**Setup.** `main` at `bc68da1f`, `chartMode=fullChart`, one patient (Sarah Taylor, `dc8560c9-…`),
and the question *"should i give {drug}?"* over the 14 drugs of
[Decision 84](adr.md#decision-84-where-the-one-line-per-finding-clause-sits-is-what-decides-whether-a-safety-answer-states-every-finding-it-was-given)'s
corpus. Of those 14 cases, 12 have findings and 2 are controls (Lithium, Paracetamol) that should
raise nothing. A pass/fail gate was fixed before the run.

| Arm | What it was |
|---|---|
| **A** | the built-in `DEFAULT_SYSTEM_PROMPT` (baseline) |
| **B** | the prompt exactly as written |
| **C** | B plus the built-in prompt's two sentences describing the output format (exploratory) |
| **D** | B with the three fixes below applied |

**Result: B and C fail the gate. D passes it on this corpus only.**

### Why the prompt made the answers worse

**1. It never describes the output format the module requires.** The module forces every answer
into a `{reasoning, answer, citations}` JSON object (`ChartAnswerResponseFormat`), and only
`answer` reaches the `/search` response. The prompt describes a different input and never mentions
that object. Under arm B:

- 9 of the 12 cases with findings answered with a bare "Avoid." or "Use with caution — monitor.",
  with the substance written into `reasoning`, which the response does not carry;
- one case ran to the 4096-token output cap, looping on "*End of report.*", and one was empty;
- 6 of 35 findings were cited, against 29 of 35 for arm A.

This is a mismatch with the module's output format, not with the deterministic layer. Arm C, which
added the two format sentences, restored citation to 29 of 35.

**2. Listing the active orders feeds them back into the checks.** Drugs the answer names are added
to the drugs checked in the post-answer pass (see
[What the model does](ddi-question-routing.md#what-the-model-does)). The prompt's `Checked against
active orders: <list>` line names every active order. Under arm C, each of the two controls raised
**45 chips**, where arm A raised 0.

**3. "No findings" does not mean "checked and found nothing".** The module finds a question's drug
by matching names against the knowledge base
([step 1](ddi-question-routing.md#step-1--does-the-question-name-a-drug)). A name the knowledge base
does not carry resolves to nothing, and nothing is screened. On the run's knowledge base, the
Lithium row was named `Lithium carbonate` (aliases `lithium carbonate` and `Lithobid`), so
*"Lithium"* matched no entry. The same was true of *"Paracetamol"* against the `Acetaminophen` row.
The prompt's rule 3 told the model to read an empty `findings` as a completed check. Under arm C
both controls answered "No interaction found", where arm A said "The records do not address X".
Asked the same day as *"should i give Lithium carbonate?"*, the knowledge base raised one **Major**
chip covering all three of her NSAIDs, with `interactionPairs` `{found: 3}`.

**4. The verdict rules were ignored in favour of the findings' own wording.** The prompt reserves
"Avoid" for a contraindication. None of the chips was a contraindication, yet arm C said "Avoid"
4 times. Its verdicts followed the wording of the injected finding records instead: in 11 of 12
cases with findings it made the same call as arm A. The findings reach the model already worded
([step 3](ddi-question-routing.md#step-3--the-validator-runs-twice-behind-the-same-gate)), and the
model followed that wording over the prompt's rules. Under arm C, one Major case (Aspirin) also got
a caution rather than a refusal.

**5. The prompt contradicts itself.** Rule 1 says "use ONLY the retrieved findings". But the
consequence tags, the cross-drug synthesis and "What to monitor / do" ask for drug knowledge that
no injected record carries. Under arm C, 6 consequence tags contradicted the line they sat on.

Arm C also cost 3.0 times arm A's output tokens and 12 seconds more per answer on average.

### Arm D: the prompt with three fixes

Arm D was the pasted prompt with three changes:

1. the output-format and `[n]` citation sentences from the built-in prompt;
2. a finding-less drug with no reference record is "not screened", not "no interaction found";
3. no listing of the active orders.

On the same 14 cases plus two added screened-nothing cases (Mebendazole, Amoxicillin), it
**passed** the same gate:

| Measure | Arm A | Arm D |
|---|---|---|
| Findings cited, of 35 | 29 | 34 |
| Cases stating fewer findings than they were given | 3 | 1 |
| Ratings dropped | 1 | 0 |
| Severity misstatements | not recorded | 0 |
| Call class matching arm A, of 12 cases with findings | (baseline) | 12 |
| Chips on the controls | 0 | 0 |
| Mean output tokens | 359 | 826 |
| Misspelled drug-name tokens | 3 | 5 |

In arm D, all 5 Major cases answered "Avoid" and all 7 Moderate or Minor cases a caution. It also
added about 15.6 seconds per answer on the Metal rig.

**Still open after run 1:**

- The run covered one patient, one phrasing and proposal questions only. No screening question was
  tested, and screening questions are the ones `isInteractionScreening` would route to such a
  prompt.
- The "Avoid only for a contraindication" rule was still ignored, so the prompt still contradicts
  itself.
- "What to monitor / do" is the model's own advice, which no record carries.

### Arm D on other question types

The same patient was asked seven other kinds of question under arms A, B and D. Arm D:

- opened a "should I stop" question with "Use with caution — monitor, for the proposed drug". It
  has no wording for a medication she is already taking;
- rated allergy findings "major" or "moderate", although every allergy chip is an unrated
  contraindication;
- refused *"most recent vital signs?"* outright.

In the same run, arm B's listing of the active orders inflated the chip count for Mebendazole
(45 against arm A's 0), Lithium carbonate (28 against 1) and a medication-list question (22 against
10).

**So a prompt like arm D could only ever serve proposal questions**, and routing any other question
to it makes that answer worse.

---

## Run 2 — 2026-09-25: perturbing the built-in prompt

**Setup.** `main` at `69f7b5ee`, 17 cases each run twice per arm: cases from the worked examples
doc plus one case per issue under test. The prompt global property was swapped per arm. An A/A
control (the property unset, against the property holding the default text) gave 34 of 34
byte-identical answers, so the noise floor was 0.

**Every prompt arm failed the gate.** Each was a targeted edit to `DEFAULT_SYSTEM_PROMPT` for
issues #539, #337 and #402, and each dropped findings in some case: on #402's case, the findings
cited fell from 7 to 2. One arm fixed #539's case only by deleting the mechanism text, and dropped
"Major" with it.

**What did fix the affected cases was taking the model out of them.** With
`chartsearchai.drugSafety.answerFromFindings=true`
([Decision 108](adr.md#decision-108-a-drug-safety-question-the-module-resolved-itself-is-answered-from-its-own-findings-and-the-model-is-not-asked-to-restate-them)),
the cases the module composed itself stated every finding with its rating, and every Major hazard
word for word. That fixed #539's case, one of #541's cases, and #399's case (recorded as "10-of-20"). The cases the model still answered were byte-identical to the baseline. That
property still defaults to `false`.

---

## What this means for the next attempt

- **In both runs the model followed the records over the prompt.** The findings, their wording and
  their ratings reach the model as records, and where the prompt's rules disagreed with them, the
  records won. To change what a finding says, change how the module writes it, not the prompt.
- **A prompt must keep the module's output format.** It must describe the
  `{reasoning, answer, citations}` object and the `[n]` citations. Without them the answer is lost,
  whatever else the prompt does well.
- **A prompt must not list the patient's orders in the answer.** Every drug the answer names is
  checked again.
- **A prompt must not treat an empty finding list as a completed check.** An unrecognised drug name
  produces no findings too. On a screening question, the module does state an empty screen itself
  ([Decision 87](adr.md#decision-87-a-screen-that-related-nothing-says-so-in-the-prompt-instead-of-reaching-the-model-as-an-empty-slice)),
  but that covers the screening check only.
- **Re-run against the built-in prompt's capture, with a gate fixed in advance, before proposing
  any prompt change.** Every perturbation measured so far dropped findings somewhere.

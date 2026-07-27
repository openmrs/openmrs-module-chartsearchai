"""Adjudication overrides that port the committed rc.2 gold's category boundaries onto THIS
standalone's concept vocabulary (RefApp 3.7.1 demo data).

The rc.2 boundaries were adjudicated against rc.2's concept names. This install has a different
concept set, so several genuinely on-topic concepts fall outside the rc.2 stems and would be
scored as drift (measured: "Disorder of Eyelid" and "Diplopia" are eye problems but match no eye
stem, so an entirely correct answer scored as an abstention failure with 3 off-topic citations).

Every addition below keeps the ORIGINAL gold's stated category intent:
  eye        — ocular structures/function (NOT general neurology)
  heart      — structural/functional cardiac + cerebrovascular disease; NOT plain hypertension,
               NOT raw vitals, NOT venous-only thrombophlebitis
  fractures  — trauma broadly (fracture/dislocation/injury/wound/burn/bite/amputation/sprain),
               NOT surgical/iatrogenic injury
  kidney     — renal/urinary conditions + renal labs; NOT the adrenal (endocrine) gland
  mental     — DSM/ICD psychiatric entities; NOT neurological findings
  allergies  — allergy/hypersensitivity records
  drug-allergies — the DRUG-allergen subset of the above

Ambiguous concepts are deliberately left OFF rather than guessed: the same gold scores every arm,
so a conservative boundary costs comparability nothing while a wrong inclusion adds noise.
"""

import build_gold_rc2 as G

# --- additional on-topic stems, by topic (lowercase regex, matched against normalized names) ---
EXTRA_STEMS = {
    "eye": [
        r"eyelid", r"\beyes\b", r"diplopia", r"pupillary", r"\borbital\b", r"hordeolum",
        r"tear production", r"xeroph",
    ],
    "heart": [
        r"cardiogenic", r"heartbeat", r"cerebrovascular", r"ductus arteriosus", r"foramen ovale",
        r"transposition of great vessels", r"ebstein", r"pulmonary artery stenosis",
        r"creatine kinase-mb",
    ],
    # NB: no bare "amputation" stem — "Traumatic amputation …" already matches the `trauma`
    # stem, and adding it would sweep in "Necrosis of amputation stump" (a post-amputation
    # complication, not trauma).
    "fractures": [
        r"avulsion", r"sunburn", r"\btorn\b", r"disorder of continuity of bone",
        r"accident caused by",
    ],
    "kidney": [
        r"cystitis", r"polyuria", r"\banuria\b", r"creatinine clearance",
    ],
    "mental": [
        r"psychotic", r"\bmania\b", r"trichotillomania", r"neurocognitive", r"psychogenic",
        r"\bamnesia\b", r"dysphori", r"delusion", r"personality disorder", r"oppositional",
        r"pervasive developmental", r"psychosomatic", r"cyclothym", r"nicotine",
        r"attention deficit", r"\buse disorder\b", r"narcotic abuse", r"khat abuse",
        r"stress disorder", r"reaction to severe stress", r"behavio[u]?r",
        r"intrusive thoughts", r"tardive", r"neuroleptic", r"elective mutism", r"dyssomnia",
        r"hypersomnia", r"paraphilia", r"voyeurism", r"sexual disorder", r"sibling jealousy",
    ],
    # NB: no "itchy" stem. The rc.2 gold counted a record literally named "Itching" (a
    # hypersensitivity symptom); this install has "Itchy Eyes", which is an ocular complaint —
    # counting it as an allergy record turned a correct "No allergies are recorded" into a
    # scored recall failure.
    "allergies": [
        r"atopic", r"\bwheal\b",
    ],
    "drug-allergies": [],
}

# --- additional exclusions (iatrogenic/surgical trauma stays off "fractures", per the original) ---
EXTRA_EXCLUDES = {
    # Surgical-wound complications are iatrogenic, not trauma (the original gold's rule). A
    # periprosthetic fracture, by contrast, IS a broken bone and stays ON.
    "fractures": [r"post-?operative", r"operation wound"],
    # "Stress fracture" must never count as mental; the mental stems above are phrase-anchored,
    # but keep the guard explicit so a later broadening cannot silently reintroduce it.
    "mental": [r"fractur"],
}

# --- explicit per-concept calls for this install's drug/non-drug allergen split ---
EXTRA_ADJUDICATIONS = {
    # drug allergens recorded as condition/diagnosis rows -> drug-allergies ON
    "allergy to ertapenem": {"drug-allergies": True},
    "allergy to chloroprocaine": {"drug-allergies": True},
    "calcium channel blocker allergy": {"drug-allergies": True},
    "contrast media allergy": {"drug-allergies": True},
    "personal history of allergy to anaesthetic agent": {"drug-allergies": True},
    # non-drug allergens -> OFF
    "allergy to almonds": {"drug-allergies": False},
    "allergy to cats": {"drug-allergies": False},
    "allergy to egg protein": {"drug-allergies": False},
    "allergy to seafood": {"drug-allergies": False},
    "hymenoptera allergy": {"drug-allergies": False},
    "history of allergy to milk products": {"drug-allergies": False},
    "personal history of allergy to shellfish": {"drug-allergies": False},
    "sulfite allergy": {"drug-allergies": False},   # food preservative, not a drug
    "allergic rhinitis": {"drug-allergies": False},
    "allergic contact dermatitis": {"drug-allergies": False},
    "urticaria": {"drug-allergies": False},
    "anaphylaxis": {"drug-allergies": False},
    "atopic dermatitis and related condition": {"drug-allergies": False},
    # "Sexual abuse" is a forensic/social code, not a psychiatric diagnosis -> OFF for mental
    # (the mental `behavio[u]?r` / `\buse disorder\b` stems do not reach it; stated for the record).
    "sexual abuse": {"mental": False},
}


def apply():
    """Merge the overrides into build_gold_rc2's tables (idempotent)."""
    for topic, stems in EXTRA_STEMS.items():
        b = G.BOUNDARIES[topic]
        for s in stems:
            if s not in b["stems"]:
                b["stems"].append(s)
    for topic, excludes in EXTRA_EXCLUDES.items():
        b = G.BOUNDARIES[topic]
        cur = b.setdefault("exclude", [])
        for s in excludes:
            if s not in cur:
                cur.append(s)
    for name, calls in EXTRA_ADJUDICATIONS.items():
        G.ADJUDICATIONS.setdefault(name, {}).update(calls)


def selftest():
    apply()
    C = G.classify
    # eye: this install's ocular vocabulary now counts
    assert C("eye", "condition", "disorder of eyelid", "") is True
    assert C("eye", "condition", "diplopia", "") is True
    assert C("eye", "obs", "itchy eyes", "") is True
    assert C("eye", "condition", "hypertensive retinopathy", "") is True
    assert C("eye", "condition", "proctoptosis", "") is False, "rectal prolapse is not an eye problem"
    # heart: cerebrovascular in (rc.2 precedent), plain hypertension and raw vitals out
    assert C("heart", "condition", "cerebrovascular accident (i64)", "") is True
    assert C("heart", "condition", "irregular heartbeat", "") is True
    assert C("heart", "condition", "secondary hypertension", "") is False
    assert C("heart", "obs", "systolic blood pressure", "") is False
    assert C("heart", "condition", "phlebitis and thrombophlebitis", "") is False
    # fractures: trauma in, iatrogenic out
    assert C("fractures", "condition", "traumatic amputation of lower leg", "") is True
    assert C("fractures", "condition", "disorder of continuity of bone", "") is True
    assert C("fractures", "condition", "postoperative wound infection", "") is False
    assert C("fractures", "condition", "intraoperative musculoskeletal disorder", "") is False
    assert C("fractures", "condition", (
        "fracture of bone following insertion of orthopedic implant, joint prosthesis, or bone plate"),
        "") is True, "a periprosthetic fracture is still a broken bone"
    assert C("fractures", "condition", "necrosis of amputation stump", "") is False, (
        "a stump complication is not trauma")
    # kidney: renal/urinary in, adrenal out
    assert C("kidney", "condition", "cystitis", "") is True
    assert C("kidney", "condition", "polyuria", "") is True
    assert C("kidney", "obs", "serum creatinine (mg/dl)", "") is True
    assert C("kidney", "condition", "malignant tumor of adrenal gland", "") is False
    # mental: DSM entities in, neurology and stress FRACTURE out
    assert C("mental", "condition", "brief reactive psychosis", "") is True
    assert C("mental", "condition", "moderate tobacco use disorder", "") is True
    assert C("mental", "condition", "tardive dyskinesia", "") is True
    assert C("mental", "condition", "acute stress disorder", "") is True
    assert C("mental", "condition", "stress fracture", "") is False, "a stress fracture is not psychiatric"
    assert C("mental", "condition", "generalized tonic-clonic seizure", "") is False
    assert C("mental", "condition", "sexual abuse", "") is False
    # allergies / drug-allergies split
    assert C("allergies", "condition", "atopic dermatitis and related condition", "") is True
    assert C("drug-allergies", "condition", "allergy to ertapenem", "") is True
    assert C("drug-allergies", "condition", "contrast media allergy", "") is True
    assert C("drug-allergies", "condition", "allergy to seafood", "") is False
    assert C("drug-allergies", "condition", "sulfite allergy", "") is False
    print("gold_overrides selftest OK")


if __name__ == "__main__":
    import os, sys
    sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
    selftest()

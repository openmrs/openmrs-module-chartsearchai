/**
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.chartsearchai.reference;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * {@link DrugReferenceSource} backed by the DDInter 2.0 drug-drug interaction knowledge
 * base ({@code ddi-knowledge-base.json} from the openmrs-ddi-knowledge-base data project):
 * structured DDIs with severity and a mechanism description, normalized to RxNorm and
 * cross-walked to CIEL. Selected by {@code sourceFormat=ddinter}. See ADR Decision 24.
 *
 * <p>The dataset is <em>normalized</em> — three tables joined by id, so nothing is
 * duplicated:
 * <ul>
 *   <li>{@code mechanisms}: {@code {groupId: {text, categories}}}, each description stored once;</li>
 *   <li>{@code drugs}: {@code {id, name, rxcui, rxnorm_name, drugbank_id, atc[], ciel[]}};</li>
 *   <li>{@code interactions}: rows {@code [drug_a_id, drug_b_id, severity, group_id]}.</li>
 * </ul>
 * This source expands that into the module's drug-centric {@link DrugReference} model: one
 * entry per drug, whose {@code interactions[]} are its partners with a {@code severity + mechanism}
 * note. Because the interaction rows are symmetric, each pair contributes to both drugs'
 * entries, which is what the validator's from-either-side matching expects.
 *
 * <p>One class of row is deliberately NOT expanded: a row pairing a drug with itself, or with another
 * route/formulation row of the same substance — see {@link #isSelfPair} (issue #152).
 *
 * <p>Memory: there are far fewer distinct mechanism descriptions than pairs, so the
 * per-partner notes are interned (one shared {@link String} per {@code severity + group}),
 * bounding note cost to the unique set rather than the pair count.
 *
 * <p>Resolution mirrors {@link JsonDrugReferenceSource}: prefer the operator file at
 * {@link ChartSearchAiConstants#GP_DRUG_REFERENCE_DATA_FILE_PATH}, else the dataset bundled
 * on the classpath at {@code /chartsearchai/ddi-knowledge-base.json}; any failure degrades
 * to an empty list (fail-safe), so the feature stays an additive net.
 *
 * <p><b>Scope.</b> V1 carries drug-drug interactions only: entries expose {@code interactions},
 * never {@code ageBands} or {@code contraindications} (dosing and drug-allergy/condition are
 * out of scope). {@code management} is not a discrete DDInter field, so whatever management prose
 * the mechanism text carries is folded into the interaction note rather than invented — save for
 * the residual field markers below, which are dropped because they carry no management content
 * to fold.
 *
 * <p><b>Residual field markers.</b> Some mechanism texts are prefixed with an all-caps field
 * marker followed by a colon — apparently the surviving tail of a management tag from the
 * monograph the mechanism text was scraped from. Measured over the full 8234-mechanism
 * KB (2026-08-04) there are exactly two: {@code INTERVAL:} (224 mechanisms, 4070 pair rows) and
 * {@code RECOMMENDED:} (50 mechanisms, 1268 pair rows); no other leading {@code TOKEN:} shape
 * occurs, and both markers appear only in that leading position. They are machine artifacts, not
 * prose, and the note reaches three surfaces verbatim — the clinician's chip detail, the rendered
 * reference record, and the pre-answer safety finding that reuses that chip detail
 * ({@link DrugReferenceInjector#renderFinding}) — so {@link #mechanismText} strips a leading
 * marker instead of passing it through (issue #116). Marker <em>shape</em>, not a fixed list of
 * the two seen today: a KB refresh emitting a sibling tag would otherwise leak it verbatim
 * exactly as these two did.
 *
 * <p>The marker is dropped rather than reinterpreted. {@code INTERVAL:} broadly flags
 * administration timing (dose separation is the management for the chelation/absorption rows —
 * 147 of the 224 are {@code categories: [absorption]}), but it is not reliable enough to render
 * as advice: nine of the 224 are {@code synergistic_effect} rows where separating doses is
 * <em>not</em> the management (ibutilide plus a class III antiarrhythmic — additive QT
 * prolongation; flibanserin plus alcohol; mefloquine convulsion risk). The marker itself carries
 * no interval, no separation time and no wording, so any management sentence built from it would
 * be invented, and the mechanism {@code categories} are a PK/PD taxonomy
 * ({@code metabolism}/{@code absorption}/…) that does not encode management either. Structured
 * management guidance therefore has to come from the KB builder keeping the whole upstream tag,
 * not from reverse-engineering its truncated residue here.
 */
public class DdiDrugReferenceSource implements DrugReferenceSource {

	private static final Logger log = LoggerFactory.getLogger(DdiDrugReferenceSource.class);

	static final String CLASSPATH_DEFAULT = "/chartsearchai/ddi-knowledge-base.json";

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private static final String SOURCE = "DDInter 2.0 (via openmrs-ddi-knowledge-base)";

	/**
	 * A residual field marker at the head of a mechanism text: a run of up to six all-caps words
	 * immediately followed by a colon (see the class javadoc). Words are two letters or more so a
	 * bare initial cannot look like a marker, and the run is bounded so a shouted sentence ending
	 * in a colon is not mistaken for one. Verified against the full 8234-mechanism KB: it matches
	 * exactly the 274 {@code INTERVAL:}/{@code RECOMMENDED:} rows and nothing else, and every
	 * remainder still begins with a capital, so the stripped note reads as a sentence.
	 */
	private static final Pattern RESIDUAL_FIELD_MARKER = Pattern
			.compile("^\\s*[A-Z]{2,}(?: [A-Z]{2,}){0,5}:\\s*");

	private volatile String lastLoadOrigin;

	@Override
	public List<DrugReference> load() {
		ReferenceDataFiles.Loaded<DrugReference> loaded = ReferenceDataFiles.loadWithClasspathFallback(
				ChartSearchAiConstants.GP_DRUG_REFERENCE_DATA_FILE_PATH, CLASSPATH_DEFAULT,
				"DDInter drug-reference entries", DdiDrugReferenceSource::parse);
		lastLoadOrigin = loaded.getOrigin();
		return loaded.getItems();
	}

	@Override
	public String lastLoadOrigin() {
		return lastLoadOrigin;
	}

	/**
	 * Parse the normalized DDI knowledge base into drug-centric {@link DrugReference} entries.
	 * Package-private and static so tests exercise the real parser against a real dataset.
	 */
	static List<DrugReference> parse(InputStream in) throws IOException {
		JsonNode root = MAPPER.readTree(in);
		if (root == null || !root.hasNonNull("drugs") || !root.hasNonNull("interactions")) {
			return Collections.emptyList();
		}

		// drugs table, indexed by id
		Map<String, DrugRow> byId = new HashMap<String, DrugRow>();
		List<DrugRow> order = new ArrayList<DrugRow>();
		for (JsonNode d : root.get("drugs")) {
			DrugRow row = DrugRow.of(d);
			if (row != null) {
				byId.put(row.id, row);
				order.add(row);
			}
		}

		// mechanisms table (text stored once); note strings interned per severity+group.
		// Severity strings are interned too: the vocabulary is four values across ~300k
		// full-KB rows, and Jackson allocates a fresh String per row — without this cache the
		// structured severity field alone would retain ~13.5 MB for the module lifetime,
		// reintroducing exactly the per-pair cost the note interning exists to avoid.
		JsonNode mech = root.path("mechanisms");
		Map<String, String> noteCache = new HashMap<String, String>();
		Map<String, String> severityCache = new HashMap<String, String>();

		// group interaction rows by drug id -> partner links
		Map<String, List<Link>> partners = new HashMap<String, List<Link>>();
		int selfPairs = 0;
		for (JsonNode row : root.get("interactions")) {
			if (row == null || !row.isArray() || row.size() < 4) {
				continue;
			}
			String a = row.get(0).asText();
			String b = row.get(1).asText();
			String severity = severityCache.computeIfAbsent(row.get(2).asText(), s -> s);
			String gid = row.get(3).asText();
			if (!byId.containsKey(a) || !byId.containsKey(b)) {
				continue;
			}
			if (isSelfPair(byId.get(a), byId.get(b))) {
				selfPairs++;
				continue;
			}
			String note = noteFor(severity, gid, mech, noteCache);
			partners.computeIfAbsent(a, k -> new ArrayList<Link>()).add(new Link(b, severity, note));
			partners.computeIfAbsent(b, k -> new ArrayList<Link>()).add(new Link(a, severity, note));
		}
		if (selfPairs > 0) {
			// WARN, not DEBUG: a knowledge base pairing a drug with itself is a data-validity problem in
			// the operator's or the upstream project's file, and the count is how they see a refresh has
			// introduced more of them. Once per load, with the count, rather than per row — the shipped KB
			// has 26 of them among 295,184 rows and a per-row line would say nothing extra.
			log.warn("Skipped {} DDInter interaction row(s) pairing a drug with itself or with another "
					+ "route/formulation row of the same substance — a drug cannot interact with itself",
					selfPairs);
		}

		// RxCUI frequency: some route variants share a RxCUI (e.g. the Lidocaine variants all
		// map to 6387). The id must be unique — the injector dedups citations by it — so the
		// RxCUI is used only when it identifies exactly one entry; otherwise the DDInter id.
		Map<String, Integer> rxcuiCounts = new HashMap<String, Integer>();
		for (DrugRow row : order) {
			if (row.rxcui != null && !row.rxcui.isEmpty()) {
				rxcuiCounts.merge(row.rxcui, 1, Integer::sum);
			}
		}

		// build one entry per drug, in dataset order
		List<DrugReference> out = new ArrayList<DrugReference>();
		for (DrugRow row : order) {
			List<Link> links = partners.get(row.id);
			DrugReference ref = new DrugReference();
			boolean uniqueRxcui = row.rxcui != null && !row.rxcui.isEmpty()
					&& rxcuiCounts.get(row.rxcui) == 1;
			ref.setId(uniqueRxcui ? row.rxcui : row.id);
			ref.setName(row.name);
			// The substance this row is a route/formulation of, ALWAYS — unlike the chip-label synonym
			// below, which is deliberately withheld when the display name already contains it. That is
			// what makes it usable as an identity: it is the field the route variants sharing one RxCUI
			// agree on ("Dexamethasone", "Dexamethasone (nasal)", … all publish "dexamethasone"), and
			// the safety chips group on it so one substance is one finding (issue #145, see
			// DrugReference.substanceKey). Not the RxCUI itself, though it partitions this KB's entries
			// identically: setId above already spends the RxCUI on entry identity, where a shared one is
			// precisely what it must NOT be.
			ref.setSubstanceName(row.rxnormName);
			// Chip-label synonym (never renaming): when the DDInter display name diverges from
			// the RxNorm generic the question and chart use ("Acetylsalicylic acid" vs
			// "aspirin"), carry the generic so safety chips can show both vocabularies. A name
			// that already contains its generic — including the route variants sharing one
			// RxNorm name ("Lidocaine (topical)") — carries none. Renaming outright was
			// measured and rejected: 276 of the full KB's names diverge, mostly INN-vs-USAN
			// pairs a swap would mistranslate.
			if (row.rxnormName != null && !row.rxnormName.isEmpty()
					&& !row.name.toLowerCase(Locale.ROOT).contains(row.rxnormName.toLowerCase(Locale.ROOT))) {
				ref.setGenericName(row.rxnormName.toLowerCase(Locale.ROOT));
			}
			ref.setAliases(row.aliases);
			ref.setAtcCodes(row.atc);
			ref.setInteractions(interactionsFor(links, byId));
			ref.setSource(SOURCE);
			out.add(ref);
		}
		log.info("Parsed {} DDInter drug-reference entries", out.size());
		return out;
	}

	/**
	 * Whether an interaction row joins a drug to ITSELF and so must not be loaded (issue #152). Two
	 * tests, because the KB produces the shape two ways:
	 * <ul>
	 *   <li>the same row on both sides — exactly one in the shipped 19 MB KB, {@code DDInter225}
	 *       (botulinum toxin type A) of 295,184 rows. Its mechanism text is about administering
	 *       different botulinum SEROTYPES together, which this KB carries no second row for, so the pair
	 *       is an artifact of its granularity; what reaches a clinician is "Botulinum toxin type A
	 *       interacts with active order botulinum toxin type A".</li>
	 *   <li>two ROUTE/FORMULATION rows of one substance — 25 more, {@code Lidocaine} against
	 *       {@code Lidocaine (topical)} and the like (measured 2026-08-06; re-measure before relying on
	 *       the figures). Also unrenderable rather than merely redundant: every row of a substance
	 *       publishes the same {@code rxnorm_name}, which is the match token a rule carries and the label
	 *       a chip prints, so such a pair can ONLY read as a substance interacting with itself. The
	 *       systemic-plus-topical exposure the KB row is about cannot be stated by anything this module
	 *       renders, while the self-reference can.</li>
	 * </ul>
	 * Through {@link DrugReference#substanceKey(String, String)} rather than a local comparison, so this
	 * guard and the chip grouping mean the same thing by "one substance". A row publishing no substance
	 * name keys null and is then only caught by the id test — the conservative direction: with no
	 * substance identity to compare, two different ids are two different drugs.
	 *
	 * <p>At load rather than in the arms that read the rules: those rows feed five consumers (the
	 * drug-in-play chips, the screening arm, the question-pair arm, the promoted notes inside the
	 * injected reference record, and the pre-answer finding derived from a chip), and one invariant at
	 * the parse boundary covers all of them and any future KB revision. Only this source is guarded — a
	 * hand-authored curated file is the operator's own data, and the {@code atc} adapter carries no
	 * rules at all.
	 */
	private static boolean isSelfPair(DrugRow a, DrugRow b) {
		if (a.id.equals(b.id)) {
			return true;
		}
		Object substance = DrugReference.substanceKey(a.name, a.rxnormName);
		return substance != null && substance.equals(DrugReference.substanceKey(b.name, b.rxnormName));
	}

	private static List<DrugReference.Interaction> interactionsFor(List<Link> links, Map<String, DrugRow> byId) {
		if (links == null || links.isEmpty()) {
			return Collections.emptyList();
		}
		List<DrugReference.Interaction> out = new ArrayList<DrugReference.Interaction>();
		for (Link link : links) {
			DrugRow p = byId.get(link.partnerId);
			if (p == null) {
				continue;
			}
			DrugReference.Interaction i = new DrugReference.Interaction();
			// Match on the RxNorm generic name (e.g. "aspirin", "acetaminophen") rather than the
			// DDInter display name ("Acetylsalicylic acid"), since the validator matches this token
			// against the order's display name (by DrugReference.matchesOrderName, which needs the
			// token to start a word of that name); fall back to the display name.
			String token = p.rxnormName != null && !p.rxnormName.isEmpty() ? p.rxnormName : p.name;
			i.setToken(token.toLowerCase(Locale.ROOT));
			i.setAtc(p.atc.isEmpty() ? null : p.atc.get(0));
			i.setSeverity(link.severity);
			i.setNote(link.note);
			out.add(i);
		}
		return out;
	}

	/** Interned note: one shared string per (severity, group). */
	private static String noteFor(String severity, String gid, JsonNode mech, Map<String, String> cache) {
		String key = severity + " " + gid;
		String cached = cache.get(key);
		if (cached != null) {
			return cached;
		}
		String text = mechanismText(mech, gid);
		String note = (text != null && !text.isEmpty())
				? severity + ". " + text
				: severity + " severity interaction (DDInter 2.0; no mechanism description on file).";
		cache.put(key, note);
		return note;
	}

	/**
	 * The mechanism description for {@code gid}, with any leading residual field marker removed
	 * (see the class javadoc), or {@code null} when the group carries no text. A text that is
	 * <em>only</em> a marker strips to empty and so degrades to the no-mechanism note in
	 * {@link #noteFor} — a dangling "Major. " would read worse than saying nothing is on file.
	 */
	private static String mechanismText(JsonNode mech, String gid) {
		JsonNode node = mech.path(gid).path("text");
		if (!node.isTextual()) {
			return null;
		}
		String text = node.asText();
		Matcher marker = RESIDUAL_FIELD_MARKER.matcher(text);
		return marker.lookingAt() ? text.substring(marker.end()) : text;
	}

	/** A drug row from the {@code drugs} table. */
	private static final class DrugRow {

		final String id;

		final String name;

		final String rxcui;

		final String rxnormName;

		final List<String> atc;

		final List<String> aliases;

		private DrugRow(String id, String name, String rxcui, String rxnormName, List<String> atc, List<String> aliases) {
			this.id = id;
			this.name = name;
			this.rxcui = rxcui;
			this.rxnormName = rxnormName;
			this.atc = atc;
			this.aliases = aliases;
		}

		static DrugRow of(JsonNode d) {
			String id = d.path("id").asText(null);
			String name = d.path("name").asText(null);
			if (id == null || id.isEmpty() || name == null || name.isEmpty()) {
				return null;
			}
			String rxcui = d.path("rxcui").isTextual() ? d.get("rxcui").asText() : null;
			// Trimmed once here so the match token, the divergence guard, and the chip-label
			// synonym all see the same clean value — a padded name would defeat the guard and
			// leak padding into the label.
			String rxnormName = d.path("rxnorm_name").isTextual() ? d.get("rxnorm_name").asText().trim() : null;
			List<String> atc = new ArrayList<String>();
			for (JsonNode a : d.path("atc")) {
				atc.add(a.asText());
			}
			// aliases: name + RxNorm name + CIEL concept names, lowercased and de-duplicated
			List<String> aliases = new ArrayList<String>();
			addAlias(aliases, name);
			if (d.path("rxnorm_name").isTextual()) {
				addAlias(aliases, d.get("rxnorm_name").asText());
			}
			for (JsonNode c : d.path("ciel")) {
				if (c.path("name").isTextual()) {
					addAlias(aliases, c.get("name").asText());
				}
			}
			return new DrugRow(id, name, rxcui, rxnormName, atc, aliases);
		}

		private static void addAlias(List<String> aliases, String value) {
			if (value == null) {
				return;
			}
			String a = value.trim().toLowerCase(Locale.ROOT);
			if (!a.isEmpty() && !aliases.contains(a)) {
				aliases.add(a);
			}
		}
	}

	/** A partner link: the other drug's id, the row's severity, and the shared interaction note. */
	private static final class Link {

		final String partnerId;

		final String severity;

		final String note;

		Link(String partnerId, String severity, String note) {
			this.partnerId = partnerId;
			this.severity = severity;
			this.note = note;
		}
	}
}

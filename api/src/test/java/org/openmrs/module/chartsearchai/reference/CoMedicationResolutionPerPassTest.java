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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.openmrs.module.chartsearchai.ModuleSourceRoot;

/**
 * The patient's co-medications are resolved ONCE per {@code validate} pass, however many drugs the
 * question puts in play (issue #256).
 *
 * <p>{@code DrugSafetyValidator.classRelationships} runs per in-play substance and used to call
 * {@code orderPartners(context)} each time — a resolution of the whole active-order list that is a
 * function of the pass's context alone, so every call after the first re-derived an answer the pass
 * already had. The cost is NOT the pairwise arms the issue names: measured through the real
 * {@code validate} over the shipped knowledge base, it grows as drugs-in-play TIMES active orders
 * rather than as pairs, and on a 43-order chart a probe attributed 77% of a ten-drug pass to that one
 * method, while ALL the work a chart-less pass does — the drug-in-play arms and the pairwise ones
 * together — came to 30 ms of that 490, which is the upper bound on the arms the issue blamed.
 *
 * <p><b>What these cases count, and why that is the honest unit.</b> A timing assertion would be flaky
 * and machine-shaped. The repeat's own cost is full walks of the loaded dataset — what
 * {@code orderPartners}' javadoc calls "the repeated full scans" — and a walk begins with a call to
 * {@code DrugReferenceService.getAll()}, which is a deterministic integer count: independent of how
 * big the dataset is and of how fast the box is. So the behavioural cases run on the small DDInter
 * excerpt and count that call, through a subclass that increments and delegates to
 * {@code super.getAll()}: the real service, the real parser, the real {@code validate}. An instrument,
 * not a mock — nothing of the pipeline is re-expressed.
 *
 * <p>What is counted is therefore CALLS and not walks, and the two are not the same: {@code validate}
 * takes the list once for its own use without walking it. That inflates the absolute numbers and
 * cannot reach the invariant below, which is a DIFFERENCE between two passes over the same question —
 * every call a pass makes regardless of the chart appears on both sides and cancels.
 *
 * <p><b>The invariant is stated as a DIFFERENCE, so that no tally is published and none can go
 * stale.</b> The same question is validated twice, once against a chart carrying active orders and
 * once against a chart carrying none; the sweeps attributable to the ORDERS are the difference, and
 * that difference must be the same for every number of drugs in play. Against the excerpt it is flat
 * with the resolution held for the pass and rises by roughly one resolution per drug without it, so
 * the case reddens at TWO drugs in play rather than only at five.
 *
 * <p><b>Two guards against a vacuous pass</b>, because {@code 0 == 0} would satisfy the invariant while
 * testing nothing: the orders must cost at least one sweep at all, and the five-drug question must
 * raise strictly more chips than the one-drug question — otherwise the extra drugs are not reaching
 * the arm that resolves co-medications. A fixture edit breaking either would make the invariant
 * unfalsifiable rather than merely wrong.
 *
 * <p><b>Why there is a source scan beside the behavioural cases.</b> Two things the counting cases
 * cannot see. A memo held in a FIELD and reassigned once per pass passes them flat, which is exactly
 * the limit CLAUDE.md records for the analogous {@code recordedAllergens} memo — so the single
 * construction site is asserted structurally instead. And a NEW per-subject caller of
 * {@code orderPartners}, or of the uncached {@code entryForAtcCode(String)}, in an arm these fixtures
 * do not exercise would reintroduce the defect invisibly: {@code ruleAbout} was the second such caller
 * and this arrangement never reaches it, since that method returns before resolving anything when the
 * subject has no rule about an active order, which is the ordinary outcome. The mechanism mirrors
 * {@code ChipSubjectOneResolutionTest}'s and {@code OrderPartnerNameSourceWritePathTest}'s; a third
 * copy rather than an extraction, because the first of those records that keeping them independent is
 * deliberate, and unifying all three is a change of its own.
 */
public class CoMedicationResolutionPerPassTest {

	/** The real service over the pinned excerpt, counting the calls that begin a dataset walk. */
	private static final class SweepCountingService extends DrugReferenceService {

		private int sweeps;

		@Override
		public List<DrugReference> getAll() {
			sweeps++;
			return super.getAll();
		}
	}

	/** Eight active orders the concept dictionary mapped to no ATC code — the majority shape on the
	 *  3.7.1 reference dictionary, and the one whose partner is resolved by NAME (issue #228). */
	private static final List<String> ORDER_NAMES = Arrays.asList("Warfarin", "Digoxin", "Amiodarone",
			"Sertraline", "Spironolactone", "Metformin", "Tramadol", "Ciprofloxacin");

	/** Five substances the excerpt classifies, so each reaches the class arm and asks the pass for its
	 *  co-medications. */
	private static final List<String> IN_PLAY = Arrays.asList("simvastatin", "clarithromycin",
			"fluconazole", "methotrexate", "ibuprofen");

	/**
	 * Every chip the two-drug question raises, in order, as {@code type | severity | lead} — the lead
	 * being the detail up to its em dash, which is the half naming the SUBJECT and the PARTNER.
	 *
	 * <p>The mechanism prose is deliberately not pinned: it is the dataset's, another case's business,
	 * and it is not what sharing one partner list across two subjects could disturb. What could is
	 * exactly what is here — which partner each subject is chipped about, what that partner is called,
	 * how the pair is rated, and the order the chips arrive in. Captured from the pre-change code and
	 * unchanged by the change (verified by diffing the full rendered chip list, all five questions and
	 * every detail in full, across the two heads).
	 */
	private static final List<String> TWO_DRUG_CHIPS = Arrays.asList(
			"interaction | Minor | Simvastatin interacts with active order warfarin",
			"interaction | Moderate | Simvastatin interacts with active order ciprofloxacin",
			"interaction | Major | Simvastatin interacts with active order amiodarone",
			"interaction | Moderate | Clarithromycin interacts with active order metformin",
			"interaction | Moderate | Clarithromycin interacts with active order sertraline",
			"interaction | Moderate | Clarithromycin interacts with active order tramadol",
			"interaction | Major | Clarithromycin interacts with active order warfarin",
			"interaction | Moderate | Clarithromycin interacts with active order ciprofloxacin",
			"interaction | Major | Clarithromycin interacts with active order digoxin",
			"interaction | Major | Clarithromycin interacts with active order amiodarone",
			"interaction | Major | Simvastatin interacts with Clarithromycin, also named in the question");

	/** A question naming nothing the excerpt classifies, so no arm asks for a co-medication. */
	private static final String NAMES_NO_DRUG = "How old is she?";

	private static SweepCountingService service() {
		SweepCountingService service = new SweepCountingService();
		service.setEntries(DrugReferenceTestSupport.ddinterEntries());
		service.setCrossReactivityGroups(DrugReferenceTestSupport.bundledGroups());
		return service;
	}

	private static PatientClinicalContext chartWithOrders() {
		List<PatientClinicalContext.ActiveDrugOrder> orders =
				new ArrayList<PatientClinicalContext.ActiveDrugOrder>();
		Set<String> names = new LinkedHashSet<String>();
		for (String name : ORDER_NAMES) {
			orders.add(DrugReferenceTestSupport.activeOrder("order-" + name, name, name));
			names.add(name.toLowerCase());
		}
		return DrugReferenceTestSupport.ctx(60, 70.0, names, null, null, null, orders);
	}

	private static String questionNaming(int drugs) {
		StringBuilder question = new StringBuilder("Can I give her ");
		for (int i = 0; i < drugs; i++) {
			if (i > 0) {
				question.append(" and ");
			}
			question.append(IN_PLAY.get(i));
		}
		return question.append("?").toString();
	}

	@Test
	public void theCoMedicationResolutionDoesNotGrowWithTheDrugsInPlay() {
		SweepCountingService service = service();
		DrugSafetyValidator validator = DrugReferenceTestSupport.validator(service);
		PatientClinicalContext withOrders = chartWithOrders();
		PatientClinicalContext noOrders = DrugReferenceTestSupport.ctx(60, 70.0, null, null, null, null);

		int attributableToOrdersAtOneDrug = -1;
		int chipsAtOneDrug = -1;
		int chipsAtEveryDrug = -1;
		for (int drugs = 1; drugs <= IN_PLAY.size(); drugs++) {
			String question = questionNaming(drugs);

			service.sweeps = 0;
			int chips = validator.validate("", question, withOrders).size();
			int withOrdersSweeps = service.sweeps;

			service.sweeps = 0;
			validator.validate("", question, noOrders);
			int attributableToOrders = withOrdersSweeps - service.sweeps;

			if (drugs == 1) {
				attributableToOrdersAtOneDrug = attributableToOrders;
				chipsAtOneDrug = chips;
				assertTrue(attributableToOrders > 0, "the arrangement must resolve the patient's "
						+ "co-medications at all, or the invariant below is vacuous");
			}
			chipsAtEveryDrug = chips;
			assertEquals(attributableToOrdersAtOneDrug, attributableToOrders,
				"validating a question naming " + drugs + " drugs re-resolved the patient's active "
						+ "orders: the dataset sweeps their presence costs must not grow with the drugs "
						+ "in play (issue #256). Whatever arm was added, read the pass's CoMedications "
						+ "rather than resolving the chart again.");
		}
		assertTrue(chipsAtEveryDrug > chipsAtOneDrug, "the extra drugs in play must reach the arm that "
				+ "resolves co-medications, or the invariant above is vacuous");
	}

	/**
	 * A question that puts no substance in play resolves the chart NOT AT ALL — the laziness the memo
	 * exists to keep, and the reason the resolution is not simply hoisted to the top of
	 * {@code validate}.
	 *
	 * <p>Without this, an eager hoist passes every other case here: the sweep invariant stays flat
	 * because the resolution still happens once, and the source scan still finds one construction
	 * inside {@code validate}. What it would cost is the commonest question of all, which reaches no
	 * arm that needs a co-medication and today pays for none.
	 *
	 * <p>Stated as a comparison rather than as "zero", because a chart with orders costs sweeps that
	 * have nothing to do with this memo — {@code findForActiveOrders} and {@code withReferenceNames}
	 * run for every pass — so zero is the wrong bar and would never be met.
	 */
	@Test
	public void aQuestionPuttingNoSubstanceInPlayDoesNotResolveTheChartAtAll() {
		SweepCountingService service = service();
		DrugSafetyValidator validator = DrugReferenceTestSupport.validator(service);
		PatientClinicalContext withOrders = chartWithOrders();
		PatientClinicalContext noOrders = DrugReferenceTestSupport.ctx(60, 70.0, null, null, null, null);

		assertEquals(0, validator.validate("", NAMES_NO_DRUG, withOrders).size(),
			"the arrangement must put no substance in play, or this case is about something else");
		int unasked = attributableToOrders(validator, service, withOrders, noOrders, NAMES_NO_DRUG);
		int asked = attributableToOrders(validator, service, withOrders, noOrders, questionNaming(1));
		assertTrue(unasked < asked,
			"a question naming no drug the dataset knows cost the same " + unasked + " order-attributable "
					+ "dataset sweeps as one naming a drug, so the chart was resolved for a pass that "
					+ "never asked for it. CoMedications must resolve on first USE, not on construction "
					+ "(issue #256).");
	}

	/** The dataset sweeps the patient's ORDERS cost this question — the with-chart pass less the
	 *  chart-less one, which is what isolates them from the rest of a pass's work. */
	private static int attributableToOrders(DrugSafetyValidator validator, SweepCountingService service,
			PatientClinicalContext withOrders, PatientClinicalContext noOrders, String question) {
		service.sweeps = 0;
		validator.validate("", question, withOrders);
		int withOrdersSweeps = service.sweeps;
		service.sweeps = 0;
		validator.validate("", question, noOrders);
		return withOrdersSweeps - service.sweeps;
	}

	/**
	 * The chips a question raises do not depend on whether each subject resolved its own copy of the
	 * co-medications or read one the pass holds — the correctness condition the whole change rests on,
	 * and the one a sweep count cannot see.
	 */
	@Test
	public void twoSubjectsSharingOneResolutionAreChippedExactlyAsTheyWereWhenEachResolvedItsOwn() {
		List<SafetyWarning> warnings = DrugReferenceTestSupport.validator(service())
				.validate("", questionNaming(2), chartWithOrders());
		List<String> leads = new ArrayList<String>();
		for (SafetyWarning warning : warnings) {
			String detail = warning.getDetail();
			int dash = detail.indexOf(" — ");
			leads.add(warning.getType() + " | " + warning.getSeverity() + " | "
					+ (dash < 0 ? detail : detail.substring(0, dash)));
		}
		assertEquals(TWO_DRUG_CHIPS, leads, "the second subject reads the co-medications the first "
				+ "resolved, so a chip's subject, its partner, that partner's NAME, the pair's rating or "
				+ "the order the chips arrive in must not have moved (issue #256)");
	}

	private static final String RELATIVE_SOURCE =
			"src/main/java/org/openmrs/module/chartsearchai/reference/DrugSafetyValidator.java";

	/** The one arity of {@code validate} that builds the pass's shared state; the others delegate to
	 *  it. The needle stops at the line break, so it is a single line of the file as written. */
	private static final String VALIDATE =
			"validate(String answer, String question, PatientClinicalContext rawContext,";

	private static final String MEMO_DECLARATION = "private final class CoMedications {";

	private static final String CONSTRUCTION = "new CoMedications(";

	/** The pass's one construction, as written — a LOCAL declaration, which is what separates the
	 *  compliant memo from issue #172's field. */
	private static final String CONSTRUCTION_STATEMENT =
			"CoMedications coMedications = new CoMedications(context);";

	/** The memo's own accessor, which is the only body permitted to call {@code orderPartners}. */
	private static final String RESOLVED = "List<OrderPartner> resolved() {";

	/** The memoising overload, which is the only body permitted to call the uncached sweep. */
	private static final String CACHING_LOOKUP =
			"private DrugReference entryForAtcCode(String upperCode, Map<String, DrugReference> cache) {";

	private static final Pattern RESOLUTION_CALL = Pattern.compile("\\borderPartners\\s*\\(");

	/**
	 * A FIELD of the memo's type on {@code DrugSafetyValidator} itself — issue #172's trap, and the one
	 * shape neither counting case can see, because a field reassigned once per pass sweeps exactly as
	 * often as a local does. Anchored to a single leading tab, which is this file's indentation for a
	 * member of the outer class; the pass's own local sits two tabs in, inside {@code validate}.
	 *
	 * <p>What it does NOT see is named rather than left to be discovered: a declaration this needle's
	 * shape does not fit — split across lines, carrying an annotation or a generic, indented some other
	 * way. The construction-statement check beside it is the second net, since a field has to be
	 * ASSIGNED to be a memo and that assignment is not the local declaration the needle there demands;
	 * a shape that evades both is a new needle here, not a looser one.
	 */
	private static final Pattern MEMO_FIELD =
			Pattern.compile("(?m)^\\t[\\w ]*\\bCoMedications\\s+\\w+\\s*[;=]");

	/** A call to the UNCACHED lookup: one bare identifier between the parentheses. The declaration
	 *  ({@code entryForAtcCode(String upperCode)}) carries two tokens and so cannot match, and neither
	 *  does the memoising overload's two-argument call. {@code CoMedications} names its own accessor
	 *  {@code entryForCode} precisely so that a qualified call to the memo cannot be mistaken for a
	 *  bare call to the sweep by a text scan. */
	private static final Pattern UNCACHED_LOOKUP_CALL =
			Pattern.compile("\\bentryForAtcCode\\s*\\(\\s*[A-Za-z0-9_]+\\s*\\)");

	@Test
	public void theResolutionIsBuiltOncePerPassAndNothingElseResolvesTheChart() throws IOException {
		String source = strippedSource();
		Region memo = bodyOf(source, uniqueOffsetOf(source, MEMO_DECLARATION), MEMO_DECLARATION);
		Region resolved = bodyOf(source, uniqueOffsetOf(source, RESOLVED), RESOLVED);
		Region cachingLookup = bodyOf(source, uniqueOffsetOf(source, CACHING_LOOKUP), CACHING_LOOKUP);
		Region validate = bodyOf(source, uniqueOffsetOf(source, VALIDATE), VALIDATE);
		assertTrue(memo.contains(resolved.start()),
			"the accessor matched by \"" + RESOLVED + "\" is not inside CoMedications, so this guard has "
					+ "delimited the wrong body and everything below it is meaningless (issue #256)");

		List<Integer> constructions = offsetsOfLiteral(source, CONSTRUCTION);
		assertEquals(1, constructions.size(),
			"expected " + RELATIVE_SOURCE + " to construct CoMedications exactly once — in validate, for "
					+ "the whole pass — and found " + constructions.size() + " at lines "
					+ linesOf(source, constructions) + ". A second construction is one chart resolved "
					+ "twice (issue #256).");
		assertTrue(validate.contains(constructions.get(0)),
			"CoMedications is constructed at line " + lineOf(source, constructions.get(0)) + ", outside "
					+ "the body of validate(String, String, PatientClinicalContext, List). Built anywhere "
					+ "per-subject it memoises nothing; held in a FIELD it is issue #172's trap, which no "
					+ "behavioural case here can see because a field reassigned once per pass counts the "
					+ "same sweeps.");

		assertTrue(CONSTRUCTION_STATEMENT.equals(statementAt(source, constructions.get(0))),
			"the construction of CoMedications at line " + lineOf(source, constructions.get(0)) + " reads "
					+ "\"" + statementAt(source, constructions.get(0)) + "\" rather than \""
					+ CONSTRUCTION_STATEMENT + "\", so this guard can no longer tell a pass LOCAL from an "
					+ "assignment to something outliving the pass (issue #172, issue #256). If the "
					+ "statement changed shape for a good reason, move this needle with it.");
		Matcher field = MEMO_FIELD.matcher(source);
		if (field.find()) {
			fail("DrugSafetyValidator declares CoMedications as a member at line "
					+ lineOf(source, field.start()) + ": \"" + field.group().trim() + "\". This bean is a "
					+ "Spring singleton, so a memo held on it is one unsynchronized structure shared by "
					+ "every concurrent request, and both of its memos are keyed on one patient's chart — "
					+ "a field answers for whoever asked first (issue #172). It must be a local of the "
					+ "pass, which is the one shape neither counting case above can tell apart: a field "
					+ "reassigned once per pass sweeps exactly as often as a local does.");
		}

		List<Integer> resolutionCalls = callsOutsideDeclarations(source, RESOLUTION_CALL,
				"private List<OrderPartner> orderPartners(");
		assertEquals(1, resolutionCalls.size(),
			"expected exactly one call to orderPartners in " + RELATIVE_SOURCE + " — the pass's own, "
					+ "inside CoMedications.resolved() — and found " + resolutionCalls.size()
					+ " at lines " + linesOf(source, resolutionCalls) + " (issue #256).");
		assertTrue(resolved.contains(resolutionCalls.get(0)),
			"orderPartners is called at line " + lineOf(source, resolutionCalls.get(0)) + ", outside "
					+ "CoMedications.resolved(). That resolution is a function of the pass's context "
					+ "alone; calling it from an arm resolves the whole active-order list again, once "
					+ "per in-play substance, which is issue #256. Read CoMedications instead.");

		List<Integer> uncachedCalls = callsOutsideDeclarations(source, UNCACHED_LOOKUP_CALL,
				"private DrugReference entryForAtcCode(String upperCode) {");
		assertEquals(1, uncachedCalls.size(),
			"expected exactly one call to the uncached entryForAtcCode(String) in " + RELATIVE_SOURCE
					+ " — the one inside its memoising overload — and found " + uncachedCalls.size()
					+ " at lines " + linesOf(source, uncachedCalls) + " (issue #256).");
		assertTrue(cachingLookup.contains(uncachedCalls.get(0)),
			"the uncached entryForAtcCode(String) is called at line " + lineOf(source, uncachedCalls.get(0))
					+ ", outside its memoising overload. It is a full sweep of the dataset per code; "
					+ "ruleAbout called it that way and swept once per (subject, partner, code) as a "
					+ "result. Go through CoMedications, which holds the cache for the pass (issue #256).");
	}

	/** @return the offsets of every match of {@code call} that is not the declaration located by
	 *          {@code declaration}, which is asserted to be present and unique first — so a renamed
	 *          method fails this guard loudly rather than leaving it forbidding nothing. */
	private static List<Integer> callsOutsideDeclarations(String source, Pattern call,
			String declaration) {
		int declared = uniqueOffsetOf(source, declaration);
		Matcher matcher = call.matcher(source);
		List<Integer> found = new ArrayList<Integer>();
		while (matcher.find()) {
			if (matcher.start() < declared || matcher.start() > declared + declaration.length()) {
				found.add(matcher.start());
			}
		}
		return found;
	}

	private static List<Integer> offsetsOfLiteral(String source, String needle) {
		List<Integer> found = new ArrayList<Integer>();
		int at = source.indexOf(needle);
		while (at >= 0) {
			found.add(at);
			at = source.indexOf(needle, at + 1);
		}
		return found;
	}

	/** @return the ONE offset of {@code needle}. Absent and ambiguous are both hard failures rather
	 *          than a best guess: a needle that finds nothing leaves the guard forbidding nothing, and
	 *          one that finds two cannot say which body it delimited. */
	private static int uniqueOffsetOf(String source, String needle) {
		int at = source.indexOf(needle);
		assertTrue(at >= 0, "\"" + needle + "\" was not found in " + RELATIVE_SOURCE + ", so this guard "
				+ "has nothing to compare against and everything below it would be vacuous. Update the "
				+ "needle along with the code it names (issue #256).");
		assertTrue(source.indexOf(needle, at + 1) < 0, "\"" + needle + "\" matched more than once, so "
				+ "this guard cannot say which body it delimited. Narrow it (issue #256).");
		return at;
	}

	/** The brace-delimited body FOLLOWING {@code from}. Comments and string literals are blanked
	 *  first, which is what makes counting braces sound. */
	private static Region bodyOf(String source, int from, String what) {
		int open = source.indexOf('{', from);
		assertTrue(open >= 0, "no opening brace after \"" + what + "\"");
		int depth = 0;
		for (int i = open; i < source.length(); i++) {
			char c = source.charAt(i);
			if (c == '{') {
				depth++;
			}
			else if (c == '}') {
				depth--;
				if (depth == 0) {
					return new Region(open, i);
				}
			}
		}
		throw new AssertionError("braces never balanced while reading " + what + "; the scan cannot say "
				+ "where anything lives, so it must fail rather than guess");
	}

	private static String statementAt(String source, int at) {
		int from = source.lastIndexOf('\n', at) + 1;
		int to = source.indexOf('\n', at);
		return source.substring(from, to < 0 ? source.length() : to).trim();
	}

	private static List<Integer> linesOf(String source, List<Integer> offsets) {
		List<Integer> lines = new ArrayList<Integer>();
		for (int at : offsets) {
			lines.add(lineOf(source, at));
		}
		return lines;
	}

	private static int lineOf(String source, int at) {
		int line = 1;
		for (int i = 0; i < at; i++) {
			if (source.charAt(i) == '\n') {
				line++;
			}
		}
		return line;
	}

	private static final class Region {

		private final int start;

		private final int end;

		Region(int start, int end) {
			this.start = start;
			this.end = end;
		}

		int start() {
			return start;
		}

		boolean contains(int at) {
			return at >= start && at <= end;
		}
	}

	/** {@code DrugSafetyValidator.java} with every comment and string literal blanked to spaces, so
	 *  offsets and line numbers still line up with the file on disk — blanked and not deleted because
	 *  the failure messages report line numbers, and because a call written inside a javadoc is prose
	 *  and not a call. */
	private static String strippedSource() throws IOException {
		Path file = ModuleSourceRoot.apiRoot().resolve(RELATIVE_SOURCE);
		assertTrue(Files.exists(file), "no " + RELATIVE_SOURCE + " under " + ModuleSourceRoot.apiRoot()
				+ "; a guard that reads nothing satisfies every \"is not called here\" assertion by "
				+ "containing nothing");
		char[] text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8).toCharArray();
		assertTrue(text.length > 10000,
			"only " + text.length + " chars were read from " + file + "; a truncated read forbids nothing");
		int i = 0;
		while (i < text.length) {
			char c = text[i];
			if (c == '/' && i + 1 < text.length && text[i + 1] == '/') {
				while (i < text.length && text[i] != '\n') {
					text[i++] = ' ';
				}
			}
			else if (c == '/' && i + 1 < text.length && text[i + 1] == '*') {
				text[i++] = ' ';
				text[i++] = ' ';
				while (i < text.length && !(text[i] == '*' && i + 1 < text.length && text[i + 1] == '/')) {
					if (text[i] != '\n') {
						text[i] = ' ';
					}
					i++;
				}
				if (i < text.length) {
					text[i++] = ' ';
				}
				if (i < text.length) {
					text[i++] = ' ';
				}
			}
			else if (c == '"') {
				text[i++] = ' ';
				while (i < text.length && text[i] != '"') {
					if (text[i] == '\\' && i + 1 < text.length) {
						text[i++] = ' ';
					}
					text[i++] = ' ';
				}
				if (i < text.length) {
					text[i++] = ' ';
				}
			}
			else if (c == '\'') {
				text[i++] = ' ';
				while (i < text.length && text[i] != '\'') {
					if (text[i] == '\\' && i + 1 < text.length) {
						text[i++] = ' ';
					}
					text[i++] = ' ';
				}
				if (i < text.length) {
					text[i++] = ' ';
				}
			}
			else {
				i++;
			}
		}
		return new String(text);
	}
}

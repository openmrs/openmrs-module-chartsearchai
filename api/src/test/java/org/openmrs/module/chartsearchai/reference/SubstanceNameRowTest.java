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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Issue #250 — which row of a substance {@link DrugReference#canonicalRow} elects to represent it.
 * Before this issue the fold had two rungs: prefer the row that {@link DrugReference#namesNoRoute()},
 * and otherwise keep the earliest row seen. Nothing preferred the row whose display name IS the name
 * the data files the family under, so where two rows of one substance both name no route the tie fell
 * to dataset order — and on the shipped KB the row that wins that race is
 * {@code Fluoroestradiol f-18}, a diagnostic PET tracer at index 1282, against {@code Estradiol} at
 * 1927.
 *
 * <p>Reproduced live on a 3.7.1 standalone with the stock shipped dataset: <em>"Is it safe to give her
 * estradiol?"</em> was answered <em>"No — Fluoroestradiol f-18 should not be given: it interacts with
 * active order methylprednisolone …"</em>, over five interaction chips every one of which was subjected
 * on the tracer, with the word {@code estradiol} appearing nowhere except inside
 * {@code Fluoroestradiol}. So the wrong row does not stay in the chip: {@code renderFinding} carries
 * the chip detail into the prompt verbatim and the model answers from it.
 *
 * <p>The MERGE is not what this fixes. {@code Fluoroestradiol f-18} carries no {@code drugbank_id}, so
 * the DDInter parser files it under the estradiol family's resolved substance id — a data defect
 * {@code DrugReferenceValidity.DERIVATIVE_MERGED_WITH_ITS_PARENT_SUBSTANCE} reports and deliberately
 * leaves as loaded, because splitting the rows would invent the fact the data is missing. Given the
 * merge, this is the other half: which of the merged rows speaks for the substance.
 *
 * <p>Both fixtures are verbatim shipped-KB slices read by the real {@link DdiDrugReferenceSource}, and
 * every scenario runs the real {@code validate} entry point with real question strings and GP reads on
 * their no-context defaults.
 */
public class SubstanceNameRowTest {
	
	/** The three estradiol rows the shipped KB merges into one substance, plus the corticosteroid
	 *  partner the live reproduction used. See the fixture's own note. */
	private static final String ESTRADIOL_FIXTURE = "chartsearchai-test/ddi-substance-name-row.json";
	
	/** The question from the live reproduction, verbatim. */
	private static final String ESTRADIOL_QUESTION = "Is it safe to give her estradiol?";
	
	private static final String TRACER = "Fluoroestradiol f-18";
	
	@Test
	public void theFixtureReallyTiesTheFoldOnTheTracer() throws Exception {
		// The premise, through the production predicates: the tracer and Estradiol are ONE substance to
		// this module and neither names a route, so the pre-#250 fold had nothing to separate them and
		// kept the tracer for being listed first. Without this the case could pass on a slice where the
		// fold happened to pick the right row anyway.
		List<DrugReference> entries = DrugReferenceTestSupport.ddiFixtureEntries(ESTRADIOL_FIXTURE);
		DrugReference tracer = DrugReferenceTestSupport.row(entries, TRACER);
		DrugReference estradiol = DrugReferenceTestSupport.row(entries, "Estradiol");
		
		assertEquals(tracer.substanceGroupKey(), estradiol.substanceGroupKey(),
		    "precondition: the two rows must be ONE substance");
		assertTrue(tracer.namesNoRoute() && estradiol.namesNoRoute(),
		    "precondition: neither row may name a route, or the first rung decides it");
		assertSame(tracer, entries.get(0),
		    "precondition: and the tracer must be listed first, as in the shipped file");
		assertEquals(Arrays.asList("Fluoroestradiol f-18", "Estradiol", "Estradiol (topical)"),
		    DrugReferenceTestSupport.names(
		        DrugReferenceTestSupport.serviceWith(entries).findImpliedByQuery(ESTRADIOL_QUESTION)),
		    "precondition: and the question must put every row of the family in play, tracer first");
	}
	
	@Test
	public void theChipNamesTheRowTheDataNamesTheSubstanceAfter() throws Exception {
		DrugReferenceService service = DrugReferenceTestSupport.ddiFixtureService(ESTRADIOL_FIXTURE);
		PatientClinicalContext context = DrugReferenceTestSupport.ctx(60, null,
		    DrugReferenceTestSupport.set("Dexamethasone Injection vial 8mg"), null, null, null);
		
		List<SafetyWarning> warnings = DrugReferenceTestSupport.validator(service)
		        .validate("", ESTRADIOL_QUESTION, context);
		
		assertEquals(1, warnings.size(), "one substance, one partner, one chip, was: " + warnings);
		assertEquals("Estradiol", warnings.get(0).getDrug(),
		    "the chip must name the row the data names the substance after, was: " + warnings);
		assertTrue(warnings.get(0).getDetail()
		        .startsWith("Estradiol interacts with active order dexamethasone — Moderate."),
		    "and its detail must lead with that same name, was: " + warnings.get(0).getDetail());
		assertFalse(warnings.get(0).getDetail().contains(TRACER),
		    "and must not attribute an oestrogen's mechanism to a diagnostic tracer, was: "
		            + warnings.get(0).getDetail());
	}
	
	@Test
	public void theInjectedRecordNamesThatRowToo() throws Exception {
		// The same row, on the surface the model actually cites. The record renders canonicalRow's pick
		// directly (DrugReferenceInjector.matchingEntries), so a fix that moved only the chip would leave
		// the prompt carrying the tracer's title beside a chip naming Estradiol.
		DrugReferenceService service = DrugReferenceTestSupport.ddiFixtureService(ESTRADIOL_FIXTURE);
		PatientClinicalContext context = DrugReferenceTestSupport.ctx(60, null,
		    DrugReferenceTestSupport.set("Dexamethasone Injection vial 8mg"), null, null, null);
		
		List<DrugReference> group = service.findImpliedByQuery(ESTRADIOL_QUESTION);
		assertEquals(3, group.size(), "precondition: the whole family must be in play, was: " + group);
		
		assertEquals("Estradiol", DrugReference.canonicalRow(group).getName(),
		    "the row the record renders must be the one the data names the substance after");
		assertEquals("Estradiol", DrugSafetyValidator.interactionSubject(group, context).getName(),
		    "and the row the chips name it by must be the same one, was: " + group);
	}
}

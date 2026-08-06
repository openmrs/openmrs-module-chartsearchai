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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Exercises the real {@link JsonDrugReferenceSource#load()} path. With no OpenMRS
 * context available it falls back to the bundled {@code /chartsearchai/drug-reference.json}
 * — the production default — so this runs the real load path against the real dataset.
 */
public class JsonDrugReferenceSourceTest {

	@Test
	public void loadsBundledDatasetViaClasspathFallback() {
		JsonDrugReferenceSource source = new JsonDrugReferenceSource();
		List<DrugReference> all = source.load();
		assertFalse(all.isEmpty(), "bundled dataset should load via the classpath fallback");
		assertTrue(all.stream().anyMatch(r -> "ibuprofen".equals(r.getId())),
				"dataset should contain the ibuprofen entry");
		assertEquals("chartsearchai-research-seed-v1", source.lastLoadPackage().toMap().get("id"));
		assertEquals("1.0", source.lastLoadPackage().toMap().get("version"));
		assertEquals(DrugReferencePackage.REVIEW_PROPOSED,
				source.lastLoadPackage().getReviewState());
	}

	@Test
	public void curatedEntriesCarrySafetyRules() {
		// Unlike the ATC classification source, the curated JSON carries the actual
		// contraindication/dosing rules the validator fires on.
		DrugReference ibuprofen = new JsonDrugReferenceSource().load().stream()
				.filter(r -> "ibuprofen".equals(r.getId())).findFirst().orElse(null);
		assertTrue(ibuprofen != null && !ibuprofen.getContraindications().isEmpty(),
				"the curated ibuprofen entry should carry contraindication rules");
	}

	@Test
	public void malformedNestedRulesAreRemovedBeforeValidation() throws IOException {
		String json = "{\"packageId\":\"reviewed-v1\",\"version\":\"1\","
				+ "\"source\":\"test formulary\",\"reviewState\":\"clinically_approved\","
				+ "\"entries\":["
				+ "{\"id\":\"unsafe\",\"name\":\"Unsafe Drug\",\"aliases\":[\"unsafe\"],"
				+ "\"atcCodes\":[\"NOT-ATC\"],\"interactions\":[{\"token\":\"\"},"
				+ "{\"token\":\"warfarin\",\"atc\":\"NOT-ATC\"}]},"
				+ "{\"id\":\"safe\",\"name\":\"Safe Drug\",\"aliases\":[\"safe\"],"
				+ "\"atcCodes\":[\"M01AE01\"]}]}";

		List<DrugReference> entries = JsonDrugReferenceSource.parse(stream(json));
		DrugReferencePackage sourcePackage = JsonDrugReferenceSource.parsePackage(stream(json), "test:memory");

		assertEquals(2, entries.size(), "safe record identity should survive rejected nested content");
		assertEquals("unsafe", entries.get(0).getId());
		assertTrue(entries.get(0).getAtcCodes().isEmpty());
		assertTrue(entries.get(0).getInteractions().isEmpty());
		assertFalse(sourcePackage.isUsableForWarnings());
		assertTrue(sourcePackage.getIssues().contains("source_data_partially_invalid"));
	}

	@Test
	public void invalidDoseBandIsDiagnosedAndRemoved() throws IOException {
		String json = "{\"packageId\":\"reviewed-v1\",\"version\":\"1\","
				+ "\"source\":\"test formulary\",\"reviewState\":\"clinically_approved\","
				+ "\"entries\":[{\"id\":\"ibuprofen\",\"name\":\"Ibuprofen\","
				+ "\"ageBands\":[{\"minYears\":2,\"maxYears\":11,\"maxDailyDoseMg\":-1}]}]}";

		List<DrugReference> entries = JsonDrugReferenceSource.parse(stream(json));
		DrugReferencePackage sourcePackage = JsonDrugReferenceSource.parsePackage(stream(json), "test:memory");

		assertEquals(1, entries.size(), "the drug identity should survive an invalid dose band");
		assertTrue(entries.get(0).getAgeBands().isEmpty());
		assertFalse(sourcePackage.isUsableForWarnings());
		assertTrue(sourcePackage.getIssues().contains("source_data_partially_invalid"));
	}

	@Test
	public void oversizedIntegralAgeBoundsAreDiagnosedInsteadOfNarrowed() throws IOException {
		String json = "{\"packageId\":\"reviewed-v1\",\"version\":\"1\","
				+ "\"source\":\"test formulary\",\"reviewState\":\"clinically_approved\","
				+ "\"entries\":[{\"id\":\"ibuprofen\",\"name\":\"Ibuprofen\","
				+ "\"ageBands\":[{\"minYears\":4294967298,\"maxYears\":4294967307}]}]}";

		List<DrugReference> entries = JsonDrugReferenceSource.parse(stream(json));
		DrugReferencePackage sourcePackage = JsonDrugReferenceSource.parsePackage(stream(json), "test:memory");

		assertEquals(1, entries.size(), "the drug identity should survive an invalid dose band");
		assertTrue(entries.get(0).getAgeBands().isEmpty());
		assertFalse(sourcePackage.isUsableForWarnings());
		assertTrue(sourcePackage.getIssues().contains("source_data_partially_invalid"));
	}

	@Test
	public void malformedInteractionSeverityIsRejectedButAbsentSeverityIsUnrated() throws IOException {
		String json = "{\"packageId\":\"reviewed-v1\",\"version\":\"1\","
				+ "\"source\":\"test formulary\",\"reviewState\":\"clinically_approved\","
				+ "\"entries\":[{\"id\":\"test-drug\",\"name\":\"Test Drug\","
				+ "\"interactions\":["
				+ "{\"token\":\"misspelled\",\"severity\":\"Majro\"},"
				+ "{\"token\":\"numeric\",\"severity\":3},"
				+ "{\"token\":\"blank\",\"severity\":\"\"},"
				+ "{\"token\":\"unrated\"},"
				+ "{\"token\":\"rated\",\"severity\":\"Major\"}]}]}";

		List<DrugReference> entries = JsonDrugReferenceSource.parse(stream(json));
		DrugReferencePackage sourcePackage = JsonDrugReferenceSource.parsePackage(stream(json), "test:memory");

		assertEquals(1, entries.size());
		assertEquals(2, entries.get(0).getInteractions().size());
		assertEquals("unrated", entries.get(0).getInteractions().get(0).getToken());
		assertEquals("rated", entries.get(0).getInteractions().get(1).getToken());
		assertEquals("Major", entries.get(0).getInteractions().get(1).getSeverity());
		assertFalse(sourcePackage.isUsableForWarnings());
		assertTrue(sourcePackage.getIssues().contains("source_data_partially_invalid"));
	}

	@Test
	public void malformedArrayFieldDoesNotDropTheWholeDrugRecord() throws IOException {
		String json = "{\"packageId\":\"reviewed-v1\",\"version\":\"1\","
				+ "\"source\":\"test formulary\",\"reviewState\":\"clinically_approved\","
				+ "\"entries\":[{\"id\":\"ibuprofen\",\"name\":\"Ibuprofen\","
				+ "\"aliases\":\"ibuprofen\",\"atcCodes\":[\"M01AE01\"]}]}";

		List<DrugReference> entries = JsonDrugReferenceSource.parse(stream(json));
		DrugReferencePackage sourcePackage = JsonDrugReferenceSource.parsePackage(stream(json), "test:memory");

		assertEquals(1, entries.size(), "one malformed child field must not erase record identity");
		assertEquals("ibuprofen", entries.get(0).getId());
		assertTrue(entries.get(0).getAliases().isEmpty());
		assertEquals(Collections.singletonList("M01AE01"), entries.get(0).getAtcCodes());
		assertFalse(sourcePackage.isUsableForWarnings());
		assertTrue(sourcePackage.getIssues().contains("source_data_partially_invalid"));
	}

	@Test
	public void approvedPackageWithoutIdentityCannotEmitWarnings() throws IOException {
		String json = "{\"reviewState\":\"clinically_approved\",\"entries\":["
				+ "{\"id\":\"ibuprofen\",\"name\":\"Ibuprofen\",\"aliases\":[\"ibuprofen\"],"
				+ "\"atcCodes\":[\"M01AE01\"],\"ageBands\":["
				+ "{\"minYears\":2,\"maxYears\":11,\"maxDailyDoseMg\":1200}]}]}";
		List<DrugReference> entries = JsonDrugReferenceSource.parse(stream(json));
		DrugReferencePackage sourcePackage = JsonDrugReferenceSource.parsePackage(stream(json), "test:memory");
		DrugReferenceService service = new DrugReferenceService();
		service.setSource(new DrugReferenceSource() {
			@Override
			public List<DrugReference> load() {
				return entries;
			}

			@Override
			public DrugReferencePackage lastLoadPackage() {
				return sourcePackage;
			}
		});
		service.getAll();
		service.setCrossReactivityGroups(Collections.<CrossReactivityGroup> emptyList());
		service.setCrossReactivityPackage(new DrugReferencePackage(
				"reviewed-relationships", "json", "1",
				Collections.<String, Object> singletonMap("source", "test formulary"),
				DrugReferencePackage.REVIEW_CLINICALLY_APPROVED));
		DrugSafetyValidator validator = new DrugSafetyValidator();
		validator.setDrugReferenceService(service);

		DrugSafetyValidator.SafetyCheckResult result = validator.validateWithStatus(
				"Ibuprofen 600 mg every 6 hours can be given for pain.", null,
				DrugReferenceTestSupport.ctx(5, null, null, null, null, null));

		assertEquals(DrugSafetyValidator.STATUS_LIMITED, result.getStatus());
		assertTrue(result.getWarnings().isEmpty());
		assertTrue(((List<?>) result.toMap().get("issues"))
				.contains("source_package_identity_incomplete"));
	}

	private static InputStream stream(String json) {
		return new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8));
	}
}

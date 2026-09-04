/*
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.chartsearchai.reference;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * A curated cross-reactivity group loaded from {@code cross-reactivity-groups.json}:
 * a named family of drugs, expressed as ATC code prefixes, whose members share a
 * clinical cross-reactivity concern that the ATC <em>tree</em> cannot express because
 * the members sit in different branches (the ADR Decision 24 boundary — aspirin
 * {@code N02BA01} vs ibuprofen {@code M01AE01}, both NSAIDs).
 *
 * <p>Groups are data, not code: they are loaded independently of the entry source
 * ({@link JsonDrugReferenceSource} or {@link AtcDrugReferenceSource}) so class-family
 * reasoning works with either. A drug belongs to a group when any of its normalized
 * ATC codes starts with any of the group's normalized prefixes — prefixes may be any
 * ATC level, so a deployment chooses the breadth of each family.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class CrossReactivityGroup {

	private String name;

	private String note;

	private List<String> atcPrefixes = Collections.emptyList();

	public String getName() {
		return name;
	}

	/**
	 * Stores the name TRIMMED, since issue #354 — the sole writer, so a padded operator-authored name
	 * cannot reach any reader.
	 *
	 * <p>At the producer and not at each reader, which is the same placement and the same reason
	 * {@link DrugReference#setAliases} trims: this name is PRINTED by the chip arms
	 * ({@code DrugSafetyValidator}'s cross-reactivity and duplicate-class sentences) and, since #354,
	 * REPORTED as a class by {@code DrugClassTerms}, and those two surfaces co-occur in one response.
	 * Trimming at one reader would have named one family two ways in one answer; trimming at each
	 * reader is the same rule written twice. The loader's own blank check is unaffected — a
	 * whitespace-only name trims to the empty string, which it still rejects.
	 */
	public void setName(String name) {
		this.name = name == null ? null : name.trim();
	}

	public String getNote() {
		return note;
	}

	public void setNote(String note) {
		this.note = note;
	}

	public List<String> getAtcPrefixes() {
		return atcPrefixes;
	}

	public void setAtcPrefixes(List<String> atcPrefixes) {
		this.atcPrefixes = atcPrefixes != null ? atcPrefixes : Collections.<String> emptyList();
	}

	/**
	 * @return this group's ATC prefixes normalized by the one shared rule
	 *         ({@link DrugReference#normalizeAtcTokens}) that also normalizes entry codes,
	 *         so membership tests compare like with like by construction.
	 */
	public Set<String> normalizedAtcPrefixes() {
		return DrugReference.normalizeAtcTokens(atcPrefixes);
	}

	/** @return true when the given normalized (upper-cased) ATC code falls under any of this group's prefixes. */
	public boolean containsCode(String normalizedAtcCode) {
		return fallsUnder(normalizedAtcCode, normalizedAtcPrefixes());
	}

	/**
	 * @return true when any of the given normalized ATC codes falls under any of this group's
	 *         prefixes.
	 *
	 *         <p>Normalizes this group's prefixes ONCE per call rather than once per code:
	 *         {@link #normalizedAtcPrefixes()} rebuilds the set every time it is asked, and this
	 *         runs inside {@code DrugSafetyValidator}'s per-pair screening across the candidate
	 *         set, so asking per code cost one normalization per (group, code) pair (issue #230).
	 *         No speedup was measured and none is claimed: the shipped file carries one group with
	 *         two prefixes. This is about not carrying a shape that scales with (groups × codes)
	 *         into a deployment that expands the curated groups, which is what that path is for
	 *         (#183).
	 *
	 *         <p>The normalized set is held in a LOCAL and never in a field. {@link #setAtcPrefixes} is
	 *         the write path — Jackson calls it, and so does anything wiring groups programmatically —
	 *         and it has to stay authoritative AFTER a membership question has been asked, which a set
	 *         cached on the instance would not: it would go on answering with the prefixes the group
	 *         used to carry, and silently, because no other test REPLACES a group's prefixes after
	 *         asking. (Being asked twice is not the trigger and is entirely ordinary:
	 *         {@link CrossReactivityGroupsLoader} asks every group it keeps for its prefixes once, as
	 *         its own drop filter, before any caller does.) Same rule as issue #172's "in a local,
	 *         never in a field", pinned by
	 *         {@code CrossReactivityGroupsTest#replacedPrefixesAreSeenOnTheNextQuestion_soTheNormalizationIsNeverCachedOnTheInstance}.
	 */
	public boolean containsAnyCode(Set<String> normalizedAtcCodes) {
		Set<String> prefixes = normalizedAtcPrefixes();
		for (String code : normalizedAtcCodes) {
			if (fallsUnder(code, prefixes)) {
				return true;
			}
		}
		return false;
	}

	/** The one membership rule both accessors above answer with, so the two cannot drift apart.
	 *  Deliberately NOT shared with {@code DrugReference}'s private {@code fallsUnderAnyGroup}, which
	 *  runs the same loop over the hardcoded ATC group constants on that side: the two sides answer
	 *  for different data (curated prefixes loaded from a file here; compiled-in group constants
	 *  there) and each says so in its own javadoc. Merging them would put one refinement in front of
	 *  both. */
	private static boolean fallsUnder(String normalizedAtcCode, Set<String> normalizedPrefixes) {
		if (normalizedAtcCode == null || normalizedAtcCode.isEmpty()) {
			return false;
		}
		for (String prefix : normalizedPrefixes) {
			if (normalizedAtcCode.startsWith(prefix)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * @return the groups (of {@code all}) that {@code ref} belongs to, in dataset order.
	 *         The shared membership definition used by both the safety validator and the
	 *         injector's order-relevance scoping, so "in the same group" means one thing.
	 */
	public static List<CrossReactivityGroup> groupsOf(DrugReference ref, List<CrossReactivityGroup> all) {
		List<CrossReactivityGroup> out = new ArrayList<CrossReactivityGroup>();
		Set<String> codes = ref.normalizedAtcCodes();
		if (codes.isEmpty()) {
			return out;
		}
		for (CrossReactivityGroup group : all) {
			if (group.containsAnyCode(codes)) {
				out.add(group);
			}
		}
		return out;
	}

	/**
	 * @return the first of {@code refGroups} that {@code other} is also a member of, or
	 *         {@code null} when the two drugs share no curated group.
	 */
	public static CrossReactivityGroup sharedGroup(List<CrossReactivityGroup> refGroups, DrugReference other) {
		return sharedGroupForCodes(refGroups, other.normalizedAtcCodes());
	}

	/**
	 * @return the first of {@code refGroups} that any of the (normalized) ATC codes
	 *         {@code atcCodes} falls under, or {@code null}. The order-code variant of
	 *         {@link #sharedGroup(List, DrugReference)} for active orders whose substance
	 *         may not be present in the loaded dataset — a SET, because one order's concept can map
	 *         to several codes and they are one co-medication, not several (issue #171).
	 *
	 *         <p>Scanning the groups rather than the codes is what makes the answer independent of the
	 *         order the codes arrive in: the groups are curated data in dataset order, the codes are
	 *         whatever a concept dictionary happened to publish.
	 */
	public static CrossReactivityGroup sharedGroupForCodes(List<CrossReactivityGroup> refGroups,
			Set<String> atcCodes) {
		if (atcCodes.isEmpty()) {
			return null;
		}
		for (CrossReactivityGroup group : refGroups) {
			if (group.containsAnyCode(atcCodes)) {
				return group;
			}
		}
		return null;
	}
}

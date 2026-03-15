/**
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.chartsearchai.serializer;

import java.util.List;

import org.openmrs.Allergy;
import org.openmrs.AllergyReaction;
import org.springframework.stereotype.Component;

/**
 * Serializes an {@link Allergy} into embedding-friendly text.
 *
 * <p>Example output: {@code "Allergy: Penicillin (DRUG). Date: 2024-03-15. Severity: Severe.
 * Reactions: Anaphylaxis, Rash"}</p>
 */
@Component
public class AllergyTextSerializer implements ClinicalTextSerializer<Allergy> {

	@Override
	public String toText(Allergy allergy) {
		StringBuilder sb = new StringBuilder();
		String name = getAllergenName(allergy);
		if (!name.isEmpty()) {
			sb.append("Allergy: ").append(name);
		}

		if (allergy.getAllergen().getAllergenType() != null) {
			sb.append(" (").append(allergy.getAllergen().getAllergenType()).append(")");
		}
		if (allergy.getDateCreated() != null) {
			sb.append(". Date: ").append(DateFormatUtil.formatDate(allergy.getDateCreated()));
		}
		if (allergy.getSeverity() != null) {
			sb.append(". Severity: ").append(ConceptNameUtil.getName(allergy.getSeverity()));
		}

		List<AllergyReaction> reactions = allergy.getReactions();
		if (reactions != null && !reactions.isEmpty()) {
			sb.append(". Reactions: ");
			for (int i = 0; i < reactions.size(); i++) {
				if (i > 0) {
					sb.append(", ");
				}
				AllergyReaction reaction = reactions.get(i);
				if (reaction.getReaction() != null) {
					sb.append(ConceptNameUtil.getName(reaction.getReaction()));
				} else if (reaction.getReactionNonCoded() != null
						&& !reaction.getReactionNonCoded().trim().isEmpty()) {
					sb.append(reaction.getReactionNonCoded().trim());
				}
			}
		}
		if (allergy.getComments() != null && !allergy.getComments().trim().isEmpty()) {
			sb.append(". Comments: ").append(allergy.getComments().trim());
		}

		return sb.toString();
	}

	private String getAllergenName(Allergy allergy) {
		if (allergy.getAllergen() == null) {
			return "";
		}
		if (allergy.getAllergen().getCodedAllergen() != null) {
			return ConceptNameUtil.getName(allergy.getAllergen().getCodedAllergen());
		}
		if (allergy.getAllergen().getNonCodedAllergen() != null
				&& !allergy.getAllergen().getNonCodedAllergen().trim().isEmpty()) {
			return allergy.getAllergen().getNonCodedAllergen().trim();
		}
		return allergy.getAllergen().toString();
	}
}

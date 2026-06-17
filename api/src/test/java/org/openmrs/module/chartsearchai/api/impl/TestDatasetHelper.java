/**
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.chartsearchai.api.impl;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.openmrs.module.chartsearchai.ChartSearchAiConstants;
import org.openmrs.module.chartsearchai.serializer.SerializedRecord;

/**
 * Shared helpers for tests that use the {@code FULL_PATIENT_DATASET} test
 * dataset. Consolidates the dataset-parsing logic that was previously
 * duplicated across multiple test files.
 */
final class TestDatasetHelper {

	// Full 153-record dataset from a real 16-year-old Male patient chart.
	static final String[] FULL_PATIENT_DATASET = {
			/* [  1] */ "Medication prescription: (2026-03-18) Drug order: Azithromycin. Dose: 2.0 Tablet) Intravenous Every six hours. Duration: 5 Days. Quantity: 4.0 Tablet). As needed (subject to heart attack). Dosing: Take after eating. Action: REVISE. Urgency: ROUTINE. Reason: Spectrum",
			/* [  2] */ "Medication prescription: (2026-03-18) Drug order: Azithromycin. Dose: 2.0 Tablet) Intravenous Thrice daily. Duration: 5 Days. Quantity: 4.0 Tablet). As needed (subject to heart attack). Dosing: Take after eating. Action: NEW. Urgency: ROUTINE. Reason: Spectrum. Stopped: 2026-03-18",
			/* [  3] */ "Program enrollment: (2026-03-18) Program: PMTCT. Enrolled: 2026-03-18. Status: Active",
			/* [  4] */ "Clinical observation: (2026-03-18) Finding — Immunization history: Immunizations: Polio vaccination, oral, Oral polio vacc, Polio immunization, oral); Vaccination date: 2026-03-17; Immunization sequence number: 1.0",
			/* [  5] */ "Patient allergy: (2026-03-18) Allergy: Beef (food allergen). Severity: Severe. Reactions: Diarrhea, Itching. Comments: Happens during pregnancy",
			/* [  6] */ "Clinical observation: (2026-03-05) Assessment — Method of family planning: Condoms",
			/* [  7] */ "Clinical observation: (2026-03-05) Assessment — Method of family planning: Diaphragm. Note: in bathroom",
			/* [  8] */ "Medical condition: (2025-11-12) Condition: Tuberculosis. Status: ACTIVE",
			/* [  9] */ "Clinical observation: (2025-10-30) Test — CD4 Count: 988.0 cells/mmL",
			/* [ 10] */ "Clinical observation: (2025-10-30) Drug — Pyrimethamine / sulfadoxine: 11.58",
			/* [ 11] */ "Clinical observation: (2025-10-30) Units of Measure — Syringe): 65.0",
			/* [ 12] */ "Clinical observation: (2025-10-30) Diagnosis — Kaposi sarcoma oral: 3.91",
			/* [ 13] */ "Clinical observation: (2025-10-30) Assessment — Primary Diagnosis: Tuberculosis",
			/* [ 14] */ "Clinical observation: (2025-10-30) Frequency — Every twenty-four hours: Every four hours",
			/* [ 15] */ "Clinical observation: (2025-10-30) Test — Height (cm): 131.0 cm",
			/* [ 16] */ "Clinical observation: (2025-10-30) Test — Respiratory Rate: 18.0 breaths/min",
			/* [ 17] */ "Clinical observation: (2025-10-30) Test — Pulse: 95.0 beats/min",
			/* [ 18] */ "Clinical observation: (2025-10-30) Test — Temperature (C): 36.7 DEG C",
			/* [ 19] */ "Clinical observation: (2025-10-30) Test — Weight (kg): 94.0 kg",
			/* [ 20] */ "Clinical observation: (2025-09-17) Diagnosis — Fetishism: Patient presents with mild symptoms. Advised rest and fluids.",
			/* [ 21] */ "Clinical observation: (2025-09-17) Frequency — Every twenty-four hours: Every eight hours",
			/* [ 22] */ "Clinical diagnosis: (2025-09-17) Diagnosis: Gastroenteritis. Certainty: PROVISIONAL. Rank: Primary",
			/* [ 23] */ "Clinical observation: (2025-09-17) Test — Systolic Blood Pressure: 97.0 mmHg",
			/* [ 24] */ "Clinical observation: (2025-09-17) Test — Diastolic Blood Pressure: 99.0 mmHg",
			/* [ 25] */ "Clinical observation: (2025-09-17) Test — Pulse: 62.0 beats/min",
			/* [ 26] */ "Clinical observation: (2025-09-17) Test — Temperature (C): 37.7 DEG C",
			/* [ 27] */ "Clinical observation: (2025-09-17) Test — Weight (kg): 107.0 kg",
			/* [ 28] */ "Clinical observation: (2025-09-17) Test — Height (cm): 137.0 cm",
			/* [ 29] */ "Clinical observation: (2025-09-17) Test — Respiratory Rate: 24.0 breaths/min",
			/* [ 30] */ "Clinical observation: (2025-09-17) Assessment — Primary Diagnosis: Anemia",
			/* [ 31] */ "Clinical observation: (2025-09-17) Frequency — Every twenty-four hours: Every five hours",
			/* [ 32] */ "Clinical observation: (2025-09-17) Test — Diastolic Blood Pressure: 92.0 mmHg",
			/* [ 33] */ "Clinical observation: (2025-09-17) Test — Respiratory Rate: 32.0 breaths/min",
			/* [ 34] */ "Clinical observation: (2025-09-17) Test — Weight (kg): 139.0 kg",
			/* [ 35] */ "Clinical observation: (2025-09-17) Test — Blood Oxygen Saturation: 88.0 %",
			/* [ 36] */ "Clinical observation: (2025-06-29) Diagnosis — Fetishism: Annual physical examination. Labs ordered.",
			/* [ 37] */ "Clinical observation: (2025-06-29) Test — Systolic Blood Pressure: 122.0 mmHg",
			/* [ 38] */ "Clinical observation: (2025-06-29) Test — Height (cm): 103.0 cm",
			/* [ 39] */ "Clinical observation: (2025-06-29) Test — Temperature (C): 40.3 DEG C",
			/* [ 40] */ "Clinical diagnosis: (2025-06-29) Diagnosis: HIV Disease. Certainty: CONFIRMED. Rank: Primary",
			/* [ 41] */ "Clinical observation: (2025-06-28) Assessment — Primary Diagnosis: HIV Disease",
			/* [ 42] */ "Clinical observation: (2025-06-28) Diagnosis — Fetishism: Patient stable on current regimen.",
			/* [ 43] */ "Clinical observation: (2025-06-28) Frequency — Every twenty-four hours: Married",
			/* [ 44] */ "Clinical observation: (2025-06-28) Test — Pulse: 62.0 beats/min",
			/* [ 45] */ "Clinical observation: (2025-06-28) Test — Respiratory Rate: 23.0 breaths/min",
			/* [ 46] */ "Clinical observation: (2025-06-28) Test — Blood Oxygen Saturation: 92.0 %",
			/* [ 47] */ "Clinical observation: (2025-06-28) Test — Height (cm): 126.0 cm",
			/* [ 48] */ "Clinical observation: (2025-06-28) Test — Systolic Blood Pressure: 101.0 mmHg",
			/* [ 49] */ "Clinical observation: (2025-06-28) Test — Diastolic Blood Pressure: 99.0 mmHg",
			/* [ 50] */ "Clinical observation: (2025-06-28) Assessment — Primary Diagnosis: Diabetes Mellitus",
			/* [ 51] */ "Clinical observation: (2025-06-28) Diagnosis — Fetishism: New complaint of persistent cough for 2 weeks.",
			/* [ 52] */ "Clinical diagnosis: (2025-06-28) Diagnosis: Urinary Tract Infection. Certainty: PROVISIONAL. Rank: Primary",
			/* [ 53] */ "Clinical diagnosis: (2025-06-28) Diagnosis: Tuberculosis. Certainty: CONFIRMED. Rank: Secondary",
			/* [ 54] */ "Patient allergy: (2024-12-29) Allergy: Fomepizole (drug allergen)",
			/* [ 55] */ "Medical condition: (2024-09-08) Condition: Hypertension. Status: ACTIVE",
			/* [ 56] */ "Clinical observation: (2023-04-30) Assessment — Primary Diagnosis: Anemia",
			/* [ 57] */ "Clinical observation: (2023-04-30) Diagnosis — Fetishism: Chronic disease management visit. Medication adjusted.",
			/* [ 58] */ "Clinical observation: (2023-04-30) Test — Height (cm): 101.0 cm",
			/* [ 59] */ "Clinical observation: (2023-04-30) Test — Systolic Blood Pressure: 123.0 mmHg",
			/* [ 60] */ "Clinical observation: (2023-04-30) Test — Blood Oxygen Saturation: 86.0 %",
			/* [ 61] */ "Clinical observation: (2023-04-30) Test — Pulse: 51.0 beats/min",
			/* [ 62] */ "Clinical diagnosis: (2023-04-30) Diagnosis: Skin Infection. Certainty: CONFIRMED. Rank: Primary",
			/* [ 63] */ "Clinical observation: (2023-04-29) Assessment — Primary Diagnosis: Pneumonia",
			/* [ 64] */ "Clinical observation: (2023-04-29) Test — Weight (kg): 38.0 kg",
			/* [ 65] */ "Clinical observation: (2023-04-29) Test — Diastolic Blood Pressure: 71.0 mmHg",
			/* [ 66] */ "Clinical observation: (2023-04-29) Test — Respiratory Rate: 16.0 breaths/min",
			/* [ 67] */ "Clinical diagnosis: (2023-04-29) Diagnosis: Malaria. Certainty: PROVISIONAL. Rank: Primary",
			/* [ 68] */ "Clinical diagnosis: (2023-04-29) Diagnosis: Diabetes Mellitus. Certainty: CONFIRMED. Rank: Secondary",
			/* [ 69] */ "Clinical observation: (2022-11-09) Assessment — Primary Diagnosis: HIV Disease",
			/* [ 70] */ "Clinical diagnosis: (2022-11-09) Diagnosis: HIV Disease. Certainty: PROVISIONAL. Rank: Primary",
			/* [ 71] */ "Clinical observation: (2022-10-07) Diagnosis — Fetishism: Presenting with fever and body aches for 3 days.",
			/* [ 72] */ "Clinical diagnosis: (2022-10-07) Diagnosis: HIV Disease. Certainty: CONFIRMED. Rank: Primary",
			/* [ 73] */ "Clinical diagnosis: (2022-10-07) Diagnosis: Anemia. Certainty: PROVISIONAL. Rank: Secondary",
			/* [ 74] */ "Clinical observation: (2022-10-07) Test — Systolic Blood Pressure: 137.0 mmHg",
			/* [ 75] */ "Clinical observation: (2022-10-07) Test — Diastolic Blood Pressure: 67.0 mmHg",
			/* [ 76] */ "Clinical observation: (2022-10-07) Test — Pulse: 86.0 beats/min",
			/* [ 77] */ "Clinical observation: (2022-10-07) Test — Temperature (C): 39.3 DEG C",
			/* [ 78] */ "Clinical observation: (2022-10-07) Test — Weight (kg): 146.0 kg",
			/* [ 79] */ "Clinical observation: (2022-10-07) Test — Height (cm): 107.0 cm",
			/* [ 80] */ "Clinical observation: (2022-10-07) Test — Blood Oxygen Saturation: 86.0 %",
			/* [ 81] */ "Clinical observation: (2022-10-07) Test — Respiratory Rate: 30.0 breaths/min",
			/* [ 82] */ "Clinical observation: (2022-04-27) Test — Diastolic Blood Pressure: 105.0 mmHg",
			/* [ 83] */ "Clinical observation: (2022-04-27) Test — Pulse: 70.0 beats/min",
			/* [ 84] */ "Clinical observation: (2022-04-27) Test — Blood Oxygen Saturation: 100.0 %",
			/* [ 85] */ "Clinical observation: (2022-04-27) Test — Respiratory Rate: 40.0 breaths/min",
			/* [ 86] */ "Clinical observation: (2022-04-27) Test — CD4 Count: 1191.0 cells/mmL",
			/* [ 87] */ "Clinical observation: (2022-04-27) Units of Measure — Syringe): 339.0",
			/* [ 88] */ "Clinical observation: (2022-04-27) Misc — Milligram per meter squared: 47.0",
			/* [ 89] */ "Clinical observation: (2022-04-27) Diagnosis — Kaposi sarcoma oral: 3.5",
			/* [ 90] */ "Clinical observation: (2022-04-27) Diagnosis — Photoallergy: 9.93",
			/* [ 91] */ "Clinical observation: (2022-01-16) Assessment — Primary Diagnosis: Urinary Tract Infection",
			/* [ 92] */ "Clinical observation: (2022-01-16) Diagnosis — Fetishism: Chronic disease management visit. Medication adjusted.",
			/* [ 93] */ "Clinical diagnosis: (2022-01-16) Diagnosis: Hypertension. Certainty: PROVISIONAL. Rank: Primary",
			/* [ 94] */ "Clinical observation: (2022-01-16) Test — Systolic Blood Pressure: 147.0 mmHg",
			/* [ 95] */ "Clinical observation: (2022-01-16) Test — Diastolic Blood Pressure: 58.0 mmHg",
			/* [ 96] */ "Clinical observation: (2022-01-16) Test — Pulse: 53.0 beats/min",
			/* [ 97] */ "Clinical observation: (2022-01-16) Test — Temperature (C): 36.4 DEG C",
			/* [ 98] */ "Clinical observation: (2022-01-16) Test — Height (cm): 163.0 cm",
			/* [ 99] */ "Clinical observation: (2022-01-16) Test — Respiratory Rate: 19.0 breaths/min",
			/* [100] */ "Clinical observation: (2022-01-16) Assessment — Primary Diagnosis: Headache",
			/* [101] */ "Clinical observation: (2022-01-16) Test — Pulse: 83.0 beats/min",
			/* [102] */ "Clinical observation: (2022-01-16) Test — Weight (kg): 68.0 kg",
			/* [103] */ "Clinical observation: (2022-01-16) Test — Diastolic Blood Pressure: 50.0 mmHg",
			/* [104] */ "Clinical observation: (2022-01-16) Test — Blood Oxygen Saturation: 94.0 %",
			/* [105] */ "Clinical observation: (2022-01-16) Test — Respiratory Rate: 40.0 breaths/min",
			/* [106] */ "Clinical observation: (2021-11-30) Assessment — Primary Diagnosis: Malaria",
			/* [107] */ "Clinical observation: (2021-11-30) Diagnosis — Fetishism: Well-child visit. Growth and development normal.",
			/* [108] */ "Clinical observation: (2021-11-30) Test — Diastolic Blood Pressure: 93.0 mmHg",
			/* [109] */ "Clinical observation: (2021-11-30) Test — Blood Oxygen Saturation: 95.0 %",
			/* [110] */ "Clinical observation: (2021-11-30) Test — Respiratory Rate: 36.0 breaths/min",
			/* [111] */ "Clinical diagnosis: (2021-11-30) Diagnosis: HIV Disease. Certainty: CONFIRMED. Rank: Primary",
			/* [112] */ "Clinical observation: (2021-11-30) Test — Systolic Blood Pressure: 98.0 mmHg",
			/* [113] */ "Clinical observation: (2021-11-30) Test — Pulse: 66.0 beats/min",
			/* [114] */ "Clinical observation: (2021-11-30) Test — Temperature (C): 37.8 DEG C",
			/* [115] */ "Clinical observation: (2021-11-30) Test — Weight (kg): 121.0 kg",
			/* [116] */ "Clinical observation: (2021-11-30) Test — Height (cm): 173.0 cm",
			/* [117] */ "Clinical observation: (2021-11-30) Test — Blood Oxygen Saturation: 94.0 %",
			/* [118] */ "Clinical observation: (2021-11-30) Test — Respiratory Rate: 29.0 breaths/min",
			/* [119] */ "Clinical observation: (2021-11-30) Assessment — Primary Diagnosis: Urinary Tract Infection",
			/* [120] */ "Clinical observation: (2021-11-30) Diagnosis — Fetishism: Presenting with fever and body aches for 3 days.",
			/* [121] */ "Clinical observation: (2021-09-16) Diagnosis — Fetishism: Patient counseled on lifestyle modifications.",
			/* [122] */ "Clinical observation: (2021-09-16) Frequency — Every twenty-four hours: Every four hours",
			/* [123] */ "Clinical observation: (2021-09-16) Assessment — Primary Diagnosis: Skin Infection",
			/* [124] */ "Clinical observation: (2021-09-16) Diagnosis — Fetishism: Annual physical examination. Labs ordered.",
			/* [125] */ "Clinical observation: (2021-09-16) Test — Height (cm): 137.0 cm",
			/* [126] */ "Clinical observation: (2021-09-16) Test — Respiratory Rate: 28.0 breaths/min",
			/* [127] */ "Clinical observation: (2021-09-16) Test — Systolic Blood Pressure: 134.0 mmHg",
			/* [128] */ "Clinical diagnosis: (2021-09-16) Diagnosis: Asthma. Certainty: CONFIRMED. Rank: Primary",
			/* [129] */ "Clinical observation: (2021-09-16) Test — Systolic Blood Pressure: 117.0 mmHg",
			/* [130] */ "Clinical observation: (2021-09-16) Test — Diastolic Blood Pressure: 70.0 mmHg",
			/* [131] */ "Clinical observation: (2021-09-16) Test — Pulse: 115.0 beats/min",
			/* [132] */ "Clinical observation: (2021-09-16) Test — Temperature (C): 40.1 DEG C",
			/* [133] */ "Clinical observation: (2021-09-16) Test — Height (cm): 186.0 cm",
			/* [134] */ "Clinical observation: (2021-09-16) Test — Respiratory Rate: 22.0 breaths/min",
			/* [135] */ "Clinical observation: (2021-07-20) Assessment — Primary Diagnosis: Tuberculosis",
			/* [136] */ "Clinical observation: (2021-07-20) Assessment — Primary Diagnosis: Tuberculosis",
			/* [137] */ "Clinical observation: (2021-07-20) Test — Respiratory Rate: 28.0 breaths/min",
			/* [138] */ "Clinical observation: (2021-07-20) Test — Diastolic Blood Pressure: 76.0 mmHg",
			/* [139] */ "Clinical observation: (2021-07-20) Test — Systolic Blood Pressure: 102.0 mmHg",
			/* [140] */ "Clinical observation: (2021-07-20) Units of Measure — Syringe): 237.0",
			/* [141] */ "Clinical observation: (2021-07-20) Misc — Milligram per meter squared: 17.0",
			/* [142] */ "Clinical observation: (2021-07-20) Diagnosis — Photoallergy: 8.27",
			/* [143] */ "Clinical observation: (2020-09-26) Diagnosis — Fetishism: Routine checkup. No significant findings.",
			/* [144] */ "Clinical observation: (2020-09-26) Test — Temperature (C): 39.3 DEG C",
			/* [145] */ "Clinical observation: (2020-09-26) Test — Diastolic Blood Pressure: 78.0 mmHg",
			/* [146] */ "Clinical observation: (2020-09-26) Test — Blood Oxygen Saturation: 88.0 %",
			/* [147] */ "Clinical diagnosis: (2020-09-26) Diagnosis: Diabetes Mellitus. Certainty: PROVISIONAL. Rank: Primary",
			/* [148] */ "Clinical observation: (2020-09-26) Test — Systolic Blood Pressure: 151.0 mmHg",
			/* [149] */ "Clinical observation: (2020-09-26) Test — Diastolic Blood Pressure: 53.0 mmHg",
			/* [150] */ "Clinical observation: (2020-09-26) Test — Pulse: 117.0 beats/min",
			/* [151] */ "Clinical observation: (2020-09-26) Test — Temperature (C): 39.4 DEG C",
			/* [152] */ "Clinical observation: (2020-09-26) Test — Blood Oxygen Saturation: 88.0 %",
			/* [153] */ "Clinical observation: (2020-09-26) Test — Respiratory Rate: 15.0 breaths/min",
	};

	// Second 67-record dataset from a 4-year-old Male patient chart.
	static final String[] SECOND_PATIENT_DATASET = {
			/* [ 1] */ "Clinical observation: Assessment — Text of encounter note: Est sit amet facilisis magna etiam tempor. Sed vulputate odio ut enim blandit volutpat. Eget est lorem ipsum dolor sit amet. Arcu vitae elementum curabitur vitae nunc sed velit. Venenatis cras sed felis eget velit.",
			/* [ 2] */ "Medical condition: Condition: Nonparalytic stroke. Status: ACTIVE",
			/* [ 3] */ "Medical condition: Condition: Scarring Alopecia. Status: ACTIVE",
			/* [ 4] */ "Clinical diagnosis: Diagnosis: Nonparalytic stroke. Certainty: PROVISIONAL. Rank: Primary",
			/* [ 5] */ "Clinical diagnosis: Diagnosis: Scarring Alopecia. Certainty: CONFIRMED. Rank: Primary",
			/* [ 6] */ "Clinical observation: Finding — Temperature (c)): 38.9 (CRITICALLY_HIGH)",
			/* [ 7] */ "Clinical observation: Finding — Systolic blood pressure: 133.0 (HIGH)",
			/* [ 8] */ "Clinical observation: Finding — Diastolic blood pressure: 57.0 (NORMAL)",
			/* [ 9] */ "Clinical observation: Finding — Pulse: 100.0 (NORMAL)",
			/* [10] */ "Clinical observation: Finding — Arterial blood oxygen saturation (pulse oximeter): 96.6",
			/* [11] */ "Clinical observation: Finding — Weight (kg): 68.0",
			/* [12] */ "Clinical observation: Finding — Respiratory rate: 17.0 (CRITICALLY_LOW)",
			/* [13] */ "Clinical observation: Finding — Height (cm): 168.0",
			/* [14] */ "Clinical observation: Test — High-density lipoprotein cholesterol measurement (mmol/L), High-density lipoprotein cholesterol): 96.0",
			/* [15] */ "Lab test order: Test order: High-density lipoprotein cholesterol measurement (mmol/L), High-density lipoprotein cholesterol). Action: NEW. Urgency: ROUTINE",
			/* [16] */ "Clinical observation: Assessment — Text of encounter note: Neque convallis a cras semper auctor neque vitae. Proin nibh nisl condimentum id venenatis a condimentum vitae. Viverra accumsan in nisl nisi scelerisque eu ultrices vitae. Ac tincidunt vitae semper quis lectus nulla.",
			/* [17] */ "Medical condition: Condition: Granuloma annulare. Status: ACTIVE",
			/* [18] */ "Medical condition: Condition: Syphilitic Cirrhosis. Status: ACTIVE",
			/* [19] */ "Clinical diagnosis: Diagnosis: Granuloma annulare. Certainty: PROVISIONAL. Rank: Primary",
			/* [20] */ "Clinical diagnosis: Diagnosis: Syphilitic Cirrhosis. Certainty: CONFIRMED. Rank: Primary",
			/* [21] */ "Clinical observation: Finding — Temperature (c)): 37.2 (NORMAL)",
			/* [22] */ "Clinical observation: Finding — Systolic blood pressure: 104.0 (NORMAL)",
			/* [23] */ "Clinical observation: Finding — Diastolic blood pressure: 52.0 (NORMAL)",
			/* [24] */ "Clinical observation: Finding — Pulse: 108.0 (NORMAL)",
			/* [25] */ "Clinical observation: Finding — Arterial blood oxygen saturation (pulse oximeter): 93.0 (LOW)",
			/* [26] */ "Clinical observation: Finding — Weight (kg): 49.0",
			/* [27] */ "Clinical observation: Finding — Respiratory rate: 22.0 (NORMAL)",
			/* [28] */ "Clinical observation: Finding — Height (cm): 186.0",
			/* [29] */ "Clinical observation: Assessment — Text of encounter note: Faucibus et molestie ac feugiat sed lectus. Condimentum lacinia quis vel eros donec ac. Urna porttitor rhoncus dolor purus.",
			/* [30] */ "Medical condition: Condition: Atherosclerosis. Status: ACTIVE",
			/* [31] */ "Medical condition: Condition: Complete tear of ligament of ankle or foot. Status: ACTIVE",
			/* [32] */ "Medical condition: Condition: Mild depressive episode. Status: ACTIVE",
			/* [33] */ "Clinical diagnosis: Diagnosis: Atherosclerosis. Certainty: PROVISIONAL. Rank: Primary",
			/* [34] */ "Clinical diagnosis: Diagnosis: Complete tear of ligament of ankle or foot. Certainty: CONFIRMED. Rank: Primary",
			/* [35] */ "Clinical diagnosis: Diagnosis: Mild depressive episode. Certainty: CONFIRMED. Rank: Primary",
			/* [36] */ "Clinical observation: Finding — Temperature (c)): 38.7 (CRITICALLY_HIGH)",
			/* [37] */ "Clinical observation: Finding — Systolic blood pressure: 105.0 (NORMAL)",
			/* [38] */ "Clinical observation: Finding — Diastolic blood pressure: 60.0 (NORMAL)",
			/* [39] */ "Clinical observation: Finding — Pulse: 65.0 (CRITICALLY_LOW)",
			/* [40] */ "Clinical observation: Finding — Arterial blood oxygen saturation (pulse oximeter): 99.6",
			/* [41] */ "Clinical observation: Finding — Weight (kg): 72.0",
			/* [42] */ "Clinical observation: Finding — Respiratory rate: 9.0 (CRITICALLY_LOW)",
			/* [43] */ "Clinical observation: Finding — Height (cm): 186.0",
			/* [44] */ "Clinical observation: Assessment — Text of encounter note: Est sit amet facilisis magna etiam tempor. Sed vulputate odio ut enim blandit volutpat. Eget est lorem ipsum dolor sit amet. Arcu vitae elementum curabitur vitae nunc sed velit. Venenatis cras sed felis eget velit.",
			/* [45] */ "Medical condition: Condition: Female infertility. Status: ACTIVE",
			/* [46] */ "Clinical diagnosis: Diagnosis: Female infertility. Certainty: PROVISIONAL. Rank: Primary",
			/* [47] */ "Clinical observation: Finding — Temperature (c)): 36.0 (NORMAL)",
			/* [48] */ "Clinical observation: Finding — Systolic blood pressure: 128.0 (HIGH)",
			/* [49] */ "Clinical observation: Finding — Diastolic blood pressure: 86.0 (HIGH)",
			/* [50] */ "Clinical observation: Finding — Pulse: 70.0 (LOW)",
			/* [51] */ "Clinical observation: Finding — Arterial blood oxygen saturation (pulse oximeter): 89.3 (CRITICALLY_LOW)",
			/* [52] */ "Clinical observation: Finding — Weight (kg): 50.0",
			/* [53] */ "Clinical observation: Finding — Respiratory rate: 16.0 (CRITICALLY_LOW)",
			/* [54] */ "Clinical observation: Finding — Height (cm): 185.0",
			/* [55] */ "Clinical observation: Assessment — Text of encounter note: Lorem ipsum dolor sit amet, consectetur adipiscing elit, sed do eiusmod tempor incididunt ut labore et dolore magna aliqua. Vulputate enim nulla aliquet porttitor. Fermentum iaculis eu non diam phasellus vestibulum lorem sed. Orci ac auctor augue mauris augue neque. Fames ac turpis egestas sed tempus urna. Sit amet justo donec enim diam vulputate. Tortor aliquam nulla facilisi cras fermentum. Aliquet eget sit amet tellus. Elit ullamcorper dignissim cras tincidunt lobortis feugiat. Nisl tincidunt eget nullam non nisi est. Volutpat maecenas volutpat blandit aliquam etiam erat.",
			/* [56] */ "Medical condition: Condition: Personal history of blood transfusion. Status: ACTIVE",
			/* [57] */ "Medical condition: Condition: Chronic fatigue. Status: ACTIVE",
			/* [58] */ "Clinical diagnosis: Diagnosis: Personal history of blood transfusion. Certainty: PROVISIONAL. Rank: Primary",
			/* [59] */ "Clinical diagnosis: Diagnosis: Chronic fatigue. Certainty: CONFIRMED. Rank: Primary",
			/* [60] */ "Clinical observation: Finding — Temperature (c)): 36.5 (NORMAL)",
			/* [61] */ "Clinical observation: Finding — Systolic blood pressure: 131.0 (HIGH)",
			/* [62] */ "Clinical observation: Finding — Diastolic blood pressure: 60.0 (NORMAL)",
			/* [63] */ "Clinical observation: Finding — Pulse: 63.0 (CRITICALLY_LOW)",
			/* [64] */ "Clinical observation: Finding — Arterial blood oxygen saturation (pulse oximeter): 89.7 (CRITICALLY_LOW)",
			/* [65] */ "Clinical observation: Finding — Weight (kg): 50.0",
			/* [66] */ "Clinical observation: Finding — Respiratory rate: 8.0 (CRITICALLY_LOW)",
			/* [67] */ "Clinical observation: Finding — Height (cm): 196.0",
	};

	// Third 160-record dataset from a 52-year-old Female patient chart.
	static final String[] THIRD_PATIENT_DATASET = {
			/* [  1] */ "Clinical observation: (2026-02-28) Test — Haemoglobin: 15.8 g/dL (HIGH)",
			/* [  2] */ "Clinical observation: (2026-02-28) Assessment — Text of encounter note: Massa massa ultricies mi quis hendrerit. Consectetur libero id faucibus nisl tincidunt. Sed faucibus turpis in eu mi. Lobortis scelerisque fermentum dui faucibus in ornare. Scelerisque felis imperdiet proin fermentum. Faucibus vitae aliquet nec ullamcorper sit. Placerat in egestas erat imperdiet sed euismod nisi porta. Arcu felis bibendum ut tristique et egestas. Amet commodo nulla facilisi nullam vehicula ipsum a arcu. Viverra vitae congue eu consequat ac. Convallis a cras semper auctor neque vitae. Mauris a diam maecenas sed enim ut sem. Phasellus faucibus scelerisque eleifend donec. Quisque sagittis purus sit amet volutpat. Lorem dolor sed viverra ipsum nunc aliquet bibendum. Vitae congue eu consequat ac felis donec et. Purus sit amet volutpat consequat mauris nunc congue nisi vitae. Non quam lacus suspendisse faucibus. Tellus mauris a diam maecenas sed enim ut sem. Euismod in pellentesque massa placerat duis ultricies lacus.",
			/* [  3] */ "Medical condition: (2026-02-28) Condition: Zika virus disease. Status: ACTIVE",
			/* [  4] */ "Medical condition: (2026-02-28) Condition: Ovarian cyst. Status: ACTIVE",
			/* [  5] */ "Clinical diagnosis: (2026-02-28) Diagnosis: Zika virus disease. Certainty: PROVISIONAL. Rank: Primary",
			/* [  6] */ "Clinical diagnosis: (2026-02-28) Diagnosis: Ovarian cyst. Certainty: CONFIRMED. Rank: Primary",
			/* [  7] */ "Clinical observation: (2026-02-28) Finding — Temperature (c)): 36.7 DEG C (NORMAL)",
			/* [  8] */ "Clinical observation: (2026-02-28) Finding — Systolic blood pressure: 145.0 mmHg (HIGH)",
			/* [  9] */ "Clinical observation: (2026-02-28) Finding — Diastolic blood pressure: 76.0 mmHg (NORMAL)",
			/* [ 10] */ "Clinical observation: (2026-02-28) Finding — Pulse: 111.0 beats/min (CRITICALLY_HIGH)",
			/* [ 11] */ "Clinical observation: (2026-02-28) Finding — Arterial blood oxygen saturation (pulse oximeter): 97.8 %",
			/* [ 12] */ "Clinical observation: (2026-02-28) Finding — Weight (kg): 73.0 kg",
			/* [ 13] */ "Clinical observation: (2026-02-28) Finding — Respiratory rate: 22.0 breaths/min (HIGH)",
			/* [ 14] */ "Clinical observation: (2026-02-28) Finding — Height (cm): 152.0 cm",
			/* [ 15] */ "Clinical observation: (2026-02-28) Finding — Blood urea nitrogen: 23.6 mg/dL (HIGH)",
			/* [ 16] */ "Clinical observation: (2026-02-28) Finding — Serum calcium: 10.4 mg/dL (NORMAL)",
			/* [ 17] */ "Clinical observation: (2026-02-28) Finding — Serum carbon dioxide: 24.4 mmol/L (NORMAL)",
			/* [ 18] */ "Clinical observation: (2026-02-28) Finding — Serum chloride: 103.4 mEq/L (NORMAL)",
			/* [ 19] */ "Clinical observation: (2026-02-28) Finding — Serum creatinine (mg/dL): 1.6 mg/dL (HIGH)",
			/* [ 20] */ "Clinical observation: (2026-02-28) Finding — Serum glucose: 85.5 mg/dL (NORMAL)",
			/* [ 21] */ "Clinical observation: (2026-02-28) Finding — Serum potassium: 5.2 mmol/L (HIGH)",
			/* [ 22] */ "Clinical observation: (2026-02-28) Finding — Serum sodium: 138.3 mEq/L (NORMAL)",
			/* [ 23] */ "Medication prescription: (2026-02-20) Drug order: Azithromycin. Dose: 4.0 Tablet) Oral Twice daily. Duration: 5 Days. Quantity: 5.0 Tablet). As needed. Dosing: Take with food. Action: NEW. Urgency: ROUTINE. Reason: Inflammatory bowel disease",
			/* [ 24] */ "Medical condition: (2026-02-20) Condition: Inflammatory bowel disease. Status: ACTIVE",
			/* [ 25] */ "Clinical diagnosis: (2026-02-20) Diagnosis: Inflammatory bowel disease. Certainty: CONFIRMED. Rank: Primary",
			/* [ 26] */ "Clinical observation: (2026-02-20) Finding — Temperature (c)): 39.5 DEG C (CRITICALLY_HIGH)",
			/* [ 27] */ "Clinical observation: (2026-02-20) Finding — Systolic blood pressure: 141.0 mmHg (HIGH)",
			/* [ 28] */ "Clinical observation: (2026-02-20) Finding — Diastolic blood pressure: 96.0 mmHg (HIGH)",
			/* [ 29] */ "Clinical observation: (2026-02-20) Finding — Pulse: 84.0 beats/min (NORMAL)",
			/* [ 30] */ "Clinical observation: (2026-02-20) Finding — Arterial blood oxygen saturation (pulse oximeter): 94.2 %",
			/* [ 31] */ "Clinical observation: (2026-02-20) Finding — Weight (kg): 73.0 kg",
			/* [ 32] */ "Clinical observation: (2026-02-20) Finding — Respiratory rate: 16.0 breaths/min (NORMAL)",
			/* [ 33] */ "Clinical observation: (2026-02-20) Finding — Height (cm): 152.0 cm",
			/* [ 34] */ "Medication prescription: (2026-02-07) Drug order: Azithromycin. Dose: 1.0 Tablet) Oral Twice daily. Duration: 5 Days. Quantity: 2.0 Tablet). Dosing: Take after eating. Action: NEW. Urgency: ROUTINE. Reason: Hypertension",
			/* [ 35] */ "Medical condition: (2026-02-07) Condition: Hypertension. Status: ACTIVE",
			/* [ 36] */ "Clinical diagnosis: (2026-02-07) Diagnosis: Hypertension. Certainty: CONFIRMED. Rank: Primary",
			/* [ 37] */ "Clinical observation: (2026-02-07) Finding — Temperature (c)): 36.8 DEG C (NORMAL)",
			/* [ 38] */ "Clinical observation: (2026-02-07) Finding — Systolic blood pressure: 127.0 mmHg (NORMAL)",
			/* [ 39] */ "Clinical observation: (2026-02-07) Finding — Diastolic blood pressure: 80.0 mmHg (NORMAL)",
			/* [ 40] */ "Clinical observation: (2026-02-07) Finding — Pulse: 87.0 beats/min (NORMAL)",
			/* [ 41] */ "Clinical observation: (2026-02-07) Finding — Arterial blood oxygen saturation (pulse oximeter): 96.8 %",
			/* [ 42] */ "Clinical observation: (2026-02-07) Finding — Weight (kg): 74.0 kg",
			/* [ 43] */ "Clinical observation: (2026-02-07) Finding — Respiratory rate: 18.0 breaths/min (NORMAL)",
			/* [ 44] */ "Clinical observation: (2026-02-07) Finding — Height (cm): 152.0 cm",
			/* [ 45] */ "Clinical observation: (2026-02-07) Finding — Blood urea nitrogen: 16.4 mg/dL (NORMAL)",
			/* [ 46] */ "Clinical observation: (2026-02-07) Finding — Serum calcium: 8.8 mg/dL (NORMAL)",
			/* [ 47] */ "Clinical observation: (2026-02-07) Finding — Serum carbon dioxide: 26.5 mmol/L (NORMAL)",
			/* [ 48] */ "Clinical observation: (2026-02-07) Finding — Serum chloride: 103.3 mEq/L (NORMAL)",
			/* [ 49] */ "Clinical observation: (2026-02-07) Finding — Serum creatinine (mg/dL): 0.7 mg/dL (NORMAL)",
			/* [ 50] */ "Clinical observation: (2026-02-07) Finding — Serum glucose: 90.3 mg/dL (NORMAL)",
			/* [ 51] */ "Clinical observation: (2026-02-07) Finding — Serum potassium: 3.8 mmol/L (NORMAL)",
			/* [ 52] */ "Clinical observation: (2026-02-07) Finding — Serum sodium: 138.3 mEq/L (NORMAL)",
			/* [ 53] */ "Medication prescription: (2025-12-23) Drug order: Azithromycin. Dose: 3.0 Tablet) Oral Every six hours. Duration: 5 Days. Quantity: 4.0 Tablet). As needed (subject to headache). Dosing: Take with water. Action: REVISE. Urgency: ROUTINE. Reason: Malaria",
			/* [ 54] */ "Medication prescription: (2025-12-23) Drug order: Azithromycin. Dose: 3.0 Tablet) Oral Every six hours. Duration: 5 Days. Quantity: 4.0 Tablet). As needed (subject to headache). Dosing: Take with water. Action: NEW. Urgency: ROUTINE. Reason: Malaria. Stopped: 2025-12-23",
			/* [ 55] */ "Medical condition: (2025-12-23) Condition: Malaria. Status: ACTIVE",
			/* [ 56] */ "Clinical diagnosis: (2025-12-23) Diagnosis: Malaria. Certainty: CONFIRMED. Rank: Primary",
			/* [ 57] */ "Clinical observation: (2025-12-23) Finding — Temperature (c)): 38.3 DEG C (HIGH)",
			/* [ 58] */ "Clinical observation: (2025-12-23) Finding — Systolic blood pressure: 116.0 mmHg (NORMAL)",
			/* [ 59] */ "Clinical observation: (2025-12-23) Finding — Diastolic blood pressure: 69.0 mmHg (NORMAL)",
			/* [ 60] */ "Clinical observation: (2025-12-23) Finding — Pulse: 101.0 beats/min (HIGH)",
			/* [ 61] */ "Clinical observation: (2025-12-23) Finding — Arterial blood oxygen saturation (pulse oximeter): 94.6 %",
			/* [ 62] */ "Clinical observation: (2025-12-23) Finding — Weight (kg): 74.0 kg",
			/* [ 63] */ "Clinical observation: (2025-12-23) Finding — Respiratory rate: 15.0 breaths/min (NORMAL)",
			/* [ 64] */ "Clinical observation: (2025-12-23) Finding — Height (cm): 152.0 cm",
			/* [ 65] */ "Clinical observation: (2025-12-23) Finding — Blood urea nitrogen: 11.7 mg/dL (NORMAL)",
			/* [ 66] */ "Clinical observation: (2025-12-23) Finding — Serum calcium: 10.5 mg/dL (NORMAL)",
			/* [ 67] */ "Clinical observation: (2025-12-23) Finding — Serum carbon dioxide: 20.9 mmol/L (LOW)",
			/* [ 68] */ "Clinical observation: (2025-12-23) Finding — Serum chloride: 97.9 mEq/L (LOW)",
			/* [ 69] */ "Clinical observation: (2025-12-23) Finding — Serum creatinine (mg/dL): 0.9 mg/dL (NORMAL)",
			/* [ 70] */ "Clinical observation: (2025-12-23) Finding — Serum glucose: 105.1 mg/dL (HIGH)",
			/* [ 71] */ "Clinical observation: (2025-12-23) Finding — Serum potassium: 5.1 mmol/L (HIGH)",
			/* [ 72] */ "Clinical observation: (2025-12-23) Finding — Serum sodium: 138.2 mEq/L (NORMAL)",
			/* [ 73] */ "Medication prescription: (2025-11-03) Drug order: Azithromycin. Dose: 3.0 Tablet) Intravenous Every four hours. Duration: 5 Days. Quantity: 3.0 Tablet). As needed. Dosing: Take with food. Action: NEW. Urgency: ROUTINE. Reason: Chronic kidney disease",
			/* [ 74] */ "Medical condition: (2025-11-03) Condition: Chronic kidney disease. Status: ACTIVE",
			/* [ 75] */ "Clinical diagnosis: (2025-11-03) Diagnosis: Chronic kidney disease. Certainty: CONFIRMED. Rank: Primary",
			/* [ 76] */ "Clinical observation: (2025-11-03) Finding — Temperature (c)): 37.5 DEG C (NORMAL)",
			/* [ 77] */ "Clinical observation: (2025-11-03) Finding — Systolic blood pressure: 149.0 mmHg (HIGH)",
			/* [ 78] */ "Clinical observation: (2025-11-03) Finding — Diastolic blood pressure: 72.0 mmHg (NORMAL)",
			/* [ 79] */ "Clinical observation: (2025-11-03) Finding — Pulse: 62.0 beats/min (NORMAL)",
			/* [ 80] */ "Clinical observation: (2025-11-03) Finding — Arterial blood oxygen saturation (pulse oximeter): 98.5 %",
			/* [ 81] */ "Clinical observation: (2025-11-03) Finding — Weight (kg): 75.0 kg",
			/* [ 82] */ "Clinical observation: (2025-11-03) Finding — Respiratory rate: 21.0 breaths/min (HIGH)",
			/* [ 83] */ "Clinical observation: (2025-11-03) Finding — Height (cm): 152.0 cm",
			/* [ 84] */ "Clinical observation: (2025-11-03) Finding — Blood urea nitrogen: 17.5 mg/dL (NORMAL)",
			/* [ 85] */ "Clinical observation: (2025-11-03) Finding — Serum calcium: 10.3 mg/dL (NORMAL)",
			/* [ 86] */ "Clinical observation: (2025-11-03) Finding — Serum carbon dioxide: 23.7 mmol/L (NORMAL)",
			/* [ 87] */ "Clinical observation: (2025-11-03) Finding — Serum chloride: 99.9 mEq/L (NORMAL)",
			/* [ 88] */ "Clinical observation: (2025-11-03) Finding — Serum creatinine (mg/dL): 0.7 mg/dL (NORMAL)",
			/* [ 89] */ "Clinical observation: (2025-11-03) Finding — Serum glucose: 70.5 mg/dL (LOW)",
			/* [ 90] */ "Clinical observation: (2025-11-03) Finding — Serum potassium: 4.1 mmol/L (NORMAL)",
			/* [ 91] */ "Clinical observation: (2025-11-03) Finding — Serum sodium: 137.2 mEq/L (NORMAL)",
			/* [ 92] */ "Lab test order: (2025-08-26) Test order: Complete Blood Count. Action: NEW. Urgency: STAT. Clinical history: Abnormal red blood cells. Assessments: Anaemia",
			/* [ 93] */ "Medication prescription: (2025-08-26) Drug order: Azithromycin. Dose: 1.0 Tablet) Oral Every four hours. Duration: 5 Days. Quantity: 5.0 Tablet). Dosing: Take after eating. Action: NEW. Urgency: ROUTINE. Reason: Anaemia",
			/* [ 94] */ "Medical condition: (2025-08-26) Condition: Anaemia. Status: ACTIVE",
			/* [ 95] */ "Clinical diagnosis: (2025-08-26) Diagnosis: Anaemia. Certainty: PROVISIONAL. Rank: Primary",
			/* [ 96] */ "Clinical observation: (2025-08-26) Finding — Temperature (c)): 38.9 DEG C (CRITICALLY_HIGH)",
			/* [ 97] */ "Clinical observation: (2025-08-26) Finding — Systolic blood pressure: 127.0 mmHg (NORMAL)",
			/* [ 98] */ "Clinical observation: (2025-08-26) Finding — Diastolic blood pressure: 65.0 mmHg (NORMAL)",
			/* [ 99] */ "Clinical observation: (2025-08-26) Finding — Pulse: 73.0 beats/min (NORMAL)",
			/* [100] */ "Clinical observation: (2025-08-26) Finding — Arterial blood oxygen saturation (pulse oximeter): 96.5 %",
			/* [101] */ "Clinical observation: (2025-08-26) Finding — Weight (kg): 74.0 kg",
			/* [102] */ "Clinical observation: (2025-08-26) Finding — Respiratory rate: 22.0 breaths/min (HIGH)",
			/* [103] */ "Clinical observation: (2025-08-26) Finding — Height (cm): 152.0 cm",
			/* [104] */ "Clinical observation: (2025-08-26) Finding — Haemoglobin: 11.2 g/dL (LOW)",
			/* [105] */ "Clinical observation: (2025-08-26) Finding — White blood cells: 9.1 10^3/uL (NORMAL)",
			/* [106] */ "Clinical observation: (2025-08-26) Finding — Platelet count: 309.0 10^3/uL (NORMAL)",
			/* [107] */ "Clinical observation: (2025-08-26) Finding — Hematocrit: 39.5 % (NORMAL)",
			/* [108] */ "Clinical observation: (2025-08-26) Finding — Mean corpuscular volume: 94.0 fL (NORMAL)",
			/* [109] */ "Clinical observation: (2025-08-26) Finding — Red blood cells: 3.5 10^6/uL (LOW)",
			/* [110] */ "Clinical observation: (2025-08-26) Finding — Blood urea nitrogen: 18.2 mg/dL (NORMAL)",
			/* [111] */ "Clinical observation: (2025-08-26) Finding — Serum calcium: 10.1 mg/dL (NORMAL)",
			/* [112] */ "Clinical observation: (2025-08-26) Finding — Serum carbon dioxide: 27.3 mmol/L (NORMAL)",
			/* [113] */ "Clinical observation: (2025-08-26) Finding — Serum chloride: 101.1 mEq/L (NORMAL)",
			/* [114] */ "Clinical observation: (2025-08-26) Finding — Serum creatinine (mg/dL): 0.7 mg/dL (NORMAL)",
			/* [115] */ "Clinical observation: (2025-08-26) Finding — Serum glucose: 84.8 mg/dL (NORMAL)",
			/* [116] */ "Clinical observation: (2025-08-26) Finding — Serum potassium: 4.7 mmol/L (NORMAL)",
			/* [117] */ "Clinical observation: (2025-08-26) Finding — Serum sodium: 144.5 mEq/L (NORMAL)",
			/* [118] */ "Medication prescription: (2025-05-01) Drug order: Azithromycin. Dose: 1.0 Tablet) Oral Once daily. Duration: 5 Days. Quantity: 5.0 Tablet). Dosing: Take after eating. Action: NEW. Urgency: ROUTINE. Reason: Pneumonia",
			/* [119] */ "Medical condition: (2025-05-01) Condition: Pneumonia. Status: ACTIVE",
			/* [120] */ "Clinical diagnosis: (2025-05-01) Diagnosis: Pneumonia. Certainty: CONFIRMED. Rank: Primary",
			/* [121] */ "Clinical observation: (2025-05-01) Finding — Temperature (c)): 38.2 DEG C (HIGH)",
			/* [122] */ "Clinical observation: (2025-05-01) Finding — Systolic blood pressure: 140.0 mmHg (HIGH)",
			/* [123] */ "Clinical observation: (2025-05-01) Finding — Diastolic blood pressure: 85.0 mmHg (NORMAL)",
			/* [124] */ "Clinical observation: (2025-05-01) Finding — Pulse: 62.0 beats/min (NORMAL)",
			/* [125] */ "Clinical observation: (2025-05-01) Finding — Arterial blood oxygen saturation (pulse oximeter): 95.9 %",
			/* [126] */ "Clinical observation: (2025-05-01) Finding — Weight (kg): 73.0 kg",
			/* [127] */ "Clinical observation: (2025-05-01) Finding — Respiratory rate: 23.0 breaths/min (HIGH)",
			/* [128] */ "Clinical observation: (2025-05-01) Finding — Height (cm): 152.0 cm",
			/* [129] */ "Medication prescription: (2024-12-12) Drug order: Azithromycin. Dose: 4.0 Tablet) Oral Once daily. Duration: 5 Days. Quantity: 2.0 Tablet). As needed (subject to headache). Dosing: Take after eating. Action: NEW. Urgency: ROUTINE. Reason: Diabetes",
			/* [130] */ "Medical condition: (2024-12-12) Condition: Diabetes. Status: ACTIVE",
			/* [131] */ "Clinical diagnosis: (2024-12-12) Diagnosis: Diabetes. Certainty: CONFIRMED. Rank: Primary",
			/* [132] */ "Clinical observation: (2024-12-12) Finding — Temperature (c)): 37.7 DEG C (NORMAL)",
			/* [133] */ "Clinical observation: (2024-12-12) Finding — Systolic blood pressure: 130.0 mmHg (NORMAL)",
			/* [134] */ "Clinical observation: (2024-12-12) Finding — Diastolic blood pressure: 86.0 mmHg (NORMAL)",
			/* [135] */ "Clinical observation: (2024-12-12) Finding — Pulse: 79.0 beats/min (NORMAL)",
			/* [136] */ "Clinical observation: (2024-12-12) Finding — Arterial blood oxygen saturation (pulse oximeter): 98.3 %",
			/* [137] */ "Medical condition: (2023-05-04) Condition: Self-Induced Abortion. Status: ACTIVE",
			/* [138] */ "Clinical observation: (2023-05-04) Finding — Temperature (c)): 37.3 DEG C (NORMAL)",
			/* [139] */ "Clinical diagnosis: (2023-05-04) Diagnosis: Self-Induced Abortion. Certainty: CONFIRMED. Rank: Primary",
			/* [140] */ "Clinical observation: (2023-05-04) Finding — Systolic blood pressure: 140.0 mmHg (HIGH)",
			/* [141] */ "Clinical observation: (2023-05-04) Finding — Diastolic blood pressure: 64.0 mmHg (NORMAL)",
			/* [142] */ "Clinical observation: (2023-05-04) Finding — Pulse: 96.0 beats/min (NORMAL)",
			/* [143] */ "Clinical observation: (2023-05-04) Finding — Arterial blood oxygen saturation (pulse oximeter): 96.3 %",
			/* [144] */ "Clinical observation: (2023-05-04) Finding — Weight (kg): 72.0 kg",
			/* [145] */ "Clinical observation: (2023-05-04) Finding — Respiratory rate: 22.0 breaths/min (HIGH)",
			/* [146] */ "Clinical observation: (2023-05-04) Finding — Height (cm): 152.0 cm",
			/* [147] */ "Medication prescription: (2023-05-04) Drug order: Azithromycin. Dose: 4.0 Tablet) Oral Once daily. Duration: 5 Days. Quantity: 5.0 Tablet). As needed (subject to headache). Dosing: Take after eating. Action: NEW. Urgency: ROUTINE. Reason: Self-Induced Abortion",
			/* [148] */ "Clinical observation: (2023-05-04) Assessment — Text of encounter note: Vulputate eu scelerisque felis imperdiet proin. Praesent elementum facilisis leo vel fringilla est. Vitae purus faucibus ornare suspendisse sed nisi. Convallis tellus id interdum velit laoreet id donec. Sit amet nulla facilisi morbi tempus iaculis urna id. Quis vel eros donec ac odio tempor. Eget duis at tellus at urna condimentum mattis. Imperdiet dui accumsan sit amet nulla facilisi morbi tempus. In dictum non consectetur a erat nam at. A lacus vestibulum sed arcu non odio euismod. Fames ac turpis egestas maecenas pharetra.",
			/* [149] */ "Program enrollment: (2023-05-04) Program: PMTCT (Prevention of Mother-to-Child Transmission of HIV during pregnancy). Enrolled: 2023-05-04. Status: Active",
			/* [150] */ "Clinical observation: (2022-06-20) Finding — Temperature (c)): 37.2 DEG C (NORMAL)",
			/* [151] */ "Clinical observation: (2022-06-20) Finding — Systolic blood pressure: 120.0 mmHg (NORMAL)",
			/* [152] */ "Clinical observation: (2022-06-20) Finding — Diastolic blood pressure: 69.0 mmHg (NORMAL)",
			/* [153] */ "Clinical observation: (2022-06-20) Finding — Pulse: 92.0 beats/min (NORMAL)",
			/* [154] */ "Clinical observation: (2022-06-20) Finding — Arterial blood oxygen saturation (pulse oximeter): 97.6 %",
			/* [155] */ "Clinical observation: (2022-06-20) Finding — Weight (kg): 71.0 kg",
			/* [156] */ "Clinical observation: (2022-06-20) Assessment — Text of encounter note: Lorem ipsum dolor sit amet, consectetur adipiscing elit, sed do eiusmod tempor incididunt ut labore et dolore magna aliqua. Ut enim ad minim veniam, quis nostrud exercitation ullamco laboris nisi ut aliquip ex ea commodo consequat. Duis aute irure dolor in reprehenderit in voluptate velit esse cillum dolore eu fugiat nulla pariatur. Excepteur sint occaecat cupidatat non proident, sunt in culpa qui officia deserunt mollit anim id est laborum.",
			/* [157] */ "Medical condition: (2022-06-20) Condition: Asthma. Status: INACTIVE",
			/* [158] */ "Clinical diagnosis: (2022-06-20) Diagnosis: Asthma. Certainty: CONFIRMED. Rank: Primary",
			/* [159] */ "Clinical observation: (2022-06-20) Finding — Respiratory rate: 13.0 breaths/min (NORMAL)",
			/* [160] */ "Clinical observation: (2022-06-20) Finding — Height (cm): 152.0 cm",
	};

	// Fourth 160-record dataset from a 52-year-old Female patient chart.
	// Unlike the third dataset, this one has no individual CBC lab values
	// (Haemoglobin, Platelet, Hematocrit) but includes blood-related
	// conditions (Haemorrhagic disease, Melaena) that use different
	// vocabulary from "blood".
	static final String[] FOURTH_PATIENT_DATASET = {
			/* [  1] */ "Clinical observation: (2026-02-28) Test — Haemoglobin: 15.8 g/dL (HIGH)",
			/* [  2] */ "Clinical observation: (2026-02-28) Assessment — Text of encounter note: Massa massa ultricies mi quis hendrerit. Consectetur libero id faucibus nisl tincidunt. Sed faucibus turpis in eu mi. Lobortis scelerisque fermentum dui faucibus in ornare. Scelerisque felis imperdiet proin fermentum. Faucibus vitae aliquet nec ullamcorper sit. Placerat in egestas erat imperdiet sed euismod nisi porta. Arcu felis bibendum ut tristique et egestas. Amet commodo nulla facilisi nullam vehicula ipsum a arcu. Viverra vitae congue eu consequat ac. Convallis a cras semper auctor neque vitae. Mauris a diam maecenas sed enim ut sem. Phasellus faucibus scelerisque eleifend donec. Quisque sagittis purus sit amet volutpat. Lorem dolor sed viverra ipsum nunc aliquet bibendum. Vitae congue eu consequat ac felis donec et. Purus sit amet volutpat consequat mauris nunc congue nisi vitae. Non quam lacus suspendisse faucibus. Tellus mauris a diam maecenas sed enim ut sem. Euismod in pellentesque massa placerat duis ultricies lacus.",
			/* [  3] */ "Medical condition: (2026-02-28) Condition: Zika virus disease. Status: ACTIVE",
			/* [  4] */ "Medical condition: (2026-02-28) Condition: Ovarian cyst. Status: ACTIVE",
			/* [  5] */ "Clinical diagnosis: (2026-02-28) Diagnosis: Zika virus disease. Certainty: PROVISIONAL. Rank: Primary",
			/* [  6] */ "Clinical diagnosis: (2026-02-28) Diagnosis: Ovarian cyst. Certainty: CONFIRMED. Rank: Primary",
			/* [  7] */ "Clinical observation: (2026-02-28) Finding — Temperature (c)): 36.7 DEG C (NORMAL)",
			/* [  8] */ "Clinical observation: (2026-02-28) Finding — Systolic blood pressure: 145.0 mmHg (HIGH)",
			/* [  9] */ "Clinical observation: (2026-02-28) Finding — Diastolic blood pressure: 76.0 mmHg (NORMAL)",
			/* [ 10] */ "Clinical observation: (2026-02-28) Finding — Pulse: 111.0 beats/min (CRITICALLY_HIGH)",
			/* [ 11] */ "Clinical observation: (2026-02-28) Finding — Arterial blood oxygen saturation (pulse oximeter): 97.8 %",
			/* [ 12] */ "Clinical observation: (2026-02-28) Finding — Weight (kg): 51.0 kg",
			/* [ 13] */ "Clinical observation: (2026-02-28) Finding — Respiratory rate: 18.0 breaths/min (NORMAL)",
			/* [ 14] */ "Clinical observation: (2026-02-28) Finding — Height (cm): 176.0 cm",
			/* [ 15] */ "Clinical observation: (2026-02-27) LabSet — Prostate specific antigen test: Prostate specific antigen (PSA) measurement (ng/mL): 132.5 ng/mL",
			/* [ 16] */ "Clinical observation: (2026-02-27) Test — Hemoglobin in umbilical cord blood (mg/mL): 99.4 mg/mL",
			/* [ 17] */ "Clinical observation: (2026-02-27) Assessment — Text of encounter note: Gravida in fermentum et sollicitudin. Nibh ipsum consequat nisl vel pretium lectus quam.",
			/* [ 18] */ "Medical condition: (2026-02-27) Condition: Wasting syndrome. Status: ACTIVE",
			/* [ 19] */ "Medical condition: (2026-02-27) Condition: Haemorrhagic disease of newborn. Status: ACTIVE",
			/* [ 20] */ "Medical condition: (2026-02-27) Condition: Psychosis. Status: ACTIVE",
			/* [ 21] */ "Clinical diagnosis: (2026-02-27) Diagnosis: Wasting syndrome. Certainty: PROVISIONAL. Rank: Primary",
			/* [ 22] */ "Clinical diagnosis: (2026-02-27) Diagnosis: Haemorrhagic disease of newborn. Certainty: CONFIRMED. Rank: Primary",
			/* [ 23] */ "Clinical diagnosis: (2026-02-27) Diagnosis: Psychosis. Certainty: CONFIRMED. Rank: Primary",
			/* [ 24] */ "Clinical observation: (2026-02-27) Finding — Temperature (c)): 38.1 DEG C (CRITICALLY_HIGH)",
			/* [ 25] */ "Clinical observation: (2026-02-27) Finding — Systolic blood pressure: 111.0 mmHg (NORMAL)",
			/* [ 26] */ "Clinical observation: (2026-02-27) Finding — Diastolic blood pressure: 40.0 mmHg (CRITICALLY_LOW)",
			/* [ 27] */ "Clinical observation: (2026-02-27) Finding — Pulse: 70.0 beats/min (NORMAL)",
			/* [ 28] */ "Clinical observation: (2026-02-27) Finding — Arterial blood oxygen saturation (pulse oximeter): 89.6 % (CRITICALLY_LOW)",
			/* [ 29] */ "Clinical observation: (2026-02-27) Finding — Weight (kg): 86.0 kg",
			/* [ 30] */ "Clinical observation: (2026-02-27) Finding — Respiratory rate: 22.0 breaths/min (HIGH)",
			/* [ 31] */ "Clinical observation: (2026-02-27) Finding — Height (cm): 149.0 cm",
			/* [ 32] */ "Clinical observation: (2026-02-25) LabSet — Cerebrospinal fluid panel: Cerebrospinal fluid white blood cells: 59.0; Cerebrospinal fluid protein measurement: 24.7 mg/dL; Cerebrospinal fluid volume measurement (mL)): 112.3; Cerebrospinal fluid appearance: Greenish-yellow matter detected.; Cerebrospinal fluid glucose: 78.7 mg/dL; Cerebrospinal fluid red blood cells: 116.0",
			/* [ 33] */ "Clinical observation: (2026-02-25) Assessment — Text of encounter note: Neque convallis a cras semper auctor neque vitae. Proin nibh nisl condimentum id venenatis a condimentum vitae. Viverra accumsan in nisl nisi scelerisque eu ultrices vitae. Ac tincidunt vitae semper quis lectus nulla.",
			/* [ 34] */ "Medical condition: (2026-02-25) Condition: Enteroviral vesicular stomatitis with exanthem). Status: ACTIVE",
			/* [ 35] */ "Medical condition: (2026-02-25) Condition: Cerebrovascular Accident (I64). Status: ACTIVE",
			/* [ 36] */ "Clinical diagnosis: (2026-02-25) Diagnosis: Enteroviral vesicular stomatitis with exanthem). Certainty: PROVISIONAL. Rank: Primary",
			/* [ 37] */ "Clinical diagnosis: (2026-02-25) Diagnosis: Cerebrovascular Accident (I64). Certainty: CONFIRMED. Rank: Primary",
			/* [ 38] */ "Clinical observation: (2026-02-25) Finding — Temperature (c)): 36.4 DEG C (NORMAL)",
			/* [ 39] */ "Clinical observation: (2026-02-25) Finding — Systolic blood pressure: 159.0 mmHg (HIGH)",
			/* [ 40] */ "Clinical observation: (2026-02-25) Finding — Diastolic blood pressure: 72.0 mmHg (NORMAL)",
			/* [ 41] */ "Clinical observation: (2026-02-25) Finding — Pulse: 70.0 beats/min (NORMAL)",
			/* [ 42] */ "Clinical observation: (2026-02-25) Finding — Arterial blood oxygen saturation (pulse oximeter): 96.8 %",
			/* [ 43] */ "Clinical observation: (2026-02-25) Finding — Weight (kg): 64.0 kg",
			/* [ 44] */ "Clinical observation: (2026-02-25) Finding — Respiratory rate: 20.0 breaths/min (HIGH)",
			/* [ 45] */ "Clinical observation: (2026-02-25) Finding — Height (cm): 193.0 cm",
			/* [ 46] */ "Clinical observation: (2026-02-24) Assessment — Text of encounter note: Lorem ipsum dolor sit amet, consectetur adipiscing elit, sed do eiusmod tempor incididunt ut labore et dolore magna aliqua. Vulputate enim nulla aliquet porttitor. Fermentum iaculis eu non diam phasellus vestibulum lorem sed. Orci ac auctor augue mauris augue neque. Fames ac turpis egestas sed tempus urna. Sit amet justo donec enim diam vulputate. Tortor aliquam nulla facilisi cras fermentum. Aliquet eget sit amet tellus. Elit ullamcorper dignissim cras tincidunt lobortis feugiat. Nisl tincidunt eget nullam non nisi est. Volutpat maecenas volutpat blandit aliquam etiam erat.",
			/* [ 47] */ "Medical condition: (2026-02-24) Condition: Melaena. Status: ACTIVE",
			/* [ 48] */ "Medical condition: (2026-02-24) Condition: Mental or behavioral disorder due to psychoactive substance. Status: ACTIVE",
			/* [ 49] */ "Medical condition: (2026-02-24) Condition: Cocaine abuse. Status: ACTIVE",
			/* [ 50] */ "Medical condition: (2026-02-24) Condition: Partial congenital absence of teeth. Status: ACTIVE",
			/* [ 51] */ "Clinical diagnosis: (2026-02-24) Diagnosis: Melaena. Certainty: PROVISIONAL. Rank: Primary",
			/* [ 52] */ "Clinical diagnosis: (2026-02-24) Diagnosis: Mental or behavioral disorder due to psychoactive substance. Certainty: CONFIRMED. Rank: Primary",
			/* [ 53] */ "Clinical diagnosis: (2026-02-24) Diagnosis: Cocaine abuse. Certainty: CONFIRMED. Rank: Primary",
			/* [ 54] */ "Clinical diagnosis: (2026-02-24) Diagnosis: Partial congenital absence of teeth. Certainty: CONFIRMED. Rank: Primary",
			/* [ 55] */ "Clinical observation: (2026-02-24) Finding — Temperature (c)): 38.5 DEG C (CRITICALLY_HIGH)",
			/* [ 56] */ "Clinical observation: (2026-02-24) Finding — Systolic blood pressure: 112.0 mmHg (NORMAL)",
			/* [ 57] */ "Clinical observation: (2026-02-24) Finding — Diastolic blood pressure: 41.0 mmHg (CRITICALLY_LOW)",
			/* [ 58] */ "Clinical observation: (2026-02-24) Finding — Pulse: 55.0 beats/min (LOW)",
			/* [ 59] */ "Clinical observation: (2026-02-24) Finding — Arterial blood oxygen saturation (pulse oximeter): 92.5 % (LOW)",
			/* [ 60] */ "Clinical observation: (2026-02-24) Finding — Weight (kg): 79.0 kg",
			/* [ 61] */ "Clinical observation: (2026-02-24) Finding — Respiratory rate: 14.0 breaths/min (NORMAL)",
			/* [ 62] */ "Clinical observation: (2026-02-24) Finding — Height (cm): 183.0 cm",
			/* [ 63] */ "Clinical observation: (2025-08-02) Assessment — Text of encounter note: Montes nascetur ridiculus mus mauris vitae. Proin sagittis nisl rhoncus mattis. Pulvinar pellentesque habitant morbi tristique senectus et netus et malesuada. Urna cursus eget nunc scelerisque viverra mauris in aliquam sem.",
			/* [ 64] */ "Medical condition: (2025-08-02) Condition: Malignant tumor of base of tongue. Status: ACTIVE",
			/* [ 65] */ "Medical condition: (2025-08-02) Condition: Rash, Moderate. Status: ACTIVE",
			/* [ 66] */ "Clinical diagnosis: (2025-08-02) Diagnosis: Malignant tumor of base of tongue. Certainty: PROVISIONAL. Rank: Primary",
			/* [ 67] */ "Clinical diagnosis: (2025-08-02) Diagnosis: Rash, Moderate. Certainty: CONFIRMED. Rank: Primary",
			/* [ 68] */ "Clinical observation: (2025-08-02) Finding — Temperature (c)): 37.4 DEG C (NORMAL)",
			/* [ 69] */ "Clinical observation: (2025-08-02) Finding — Systolic blood pressure: 151.0 mmHg (HIGH)",
			/* [ 70] */ "Clinical observation: (2025-08-02) Finding — Diastolic blood pressure: 81.0 mmHg (HIGH)",
			/* [ 71] */ "Clinical observation: (2025-08-02) Finding — Pulse: 54.0 beats/min (LOW)",
			/* [ 72] */ "Clinical observation: (2025-08-02) Finding — Arterial blood oxygen saturation (pulse oximeter): 88.1 % (CRITICALLY_LOW)",
			/* [ 73] */ "Clinical observation: (2025-08-02) Finding — Weight (kg): 79.0 kg",
			/* [ 74] */ "Clinical observation: (2025-08-02) Finding — Respiratory rate: 18.0 breaths/min (NORMAL)",
			/* [ 75] */ "Clinical observation: (2025-08-02) Finding — Height (cm): 192.0 cm",
			/* [ 76] */ "Clinical observation: (2025-06-26) Assessment — Text of encounter note: Lorem ipsum dolor sit amet, consectetur adipiscing elit, sed do eiusmod tempor incididunt ut labore et dolore magna aliqua. Vulputate enim nulla aliquet porttitor. Fermentum iaculis eu non diam phasellus vestibulum lorem sed. Orci ac auctor augue mauris augue neque. Fames ac turpis egestas sed tempus urna. Sit amet justo donec enim diam vulputate. Tortor aliquam nulla facilisi cras fermentum. Aliquet eget sit amet tellus. Elit ullamcorper dignissim cras tincidunt lobortis feugiat. Nisl tincidunt eget nullam non nisi est. Volutpat maecenas volutpat blandit aliquam etiam erat.",
			/* [ 77] */ "Medical condition: (2025-06-26) Condition: Intraoperative musculoskeletal disorder. Status: ACTIVE",
			/* [ 78] */ "Medical condition: (2025-06-26) Condition: Self-accusation. Status: ACTIVE",
			/* [ 79] */ "Clinical diagnosis: (2025-06-26) Diagnosis: Intraoperative musculoskeletal disorder. Certainty: PROVISIONAL. Rank: Primary",
			/* [ 80] */ "Clinical diagnosis: (2025-06-26) Diagnosis: Self-accusation. Certainty: CONFIRMED. Rank: Primary",
			/* [ 81] */ "Clinical observation: (2025-06-26) Finding — Temperature (c)): 36.3 DEG C (NORMAL)",
			/* [ 82] */ "Clinical observation: (2025-06-26) Finding — Systolic blood pressure: 139.0 mmHg (HIGH)",
			/* [ 83] */ "Clinical observation: (2025-06-26) Finding — Diastolic blood pressure: 52.0 mmHg (LOW)",
			/* [ 84] */ "Clinical observation: (2025-06-26) Finding — Pulse: 90.0 beats/min (NORMAL)",
			/* [ 85] */ "Clinical observation: (2025-06-26) Finding — Arterial blood oxygen saturation (pulse oximeter): 92.1 % (LOW)",
			/* [ 86] */ "Clinical observation: (2025-06-26) Finding — Weight (kg): 69.0 kg",
			/* [ 87] */ "Clinical observation: (2025-06-26) Finding — Respiratory rate: 18.0 breaths/min (NORMAL)",
			/* [ 88] */ "Clinical observation: (2025-06-26) Finding — Height (cm): 175.0 cm",
			/* [ 89] */ "Clinical observation: (2024-11-22) LabSet — Glucose tolerance test 5 specimens (75g) panel: Glucose, serum 1 hr post 75g oral glucose (mg/dL): 74.0 mg/dL (NORMAL); Glucose, serum 4 hrs post 75g oral glucose (mg/dL): 98.0 mg/dL (NORMAL); Glucose tolerance test pre-glucose administration: 83.0 mg/dl (NORMAL); Glucose, serum 1/2 hr post 75g oral glucose (mg/dL): 118.0 mg/dL (NORMAL); Glucose, serum 3 hrs post 75g oral glucose (mg/dL): 88.0 mg/dL (NORMAL); Glucose, serum 2 hrs post 75g oral glucose (mg/dL): 110.0 mg/dL (NORMAL)",
			/* [ 90] */ "Clinical observation: (2024-11-22) Assessment — Text of encounter note: Neque convallis a cras semper auctor neque vitae. Proin nibh nisl condimentum id venenatis a condimentum vitae. Viverra accumsan in nisl nisi scelerisque eu ultrices vitae. Ac tincidunt vitae semper quis lectus nulla.",
			/* [ 91] */ "Medical condition: (2024-11-22) Condition: Hookworm disease. Status: ACTIVE",
			/* [ 92] */ "Medical condition: (2024-11-22) Condition: Failure of exfoliation of primary tooth. Status: ACTIVE",
			/* [ 93] */ "Medical condition: (2024-11-22) Condition: Crushing injury of thigh. Status: ACTIVE",
			/* [ 94] */ "Medical condition: (2024-11-22) Condition: Acquired short achilles tendon. Status: ACTIVE",
			/* [ 95] */ "Clinical diagnosis: (2024-11-22) Diagnosis: Hookworm disease. Certainty: PROVISIONAL. Rank: Primary",
			/* [ 96] */ "Clinical diagnosis: (2024-11-22) Diagnosis: Failure of exfoliation of primary tooth. Certainty: CONFIRMED. Rank: Primary",
			/* [ 97] */ "Clinical diagnosis: (2024-11-22) Diagnosis: Crushing injury of thigh. Certainty: CONFIRMED. Rank: Primary",
			/* [ 98] */ "Clinical diagnosis: (2024-11-22) Diagnosis: Acquired short achilles tendon. Certainty: CONFIRMED. Rank: Primary",
			/* [ 99] */ "Clinical observation: (2024-11-22) Finding — Temperature (c)): 36.3 DEG C (NORMAL)",
			/* [100] */ "Clinical observation: (2024-11-22) Finding — Systolic blood pressure: 113.0 mmHg (NORMAL)",
			/* [101] */ "Clinical observation: (2024-11-22) Finding — Diastolic blood pressure: 78.0 mmHg (NORMAL)",
			/* [102] */ "Clinical observation: (2024-11-22) Finding — Pulse: 63.0 beats/min (NORMAL)",
			/* [103] */ "Clinical observation: (2024-11-22) Finding — Arterial blood oxygen saturation (pulse oximeter): 97.6 %",
			/* [104] */ "Clinical observation: (2024-11-22) Finding — Weight (kg): 83.0 kg",
			/* [105] */ "Clinical observation: (2024-11-22) Finding — Respiratory rate: 9.0 breaths/min (CRITICALLY_LOW)",
			/* [106] */ "Clinical observation: (2024-11-22) Finding — Height (cm): 179.0 cm",
			/* [107] */ "Clinical observation: (2024-03-01) Assessment — Text of encounter note: Vel turpis nunc eget lorem dolor sed. Cras semper auctor neque vitae. Mauris in aliquam sem fringilla ut morbi tincidunt augue interdum. Lectus proin nibh nisl condimentum id venenatis a condimentum vitae. Justo donec enim diam vulputate ut pharetra. Et ultrices neque ornare aenean euismod. Lobortis feugiat vivamus at augue eget. Et malesuada fames ac turpis egestas maecenas. Massa enim nec dui nunc mattis enim ut. Sit amet purus gravida quis blandit. Pellentesque pulvinar pellentesque habitant morbi tristique senectus. Turpis egestas sed tempus urna et pharetra. Nibh tortor id aliquet lectus proin nibh. Sed adipiscing diam donec adipiscing. Tortor condimentum lacinia quis vel eros donec ac odio. Accumsan in nisl nisi scelerisque eu ultrices vitae auctor eu. Consectetur adipiscing elit duis tristique sollicitudin nibh sit amet commodo. Nibh cras pulvinar mattis nunc. Mattis vulputate enim nulla aliquet porttitor lacus luctus. Et ligula ullamcorper malesuada proin libero nunc consequat.",
			/* [108] */ "Medical condition: (2024-03-01) Condition: Nonunion of fracture. Status: ACTIVE",
			/* [109] */ "Medical condition: (2024-03-01) Condition: Human immunodeficiency virus (HIV) disease infection). Status: ACTIVE",
			/* [110] */ "Clinical diagnosis: (2024-03-01) Diagnosis: Nonunion of fracture. Certainty: PROVISIONAL. Rank: Primary",
			/* [111] */ "Clinical diagnosis: (2024-03-01) Diagnosis: Human immunodeficiency virus (HIV) disease infection). Certainty: CONFIRMED. Rank: Primary",
			/* [112] */ "Clinical observation: (2024-03-01) Finding — Temperature (c)): 36.7 DEG C (NORMAL)",
			/* [113] */ "Clinical observation: (2024-03-01) Finding — Systolic blood pressure: 146.0 mmHg (HIGH)",
			/* [114] */ "Clinical observation: (2024-03-01) Finding — Diastolic blood pressure: 55.0 mmHg (LOW)",
			/* [115] */ "Clinical observation: (2024-03-01) Finding — Pulse: 79.0 beats/min (NORMAL)",
			/* [116] */ "Clinical observation: (2024-03-01) Finding — Arterial blood oxygen saturation (pulse oximeter): 90.1 % (LOW)",
			/* [117] */ "Clinical observation: (2024-03-01) Finding — Weight (kg): 62.0 kg",
			/* [118] */ "Clinical observation: (2024-03-01) Finding — Respiratory rate: 18.0 breaths/min (NORMAL)",
			/* [119] */ "Clinical observation: (2024-03-01) Finding — Height (cm): 162.0 cm",
			/* [120] */ "Clinical observation: (2023-12-19) Assessment — Text of encounter note: Maecenas pharetra convallis posuere morbi leo urna molestie at elementum. Ullamcorper velit sed ullamcorper morbi tincidunt ornare. Malesuada fames ac turpis egestas sed tempus urna et. Morbi leo urna molestie at elementum eu facilisis sed odio. Eu facilisis sed odio morbi quis commodo odio aenean. Faucibus a pellentesque sit amet porttitor eget dolor. Suscipit adipiscing bibendum est ultricies. Mattis ullamcorper velit sed ullamcorper morbi tincidunt ornare massa eget. Nulla posuere sollicitudin aliquam ultrices sagittis orci a scelerisque purus. Libero id faucibus nisl tincidunt eget nullam non nisi. Quis viverra nibh cras pulvinar mattis nunc sed blandit libero. Commodo quis imperdiet massa tincidunt nunc pulvinar sapien et ligula. Natoque penatibus et magnis dis parturient. Fames ac turpis egestas sed tempus urna et pharetra pharetra. Sem nulla pharetra diam sit amet nisl suscipit. Felis bibendum ut tristique et egestas quis.",
			/* [121] */ "Medical condition: (2023-12-19) Condition: Benign neoplasm of scrotum. Status: ACTIVE",
			/* [122] */ "Medical condition: (2023-12-19) Condition: Substance Addiction. Status: ACTIVE",
			/* [123] */ "Medical condition: (2023-12-19) Condition: Chronic gingivitis. Status: ACTIVE",
			/* [124] */ "Clinical diagnosis: (2023-12-19) Diagnosis: Benign neoplasm of scrotum. Certainty: PROVISIONAL. Rank: Primary",
			/* [125] */ "Clinical diagnosis: (2023-12-19) Diagnosis: Substance Addiction. Certainty: CONFIRMED. Rank: Primary",
			/* [126] */ "Clinical diagnosis: (2023-12-19) Diagnosis: Chronic gingivitis. Certainty: CONFIRMED. Rank: Primary",
			/* [127] */ "Clinical observation: (2023-12-19) Finding — Temperature (c)): 38.2 DEG C (CRITICALLY_HIGH)",
			/* [128] */ "Clinical observation: (2023-12-19) Finding — Systolic blood pressure: 115.0 mmHg (NORMAL)",
			/* [129] */ "Clinical observation: (2023-12-19) Finding — Diastolic blood pressure: 79.0 mmHg (NORMAL)",
			/* [130] */ "Clinical observation: (2023-12-19) Finding — Pulse: 57.0 beats/min (LOW)",
			/* [131] */ "Clinical observation: (2023-12-19) Finding — Arterial blood oxygen saturation (pulse oximeter): 91.0 % (LOW)",
			/* [132] */ "Clinical observation: (2023-12-19) Finding — Weight (kg): 92.0 kg",
			/* [133] */ "Clinical observation: (2023-12-19) Finding — Respiratory rate: 15.0 breaths/min (NORMAL)",
			/* [134] */ "Clinical observation: (2023-12-19) Finding — Height (cm): 160.0 cm",
			/* [135] */ "Clinical observation: (2023-05-04) LabSet — Basic metabolic panel: Serum glucose (mmol): 0.9 mmol/L; Serum calcium: 7.7 mg/dL; Serum sodium: 146.0 mmol/L (NORMAL); Blood urea nitrogen: 82.9 mmol/L; Serum potassium: 3.9 mmol/L (NORMAL); Serum chloride: 99.0 mmol/L (NORMAL); Serum glucose: 118.0 mg/dl; Serum carbon dioxide: 26.0 mmol/L (NORMAL); Serum creatinine (umol/L): 146.5 umol/L",
			/* [136] */ "Clinical observation: (2023-05-04) Assessment — Text of encounter note: Faucibus et molestie ac feugiat sed lectus. Condimentum lacinia quis vel eros donec ac. Urna porttitor rhoncus dolor purus.",
			/* [137] */ "Medical condition: (2023-05-04) Condition: Self-Induced Abortion. Status: ACTIVE",
			/* [138] */ "Medical condition: (2023-05-04) Condition: Gonococcal arthritis. Status: ACTIVE",
			/* [139] */ "Clinical diagnosis: (2023-05-04) Diagnosis: Self-Induced Abortion. Certainty: PROVISIONAL. Rank: Primary",
			/* [140] */ "Clinical diagnosis: (2023-05-04) Diagnosis: Gonococcal arthritis. Certainty: CONFIRMED. Rank: Primary",
			/* [141] */ "Clinical observation: (2023-05-04) Finding — Temperature (c)): 36.6 DEG C (NORMAL)",
			/* [142] */ "Clinical observation: (2023-05-04) Finding — Systolic blood pressure: 123.0 mmHg (HIGH)",
			/* [143] */ "Clinical observation: (2023-05-04) Finding — Diastolic blood pressure: 81.0 mmHg (HIGH)",
			/* [144] */ "Clinical observation: (2023-05-04) Finding — Pulse: 54.0 beats/min (LOW)",
			/* [145] */ "Clinical observation: (2023-05-04) Finding — Arterial blood oxygen saturation (pulse oximeter): 95.0 %",
			/* [146] */ "Clinical observation: (2023-05-04) Finding — Weight (kg): 91.0 kg",
			/* [147] */ "Clinical observation: (2023-05-04) Finding — Respiratory rate: 9.0 breaths/min (CRITICALLY_LOW)",
			/* [148] */ "Clinical observation: (2023-05-04) Finding — Height (cm): 185.0 cm",
			/* [149] */ "Program enrollment: (2023-05-04) Program: PMTCT (Program for prevention of Maternal to Child HIV transmission). Enrolled: 2023-05-04. Status: Active. Current state: Lost to follow-up",
			/* [150] */ "Clinical observation: (2022-06-20) Assessment — Text of encounter note: Eget aliquet nibh praesent tristique. Lectus arcu bibendum at varius vel. Eget duis at tellus at urna condimentum mattis pellentesque. Quisque egestas diam in arcu cursus euismod quis viverra. Tellus orci ac auctor augue mauris augue. Id nibh tortor id aliquet lectus proin. Et ultrices neque ornare aenean euismod elementum nisi quis eleifend. Morbi leo urna molestie at elementum eu. Mauris ultrices eros in cursus turpis massa tincidunt. Arcu risus quis varius quam quisque id diam vel.\n\nScelerisque eu ultrices vitae auctor eu augue. Sem integer vitae justo eget magna fermentum iaculis eu. Diam volutpat commodo sed egestas egestas. Sit amet facilisis magna etiam tempor orci eu lobortis. Nam aliquam sem et tortor consequat. Id cursus metus aliquam eleifend mi in nulla posuere sollicitudin. Tempus quam pellentesque nec nam aliquam sem. Odio eu feugiat pretium nibh. Elementum eu facilisis sed odio morbi quis commodo odio aenean. Sagittis orci a scelerisque purus semper eget. Duis ut diam quam nulla porttitor. Vitae auctor eu augue ut lectus. Tincidunt nunc pulvinar sapien et. Est pellentesque elit ullamcorper dignissim. Etiam non quam lacus suspendisse faucibus interdum posuere lorem ipsum. Tristique et egestas quis ipsum suspendisse. Egestas fringilla phasellus faucibus scelerisque eleifend. Sollicitudin aliquam ultrices sagittis orci a scelerisque. Semper risus in hendrerit gravida.",
			/* [151] */ "Medical condition: (2022-06-20) Condition: Non-severe event supposed to be attributable to vaccination and immunization (ESAVI). Status: ACTIVE",
			/* [152] */ "Clinical diagnosis: (2022-06-20) Diagnosis: Non-severe event supposed to be attributable to vaccination and immunization (ESAVI). Certainty: PROVISIONAL. Rank: Primary",
			/* [153] */ "Clinical observation: (2022-06-20) Finding — Temperature (c)): 36.5 DEG C (NORMAL)",
			/* [154] */ "Clinical observation: (2022-06-20) Finding — Systolic blood pressure: 156.0 mmHg (HIGH)",
			/* [155] */ "Clinical observation: (2022-06-20) Finding — Diastolic blood pressure: 41.0 mmHg (CRITICALLY_LOW)",
			/* [156] */ "Clinical observation: (2022-06-20) Finding — Pulse: 64.0 beats/min (NORMAL)",
			/* [157] */ "Clinical observation: (2022-06-20) Finding — Arterial blood oxygen saturation (pulse oximeter): 95.6 %",
			/* [158] */ "Clinical observation: (2022-06-20) Finding — Weight (kg): 88.0 kg",
			/* [159] */ "Clinical observation: (2022-06-20) Finding — Respiratory rate: 13.0 breaths/min (NORMAL)",
			/* [160] */ "Clinical observation: (2022-06-20) Finding — Height (cm): 152.0 cm",
	};

	/**
	 * Same records as FOURTH_PATIENT_DATASET but with concept synonyms
	 * added to match how production OpenMRS serializes concept names.
	 * This is critical for testing keyword matching because synonyms
	 * introduce words like "blood" into records where the base concept
	 * name does not contain them (e.g. "Hemoglobin performed on blood").
	 */
	static final String[] FIFTH_PATIENT_DATASET = {
			/* [  1] */ "Clinical observation: (2026-02-28) Test — Haemoglobin (syn. Hb, Hemoglobin, Hemoglobin performed on blood): 15.8 g/dL (HIGH)",
			/* [  2] */ "Clinical observation: (2026-02-28) Assessment — Text of encounter note: Massa massa ultricies mi quis hendrerit. Consectetur libero id faucibus nisl tincidunt. Sed faucibus turpis in eu mi. Lobortis scelerisque fermentum dui faucibus in ornare. Scelerisque felis imperdiet proin fermentum. Faucibus vitae aliquet nec ullamcorper sit. Placerat in egestas erat imperdiet sed euismod nisi porta. Arcu felis bibendum ut tristique et egestas. Amet commodo nulla facilisi nullam vehicula ipsum a arcu. Viverra vitae congue eu consequat ac. Convallis a cras semper auctor neque vitae. Mauris a diam maecenas sed enim ut sem. Phasellus faucibus scelerisque eleifend donec. Quisque sagittis purus sit amet volutpat. Lorem dolor sed viverra ipsum nunc aliquet bibendum. Vitae congue eu consequat ac felis donec et. Purus sit amet volutpat consequat mauris nunc congue nisi vitae. Non quam lacus suspendisse faucibus. Tellus mauris a diam maecenas sed enim ut sem. Euismod in pellentesque massa placerat duis ultricies lacus.",
			/* [  3] */ "Medical condition: (2026-02-28) Condition: Zika virus disease. Status: ACTIVE",
			/* [  4] */ "Medical condition: (2026-02-28) Condition: Ovarian cyst (syn. Cyst of ovary). Status: ACTIVE",
			/* [  5] */ "Clinical diagnosis: (2026-02-28) Diagnosis: Zika virus disease. Certainty: PROVISIONAL. Rank: Primary",
			/* [  6] */ "Clinical diagnosis: (2026-02-28) Diagnosis: Ovarian cyst (syn. Cyst of ovary). Certainty: CONFIRMED. Rank: Primary",
			/* [  7] */ "Clinical observation: (2026-02-28) Finding — Temperature (c) (syn. Temp (c)): 36.7 DEG C (NORMAL)",
			/* [  8] */ "Clinical observation: (2026-02-28) Finding — Systolic blood pressure (syn. SBP): 145.0 mmHg (HIGH)",
			/* [  9] */ "Clinical observation: (2026-02-28) Finding — Diastolic blood pressure (syn. DBP): 76.0 mmHg (NORMAL)",
			/* [ 10] */ "Clinical observation: (2026-02-28) Finding — Pulse (syn. HR): 111.0 beats/min (CRITICALLY_HIGH)",
			/* [ 11] */ "Clinical observation: (2026-02-28) Finding — Arterial blood oxygen saturation (pulse oximeter) (syn. SpO2): 97.8 %",
			/* [ 12] */ "Clinical observation: (2026-02-28) Finding — Weight (kg) (syn. WT): 51.0 kg",
			/* [ 13] */ "Clinical observation: (2026-02-28) Finding — Respiratory rate (syn. RR): 18.0 breaths/min (NORMAL)",
			/* [ 14] */ "Clinical observation: (2026-02-28) Finding — Height (cm) (syn. HT): 176.0 cm",
			/* [ 15] */ "Clinical observation: (2026-02-27) LabSet — Prostate specific antigen test (syn. PSA test): Prostate specific antigen (PSA) measurement (ng/mL) (syn. PSA measurement): 132.5 ng/mL",
			/* [ 16] */ "Clinical observation: (2026-02-27) Test — Hemoglobin in umbilical cord blood (mg/mL): 99.4 mg/mL",
			/* [ 17] */ "Clinical observation: (2026-02-27) Assessment — Text of encounter note: Gravida in fermentum et sollicitudin. Nibh ipsum consequat nisl vel pretium lectus quam.",
			/* [ 18] */ "Medical condition: (2026-02-27) Condition: Wasting syndrome (syn. HIV wasting syndrome). Status: ACTIVE",
			/* [ 19] */ "Medical condition: (2026-02-27) Condition: Haemorrhagic disease of newborn (syn. Hemorrhagic disease of newborn). Status: ACTIVE",
			/* [ 20] */ "Medical condition: (2026-02-27) Condition: Psychosis. Status: ACTIVE",
			/* [ 21] */ "Clinical diagnosis: (2026-02-27) Diagnosis: Wasting syndrome (syn. HIV wasting syndrome). Certainty: PROVISIONAL. Rank: Primary",
			/* [ 22] */ "Clinical diagnosis: (2026-02-27) Diagnosis: Haemorrhagic disease of newborn (syn. Hemorrhagic disease of newborn). Certainty: CONFIRMED. Rank: Primary",
			/* [ 23] */ "Clinical diagnosis: (2026-02-27) Diagnosis: Psychosis. Certainty: CONFIRMED. Rank: Primary",
			/* [ 24] */ "Clinical observation: (2026-02-27) Finding — Temperature (c) (syn. Temp (c)): 38.1 DEG C (CRITICALLY_HIGH)",
			/* [ 25] */ "Clinical observation: (2026-02-27) Finding — Systolic blood pressure (syn. SBP): 111.0 mmHg (NORMAL)",
			/* [ 26] */ "Clinical observation: (2026-02-27) Finding — Diastolic blood pressure (syn. DBP): 40.0 mmHg (CRITICALLY_LOW)",
			/* [ 27] */ "Clinical observation: (2026-02-27) Finding — Pulse (syn. HR): 70.0 beats/min (NORMAL)",
			/* [ 28] */ "Clinical observation: (2026-02-27) Finding — Arterial blood oxygen saturation (pulse oximeter) (syn. SpO2): 89.6 % (CRITICALLY_LOW)",
			/* [ 29] */ "Clinical observation: (2026-02-27) Finding — Weight (kg) (syn. WT): 86.0 kg",
			/* [ 30] */ "Clinical observation: (2026-02-27) Finding — Respiratory rate (syn. RR): 22.0 breaths/min (HIGH)",
			/* [ 31] */ "Clinical observation: (2026-02-27) Finding — Height (cm) (syn. HT): 149.0 cm",
			/* [ 32] */ "Clinical observation: (2026-02-25) LabSet — Cerebrospinal fluid panel: Cerebrospinal fluid white blood cells (syn. CSF WBCs, Cerebrospinal fluid leukocytes): 59.0; Cerebrospinal fluid protein measurement: 24.7 mg/dL; Cerebrospinal fluid volume measurement (mL)): 112.3; Cerebrospinal fluid appearance: Greenish-yellow matter detected.; Cerebrospinal fluid glucose: 78.7 mg/dL; Cerebrospinal fluid red blood cells: 116.0",
			/* [ 33] */ "Clinical observation: (2026-02-25) Assessment — Text of encounter note: Neque convallis a cras semper auctor neque vitae. Proin nibh nisl condimentum id venenatis a condimentum vitae. Viverra accumsan in nisl nisi scelerisque eu ultrices vitae. Ac tincidunt vitae semper quis lectus nulla.",
			/* [ 34] */ "Medical condition: (2026-02-25) Condition: Enteroviral vesicular stomatitis with exanthem). Status: ACTIVE",
			/* [ 35] */ "Medical condition: (2026-02-25) Condition: Cerebrovascular Accident (I64). Status: ACTIVE",
			/* [ 36] */ "Clinical diagnosis: (2026-02-25) Diagnosis: Enteroviral vesicular stomatitis with exanthem). Certainty: PROVISIONAL. Rank: Primary",
			/* [ 37] */ "Clinical diagnosis: (2026-02-25) Diagnosis: Cerebrovascular Accident (I64). Certainty: CONFIRMED. Rank: Primary",
			/* [ 38] */ "Clinical observation: (2026-02-25) Finding — Temperature (c) (syn. Temp (c)): 36.4 DEG C (NORMAL)",
			/* [ 39] */ "Clinical observation: (2026-02-25) Finding — Systolic blood pressure (syn. SBP): 159.0 mmHg (HIGH)",
			/* [ 40] */ "Clinical observation: (2026-02-25) Finding — Diastolic blood pressure (syn. DBP): 72.0 mmHg (NORMAL)",
			/* [ 41] */ "Clinical observation: (2026-02-25) Finding — Pulse (syn. HR): 70.0 beats/min (NORMAL)",
			/* [ 42] */ "Clinical observation: (2026-02-25) Finding — Arterial blood oxygen saturation (pulse oximeter) (syn. SpO2): 96.8 %",
			/* [ 43] */ "Clinical observation: (2026-02-25) Finding — Weight (kg) (syn. WT): 64.0 kg",
			/* [ 44] */ "Clinical observation: (2026-02-25) Finding — Respiratory rate (syn. RR): 20.0 breaths/min (HIGH)",
			/* [ 45] */ "Clinical observation: (2026-02-25) Finding — Height (cm) (syn. HT): 193.0 cm",
			/* [ 46] */ "Clinical observation: (2026-02-24) Assessment — Text of encounter note: Lorem ipsum dolor sit amet, consectetur adipiscing elit, sed do eiusmod tempor incididunt ut labore et dolore magna aliqua. Vulputate enim nulla aliquet porttitor. Fermentum iaculis eu non diam phasellus vestibulum lorem sed. Orci ac auctor augue mauris augue neque. Fames ac turpis egestas sed tempus urna. Sit amet justo donec enim diam vulputate. Tortor aliquam nulla facilisi cras fermentum. Aliquet eget sit amet tellus. Elit ullamcorper dignissim cras tincidunt lobortis feugiat. Nisl tincidunt eget nullam non nisi est. Volutpat maecenas volutpat blandit aliquam etiam erat.",
			/* [ 47] */ "Medical condition: (2026-02-24) Condition: Melaena. Status: ACTIVE",
			/* [ 48] */ "Medical condition: (2026-02-24) Condition: Mental or behavioral disorder due to psychoactive substance. Status: ACTIVE",
			/* [ 49] */ "Medical condition: (2026-02-24) Condition: Cocaine abuse. Status: ACTIVE",
			/* [ 50] */ "Medical condition: (2026-02-24) Condition: Partial congenital absence of teeth. Status: ACTIVE",
			/* [ 51] */ "Clinical diagnosis: (2026-02-24) Diagnosis: Melaena. Certainty: PROVISIONAL. Rank: Primary",
			/* [ 52] */ "Clinical diagnosis: (2026-02-24) Diagnosis: Mental or behavioral disorder due to psychoactive substance. Certainty: CONFIRMED. Rank: Primary",
			/* [ 53] */ "Clinical diagnosis: (2026-02-24) Diagnosis: Cocaine abuse. Certainty: CONFIRMED. Rank: Primary",
			/* [ 54] */ "Clinical diagnosis: (2026-02-24) Diagnosis: Partial congenital absence of teeth. Certainty: CONFIRMED. Rank: Primary",
			/* [ 55] */ "Clinical observation: (2026-02-24) Finding — Temperature (c) (syn. Temp (c)): 38.5 DEG C (CRITICALLY_HIGH)",
			/* [ 56] */ "Clinical observation: (2026-02-24) Finding — Systolic blood pressure (syn. SBP): 112.0 mmHg (NORMAL)",
			/* [ 57] */ "Clinical observation: (2026-02-24) Finding — Diastolic blood pressure (syn. DBP): 41.0 mmHg (CRITICALLY_LOW)",
			/* [ 58] */ "Clinical observation: (2026-02-24) Finding — Pulse (syn. HR): 55.0 beats/min (LOW)",
			/* [ 59] */ "Clinical observation: (2026-02-24) Finding — Arterial blood oxygen saturation (pulse oximeter) (syn. SpO2): 92.5 % (LOW)",
			/* [ 60] */ "Clinical observation: (2026-02-24) Finding — Weight (kg) (syn. WT): 79.0 kg",
			/* [ 61] */ "Clinical observation: (2026-02-24) Finding — Respiratory rate (syn. RR): 14.0 breaths/min (NORMAL)",
			/* [ 62] */ "Clinical observation: (2026-02-24) Finding — Height (cm) (syn. HT): 183.0 cm",
			/* [ 63] */ "Clinical observation: (2025-08-02) Assessment — Text of encounter note: Montes nascetur ridiculus mus mauris vitae. Proin sagittis nisl rhoncus mattis. Pulvinar pellentesque habitant morbi tristique senectus et netus et malesuada. Urna cursus eget nunc scelerisque viverra mauris in aliquam sem.",
			/* [ 64] */ "Medical condition: (2025-08-02) Condition: Malignant tumor of base of tongue. Status: ACTIVE",
			/* [ 65] */ "Medical condition: (2025-08-02) Condition: Rash, Moderate. Status: ACTIVE",
			/* [ 66] */ "Clinical diagnosis: (2025-08-02) Diagnosis: Malignant tumor of base of tongue. Certainty: PROVISIONAL. Rank: Primary",
			/* [ 67] */ "Clinical diagnosis: (2025-08-02) Diagnosis: Rash, Moderate. Certainty: CONFIRMED. Rank: Primary",
			/* [ 68] */ "Clinical observation: (2025-08-02) Finding — Temperature (c) (syn. Temp (c)): 37.4 DEG C (NORMAL)",
			/* [ 69] */ "Clinical observation: (2025-08-02) Finding — Systolic blood pressure (syn. SBP): 151.0 mmHg (HIGH)",
			/* [ 70] */ "Clinical observation: (2025-08-02) Finding — Diastolic blood pressure (syn. DBP): 81.0 mmHg (HIGH)",
			/* [ 71] */ "Clinical observation: (2025-08-02) Finding — Pulse (syn. HR): 54.0 beats/min (LOW)",
			/* [ 72] */ "Clinical observation: (2025-08-02) Finding — Arterial blood oxygen saturation (pulse oximeter) (syn. SpO2): 88.1 % (CRITICALLY_LOW)",
			/* [ 73] */ "Clinical observation: (2025-08-02) Finding — Weight (kg) (syn. WT): 79.0 kg",
			/* [ 74] */ "Clinical observation: (2025-08-02) Finding — Respiratory rate (syn. RR): 18.0 breaths/min (NORMAL)",
			/* [ 75] */ "Clinical observation: (2025-08-02) Finding — Height (cm) (syn. HT): 192.0 cm",
			/* [ 76] */ "Clinical observation: (2025-06-26) Assessment — Text of encounter note: Lorem ipsum dolor sit amet, consectetur adipiscing elit, sed do eiusmod tempor incididunt ut labore et dolore magna aliqua. Vulputate enim nulla aliquet porttitor. Fermentum iaculis eu non diam phasellus vestibulum lorem sed. Orci ac auctor augue mauris augue neque. Fames ac turpis egestas sed tempus urna. Sit amet justo donec enim diam vulputate. Tortor aliquam nulla facilisi cras fermentum. Aliquet eget sit amet tellus. Elit ullamcorper dignissim cras tincidunt lobortis feugiat. Nisl tincidunt eget nullam non nisi est. Volutpat maecenas volutpat blandit aliquam etiam erat.",
			/* [ 77] */ "Medical condition: (2025-06-26) Condition: Intraoperative musculoskeletal disorder. Status: ACTIVE",
			/* [ 78] */ "Medical condition: (2025-06-26) Condition: Self-accusation. Status: ACTIVE",
			/* [ 79] */ "Clinical diagnosis: (2025-06-26) Diagnosis: Intraoperative musculoskeletal disorder. Certainty: PROVISIONAL. Rank: Primary",
			/* [ 80] */ "Clinical diagnosis: (2025-06-26) Diagnosis: Self-accusation. Certainty: CONFIRMED. Rank: Primary",
			/* [ 81] */ "Clinical observation: (2025-06-26) Finding — Temperature (c) (syn. Temp (c)): 36.3 DEG C (NORMAL)",
			/* [ 82] */ "Clinical observation: (2025-06-26) Finding — Systolic blood pressure (syn. SBP): 139.0 mmHg (HIGH)",
			/* [ 83] */ "Clinical observation: (2025-06-26) Finding — Diastolic blood pressure (syn. DBP): 52.0 mmHg (LOW)",
			/* [ 84] */ "Clinical observation: (2025-06-26) Finding — Pulse (syn. HR): 90.0 beats/min (NORMAL)",
			/* [ 85] */ "Clinical observation: (2025-06-26) Finding — Arterial blood oxygen saturation (pulse oximeter) (syn. SpO2): 92.1 % (LOW)",
			/* [ 86] */ "Clinical observation: (2025-06-26) Finding — Weight (kg) (syn. WT): 69.0 kg",
			/* [ 87] */ "Clinical observation: (2025-06-26) Finding — Respiratory rate (syn. RR): 18.0 breaths/min (NORMAL)",
			/* [ 88] */ "Clinical observation: (2025-06-26) Finding — Height (cm) (syn. HT): 175.0 cm",
			/* [ 89] */ "Clinical observation: (2024-11-22) LabSet — Glucose tolerance test 5 specimens (75g) panel: Glucose, serum 1 hr post 75g oral glucose (mg/dL): 74.0 mg/dL (NORMAL); Glucose, serum 4 hrs post 75g oral glucose (mg/dL): 98.0 mg/dL (NORMAL); Glucose tolerance test pre-glucose administration: 83.0 mg/dl (NORMAL); Glucose, serum 1/2 hr post 75g oral glucose (mg/dL): 118.0 mg/dL (NORMAL); Glucose, serum 3 hrs post 75g oral glucose (mg/dL): 88.0 mg/dL (NORMAL); Glucose, serum 2 hrs post 75g oral glucose (mg/dL): 110.0 mg/dL (NORMAL)",
			/* [ 90] */ "Clinical observation: (2024-11-22) Assessment — Text of encounter note: Neque convallis a cras semper auctor neque vitae. Proin nibh nisl condimentum id venenatis a condimentum vitae. Viverra accumsan in nisl nisi scelerisque eu ultrices vitae. Ac tincidunt vitae semper quis lectus nulla.",
			/* [ 91] */ "Medical condition: (2024-11-22) Condition: Hookworm disease. Status: ACTIVE",
			/* [ 92] */ "Medical condition: (2024-11-22) Condition: Failure of exfoliation of primary tooth. Status: ACTIVE",
			/* [ 93] */ "Medical condition: (2024-11-22) Condition: Crushing injury of thigh. Status: ACTIVE",
			/* [ 94] */ "Medical condition: (2024-11-22) Condition: Acquired short achilles tendon. Status: ACTIVE",
			/* [ 95] */ "Clinical diagnosis: (2024-11-22) Diagnosis: Hookworm disease. Certainty: PROVISIONAL. Rank: Primary",
			/* [ 96] */ "Clinical diagnosis: (2024-11-22) Diagnosis: Failure of exfoliation of primary tooth. Certainty: CONFIRMED. Rank: Primary",
			/* [ 97] */ "Clinical diagnosis: (2024-11-22) Diagnosis: Crushing injury of thigh. Certainty: CONFIRMED. Rank: Primary",
			/* [ 98] */ "Clinical diagnosis: (2024-11-22) Diagnosis: Acquired short achilles tendon. Certainty: CONFIRMED. Rank: Primary",
			/* [ 99] */ "Clinical observation: (2024-11-22) Finding — Temperature (c) (syn. Temp (c)): 36.3 DEG C (NORMAL)",
			/* [100] */ "Clinical observation: (2024-11-22) Finding — Systolic blood pressure (syn. SBP): 113.0 mmHg (NORMAL)",
			/* [101] */ "Clinical observation: (2024-11-22) Finding — Diastolic blood pressure (syn. DBP): 78.0 mmHg (NORMAL)",
			/* [102] */ "Clinical observation: (2024-11-22) Finding — Pulse (syn. HR): 63.0 beats/min (NORMAL)",
			/* [103] */ "Clinical observation: (2024-11-22) Finding — Arterial blood oxygen saturation (pulse oximeter) (syn. SpO2): 97.6 %",
			/* [104] */ "Clinical observation: (2024-11-22) Finding — Weight (kg) (syn. WT): 83.0 kg",
			/* [105] */ "Clinical observation: (2024-11-22) Finding — Respiratory rate (syn. RR): 9.0 breaths/min (CRITICALLY_LOW)",
			/* [106] */ "Clinical observation: (2024-11-22) Finding — Height (cm) (syn. HT): 179.0 cm",
			/* [107] */ "Clinical observation: (2024-03-01) Assessment — Text of encounter note: Vel turpis nunc eget lorem dolor sed. Cras semper auctor neque vitae. Mauris in aliquam sem fringilla ut morbi tincidunt augue interdum. Lectus proin nibh nisl condimentum id venenatis a condimentum vitae. Justo donec enim diam vulputate ut pharetra. Et ultrices neque ornare aenean euismod. Lobortis feugiat vivamus at augue eget. Et malesuada fames ac turpis egestas maecenas. Massa enim nec dui nunc mattis enim ut. Sit amet purus gravida quis blandit. Pellentesque pulvinar pellentesque habitant morbi tristique senectus. Turpis egestas sed tempus urna et pharetra. Nibh tortor id aliquet lectus proin nibh. Sed adipiscing diam donec adipiscing. Tortor condimentum lacinia quis vel eros donec ac odio. Accumsan in nisl nisi scelerisque eu ultrices vitae auctor eu. Consectetur adipiscing elit duis tristique sollicitudin nibh sit amet commodo. Nibh cras pulvinar mattis nunc. Mattis vulputate enim nulla aliquet porttitor lacus luctus. Et ligula ullamcorper malesuada proin libero nunc consequat.",
			/* [108] */ "Medical condition: (2024-03-01) Condition: Nonunion of fracture. Status: ACTIVE",
			/* [109] */ "Medical condition: (2024-03-01) Condition: Human immunodeficiency virus (HIV) disease infection). Status: ACTIVE",
			/* [110] */ "Clinical diagnosis: (2024-03-01) Diagnosis: Nonunion of fracture. Certainty: PROVISIONAL. Rank: Primary",
			/* [111] */ "Clinical diagnosis: (2024-03-01) Diagnosis: Human immunodeficiency virus (HIV) disease infection). Certainty: CONFIRMED. Rank: Primary",
			/* [112] */ "Clinical observation: (2024-03-01) Finding — Temperature (c) (syn. Temp (c)): 36.7 DEG C (NORMAL)",
			/* [113] */ "Clinical observation: (2024-03-01) Finding — Systolic blood pressure (syn. SBP): 146.0 mmHg (HIGH)",
			/* [114] */ "Clinical observation: (2024-03-01) Finding — Diastolic blood pressure (syn. DBP): 55.0 mmHg (LOW)",
			/* [115] */ "Clinical observation: (2024-03-01) Finding — Pulse (syn. HR): 79.0 beats/min (NORMAL)",
			/* [116] */ "Clinical observation: (2024-03-01) Finding — Arterial blood oxygen saturation (pulse oximeter) (syn. SpO2): 90.1 % (LOW)",
			/* [117] */ "Clinical observation: (2024-03-01) Finding — Weight (kg) (syn. WT): 62.0 kg",
			/* [118] */ "Clinical observation: (2024-03-01) Finding — Respiratory rate (syn. RR): 18.0 breaths/min (NORMAL)",
			/* [119] */ "Clinical observation: (2024-03-01) Finding — Height (cm) (syn. HT): 162.0 cm",
			/* [120] */ "Clinical observation: (2023-12-19) Assessment — Text of encounter note: Maecenas pharetra convallis posuere morbi leo urna molestie at elementum. Ullamcorper velit sed ullamcorper morbi tincidunt ornare. Malesuada fames ac turpis egestas sed tempus urna et. Morbi leo urna molestie at elementum eu facilisis sed odio. Eu facilisis sed odio morbi quis commodo odio aenean. Faucibus a pellentesque sit amet porttitor eget dolor. Suscipit adipiscing bibendum est ultricies. Mattis ullamcorper velit sed ullamcorper morbi tincidunt ornare massa eget. Nulla posuere sollicitudin aliquam ultrices sagittis orci a scelerisque purus. Libero id faucibus nisl tincidunt eget nullam non nisi. Quis viverra nibh cras pulvinar mattis nunc sed blandit libero. Commodo quis imperdiet massa tincidunt nunc pulvinar sapien et ligula. Natoque penatibus et magnis dis parturient. Fames ac turpis egestas sed tempus urna et pharetra pharetra. Sem nulla pharetra diam sit amet nisl suscipit. Felis bibendum ut tristique et egestas quis.",
			/* [121] */ "Medical condition: (2023-12-19) Condition: Benign neoplasm of scrotum. Status: ACTIVE",
			/* [122] */ "Medical condition: (2023-12-19) Condition: Substance Addiction. Status: ACTIVE",
			/* [123] */ "Medical condition: (2023-12-19) Condition: Chronic gingivitis. Status: ACTIVE",
			/* [124] */ "Clinical diagnosis: (2023-12-19) Diagnosis: Benign neoplasm of scrotum. Certainty: PROVISIONAL. Rank: Primary",
			/* [125] */ "Clinical diagnosis: (2023-12-19) Diagnosis: Substance Addiction. Certainty: CONFIRMED. Rank: Primary",
			/* [126] */ "Clinical diagnosis: (2023-12-19) Diagnosis: Chronic gingivitis. Certainty: CONFIRMED. Rank: Primary",
			/* [127] */ "Clinical observation: (2023-12-19) Finding — Temperature (c) (syn. Temp (c)): 38.2 DEG C (CRITICALLY_HIGH)",
			/* [128] */ "Clinical observation: (2023-12-19) Finding — Systolic blood pressure (syn. SBP): 115.0 mmHg (NORMAL)",
			/* [129] */ "Clinical observation: (2023-12-19) Finding — Diastolic blood pressure (syn. DBP): 79.0 mmHg (NORMAL)",
			/* [130] */ "Clinical observation: (2023-12-19) Finding — Pulse (syn. HR): 57.0 beats/min (LOW)",
			/* [131] */ "Clinical observation: (2023-12-19) Finding — Arterial blood oxygen saturation (pulse oximeter) (syn. SpO2): 91.0 % (LOW)",
			/* [132] */ "Clinical observation: (2023-12-19) Finding — Weight (kg) (syn. WT): 92.0 kg",
			/* [133] */ "Clinical observation: (2023-12-19) Finding — Respiratory rate (syn. RR): 15.0 breaths/min (NORMAL)",
			/* [134] */ "Clinical observation: (2023-12-19) Finding — Height (cm) (syn. HT): 160.0 cm",
			/* [135] */ "Clinical observation: (2023-05-04) LabSet — Basic metabolic panel (syn. BMP, Chem8): Serum glucose (mmol) (syn. Glu): 0.9 mmol/L; Serum calcium (syn. Ca): 7.7 mg/dL; Serum sodium: 146.0 mmol/L (NORMAL); Blood urea nitrogen: 82.9 mmol/L; Serum potassium: 3.9 mmol/L (NORMAL); Serum chloride: 99.0 mmol/L (NORMAL); Serum glucose: 118.0 mg/dl; Serum carbon dioxide: 26.0 mmol/L (NORMAL); Serum creatinine (umol/L): 146.5 umol/L",
			/* [136] */ "Clinical observation: (2023-05-04) Assessment — Text of encounter note: Faucibus et molestie ac feugiat sed lectus. Condimentum lacinia quis vel eros donec ac. Urna porttitor rhoncus dolor purus.",
			/* [137] */ "Medical condition: (2023-05-04) Condition: Self-Induced Abortion. Status: ACTIVE",
			/* [138] */ "Medical condition: (2023-05-04) Condition: Gonococcal arthritis. Status: ACTIVE",
			/* [139] */ "Clinical diagnosis: (2023-05-04) Diagnosis: Self-Induced Abortion. Certainty: PROVISIONAL. Rank: Primary",
			/* [140] */ "Clinical diagnosis: (2023-05-04) Diagnosis: Gonococcal arthritis. Certainty: CONFIRMED. Rank: Primary",
			/* [141] */ "Clinical observation: (2023-05-04) Finding — Temperature (c) (syn. Temp (c)): 36.6 DEG C (NORMAL)",
			/* [142] */ "Clinical observation: (2023-05-04) Finding — Systolic blood pressure (syn. SBP): 123.0 mmHg (HIGH)",
			/* [143] */ "Clinical observation: (2023-05-04) Finding — Diastolic blood pressure (syn. DBP): 81.0 mmHg (HIGH)",
			/* [144] */ "Clinical observation: (2023-05-04) Finding — Pulse (syn. HR): 54.0 beats/min (LOW)",
			/* [145] */ "Clinical observation: (2023-05-04) Finding — Arterial blood oxygen saturation (pulse oximeter) (syn. SpO2): 95.0 %",
			/* [146] */ "Clinical observation: (2023-05-04) Finding — Weight (kg) (syn. WT): 91.0 kg",
			/* [147] */ "Clinical observation: (2023-05-04) Finding — Respiratory rate (syn. RR): 9.0 breaths/min (CRITICALLY_LOW)",
			/* [148] */ "Clinical observation: (2023-05-04) Finding — Height (cm) (syn. HT): 185.0 cm",
			/* [149] */ "Program enrollment: (2023-05-04) Program: PMTCT (Program for prevention of Maternal to Child HIV transmission). Enrolled: 2023-05-04. Status: Active. Current state: Lost to follow-up",
			/* [150] */ "Clinical observation: (2022-06-20) Assessment — Text of encounter note: Eget aliquet nibh praesent tristique. Lectus arcu bibendum at varius vel. Eget duis at tellus at urna condimentum mattis pellentesque. Quisque egestas diam in arcu cursus euismod quis viverra. Tellus orci ac auctor augue mauris augue. Id nibh tortor id aliquet lectus proin. Et ultrices neque ornare aenean euismod elementum nisi quis eleifend. Morbi leo urna molestie at elementum eu. Mauris ultrices eros in cursus turpis massa tincidunt. Arcu risus quis varius quam quisque id diam vel.\n\nScelerisque eu ultrices vitae auctor eu augue. Sem integer vitae justo eget magna fermentum iaculis eu. Diam volutpat commodo sed egestas egestas. Sit amet facilisis magna etiam tempor orci eu lobortis. Nam aliquam sem et tortor consequat. Id cursus metus aliquam eleifend mi in nulla posuere sollicitudin. Tempus quam pellentesque nec nam aliquam sem. Odio eu feugiat pretium nibh. Elementum eu facilisis sed odio morbi quis commodo odio aenean. Sagittis orci a scelerisque purus semper eget. Duis ut diam quam nulla porttitor. Vitae auctor eu augue ut lectus. Tincidunt nunc pulvinar sapien et. Est pellentesque elit ullamcorper dignissim. Etiam non quam lacus suspendisse faucibus interdum posuere lorem ipsum. Tristique et egestas quis ipsum suspendisse. Egestas fringilla phasellus faucibus scelerisque eleifend. Sollicitudin aliquam ultrices sagittis orci a scelerisque. Semper risus in hendrerit gravida.",
			/* [151] */ "Medical condition: (2022-06-20) Condition: Non-severe event supposed to be attributable to vaccination and immunization (ESAVI). Status: ACTIVE",
			/* [152] */ "Clinical diagnosis: (2022-06-20) Diagnosis: Non-severe event supposed to be attributable to vaccination and immunization (ESAVI). Certainty: PROVISIONAL. Rank: Primary",
			/* [153] */ "Clinical observation: (2022-06-20) Finding — Temperature (c) (syn. Temp (c)): 36.5 DEG C (NORMAL)",
			/* [154] */ "Clinical observation: (2022-06-20) Finding — Systolic blood pressure (syn. SBP): 156.0 mmHg (HIGH)",
			/* [155] */ "Clinical observation: (2022-06-20) Finding — Diastolic blood pressure (syn. DBP): 41.0 mmHg (CRITICALLY_LOW)",
			/* [156] */ "Clinical observation: (2022-06-20) Finding — Pulse (syn. HR): 64.0 beats/min (NORMAL)",
			/* [157] */ "Clinical observation: (2022-06-20) Finding — Arterial blood oxygen saturation (pulse oximeter) (syn. SpO2): 95.6 %",
			/* [158] */ "Clinical observation: (2022-06-20) Finding — Weight (kg) (syn. WT): 88.0 kg",
			/* [159] */ "Clinical observation: (2022-06-20) Finding — Respiratory rate (syn. RR): 13.0 breaths/min (NORMAL)",
			/* [160] */ "Clinical observation: (2022-06-20) Finding — Height (cm) (syn. HT): 152.0 cm",
	};

	private static final String[] DATASET_PREFIXES = {
			"Medication prescription: ",
			"Lab test order: ",
			"Clinical observation: ",
			"Clinical diagnosis: ",
			"Medical condition: ",
			"Patient allergy: ",
			"Program enrollment: ",
			"Medication dispensed: "
	};

	private static final java.util.regex.Pattern DATE_PREFIX_PATTERN =
			java.util.regex.Pattern.compile("^\\(\\d{4}-\\d{2}-\\d{2}\\)\\s*");

	private TestDatasetHelper() {
	}

	/**
	 * Strips the dataset-format prefix and optional date to recover the raw
	 * serializer output (the record's {@code text} as produced by the
	 * {@code *TextSerializer}s).
	 *
	 * <p>Dataset format: {@code "Clinical observation: (2025-10-30) Test — Weight (kg): 94.0 kg"}
	 * <br>Production text: {@code "Test — Weight (kg): 94.0 kg"}
	 */
	static String stripDatasetPrefixAndDate(String datasetText) {
		String text = datasetText;
		for (String prefix : DATASET_PREFIXES) {
			if (text.startsWith(prefix)) {
				text = text.substring(prefix.length());
				break;
			}
		}
		java.util.regex.Matcher m = DATE_PREFIX_PATTERN.matcher(text);
		if (m.find()) {
			text = text.substring(m.end());
		}
		return text;
	}

	/**
	 * Infers resource type from the serialized text prefix, matching the
	 * resource-type prefixes querystore's serializers assign.
	 */
	static String inferResourceType(String text) {
		if (text.startsWith("Medication prescription:")
				|| text.startsWith("Lab test order:")) {
			return ChartSearchAiConstants.RESOURCE_TYPE_ORDER;
		}
		if (text.startsWith("Medical condition:")) {
			return ChartSearchAiConstants.RESOURCE_TYPE_CONDITION;
		}
		if (text.startsWith("Clinical diagnosis:")) {
			return ChartSearchAiConstants.RESOURCE_TYPE_DIAGNOSIS;
		}
		if (text.startsWith("Patient allergy:")) {
			return ChartSearchAiConstants.RESOURCE_TYPE_ALLERGY;
		}
		if (text.startsWith("Program enrollment:")) {
			return ChartSearchAiConstants.RESOURCE_TYPE_PROGRAM;
		}
		if (text.startsWith("Medication dispensed:")) {
			return ChartSearchAiConstants.RESOURCE_TYPE_MEDICATION_DISPENSE;
		}
		return ChartSearchAiConstants.RESOURCE_TYPE_OBS;
	}


	/**
	 * Converts a raw dataset array into {@link SerializedRecord} objects.
	 */
	static List<SerializedRecord> toSerializedRecords(String[] dataset) {
		return toSerializedRecords(dataset, null);
	}

	/**
	 * Converts a raw dataset array into {@link SerializedRecord} objects
	 * with optional concept-set category hints per record.
	 */
	static List<SerializedRecord> toSerializedRecords(String[] dataset,
			Map<Integer, List<String>> categoryHintsMap) {
		List<SerializedRecord> records = new ArrayList<>();
		for (int i = 0; i < dataset.length; i++) {
			String resourceType = inferResourceType(dataset[i]);
			String textContent = stripDatasetPrefixAndDate(dataset[i]);
			List<String> hints = categoryHintsMap != null
					? categoryHintsMap.getOrDefault(i, Collections.<String>emptyList())
					: Collections.<String>emptyList();
			records.add(new SerializedRecord(resourceType, uuidForIndex(i), textContent, null, hints));
		}
		return records;
	}

	/**
	 * Deterministic UUID literal derived from a dataset index. Keeps fixture
	 * identifiers sortable and recognizable in failure messages — the trailing
	 * digits match the dataset index. Used in place of the old integer
	 * resource ID throughout test fixtures.
	 */
	static String uuidForIndex(int index) {
		return String.format("00000000-0000-0000-0000-%012d", index);
	}

	/**
	 * Inverse of {@link #uuidForIndex}: extracts the dataset index from a
	 * fixture UUID literal. Used by eval baselines that store dataset indices
	 * but must compare against actual UUIDs after the migration.
	 */
	static int indexForUuid(String uuid) {
		int dash = uuid.lastIndexOf('-');
		return Integer.parseInt(uuid.substring(dash + 1));
	}

	/**
	 * Concept-set category hints for FULL_PATIENT_DATASET Condition and
	 * Diagnosis records. Represents what {@code extractCategoryHints}
	 * returns when the OpenMRS dictionary has concept_set memberships for
	 * clinical categories (STD, Infectious disease, Cardiovascular, etc.).
	 * Shared by cross-query regression tests and the eval harness.
	 */
	static final Map<Integer, List<String>> FULL_DATASET_CATEGORY_HINTS;
	static final Map<Integer, List<String>> SECOND_DATASET_CATEGORY_HINTS;
	static final Map<Integer, List<String>> THIRD_DATASET_CATEGORY_HINTS;
	static final Map<Integer, List<String>> FOURTH_DATASET_CATEGORY_HINTS;
	static final Map<Integer, List<String>> FIFTH_DATASET_CATEGORY_HINTS;

	static {
		Map<Integer, List<String>> m;

		// FULL_PATIENT_DATASET — Conditions + Diagnoses
		m = new HashMap<Integer, List<String>>();
		// Obs records that reference diagnosis concepts via valueCoded
		// Program enrollments
		// Vital sign obs
		for (int vi : new int[]{14, 15, 16, 17, 18, 22, 23, 24, 25, 26, 27, 28, 31, 32, 33, 34, 36, 37, 38, 43, 44, 45, 46, 47, 48, 57, 58, 59, 60, 63, 64, 65, 73, 74, 75, 76, 77, 78, 79, 80, 81, 82, 83, 84, 93, 94, 95, 96, 97, 98, 100, 101, 102, 103, 104, 107, 108, 109, 111, 112, 113, 114, 115, 116, 117, 124, 125, 126, 128, 129, 130, 131, 132, 133, 136, 137, 138, 143, 144, 145, 147, 148, 149, 150, 151, 152}) {
			if (!m.containsKey(vi)) { m.put(vi, Arrays.asList("Vital signs")); }
		}
		FULL_DATASET_CATEGORY_HINTS = Collections.unmodifiableMap(m);

		// SECOND_PATIENT_DATASET
		m = new HashMap<Integer, List<String>>();
		for (int vi : new int[]{5, 6, 7, 8, 9, 10, 11, 12, 20, 21, 22, 23, 24, 25, 26, 27, 35, 36, 37, 38, 39, 40, 41, 42, 46, 47, 48, 49, 50, 51, 52, 53, 59, 60, 61, 62, 63, 64, 65, 66}) {
			if (!m.containsKey(vi)) { m.put(vi, Arrays.asList("Vital signs")); }
		}
		// Vital sign obs
		for (int vi : new int[]{5, 6, 7, 8, 9, 10, 11, 12, 20, 21, 22, 23, 24, 25, 26, 27, 35, 36, 37, 38, 39, 40, 41, 42, 46, 47, 48, 49, 50, 51, 52, 53, 59, 60, 61, 62, 63, 64, 65, 66}) {
			if (!m.containsKey(vi)) { m.put(vi, Arrays.asList("Vital signs")); }
		}
		SECOND_DATASET_CATEGORY_HINTS = Collections.unmodifiableMap(m);

		// THIRD_PATIENT_DATASET
		m = new HashMap<Integer, List<String>>();
		for (int vi : new int[]{6, 7, 8, 9, 10, 11, 12, 13, 25, 26, 27, 28, 29, 30, 31, 32, 36, 37, 38, 39, 40, 41, 42, 43, 56, 57, 58, 59, 60, 61, 62, 63, 75, 76, 77, 78, 79, 80, 81, 82, 95, 96, 97, 98, 99, 100, 101, 102, 120, 121, 122, 123, 124, 125, 126, 127, 131, 132, 133, 134, 135, 137, 139, 140, 141, 142, 143, 144, 145, 149, 150, 151, 152, 153, 154, 158, 159}) {
			if (!m.containsKey(vi)) { m.put(vi, Arrays.asList("Vital signs")); }
		}
		// Vital sign obs
		for (int vi : new int[]{6, 7, 8, 9, 10, 11, 12, 13, 25, 26, 27, 28, 29, 30, 31, 32, 36, 37, 38, 39, 40, 41, 42, 43, 56, 57, 58, 59, 60, 61, 62, 63, 75, 76, 77, 78, 79, 80, 81, 82, 95, 96, 97, 98, 99, 100, 101, 102, 120, 121, 122, 123, 124, 125, 126, 127, 131, 132, 133, 134, 135, 137, 139, 140, 141, 142, 143, 144, 145, 149, 150, 151, 152, 153, 154, 158, 159}) {
			if (!m.containsKey(vi)) { m.put(vi, Arrays.asList("Vital signs")); }
		}
		THIRD_DATASET_CATEGORY_HINTS = Collections.unmodifiableMap(m);

		// FOURTH_PATIENT_DATASET
		m = new HashMap<Integer, List<String>>();
		for (int vi : new int[]{6, 7, 8, 9, 10, 11, 12, 13, 23, 24, 25, 26, 27, 28, 29, 30, 37, 38, 39, 40, 41, 42, 43, 44, 54, 55, 56, 57, 58, 59, 60, 61, 67, 68, 69, 70, 71, 72, 73, 74, 80, 81, 82, 83, 84, 85, 86, 87, 98, 99, 100, 101, 102, 103, 104, 105, 111, 112, 113, 114, 115, 116, 117, 118, 126, 127, 128, 129, 130, 131, 132, 133, 140, 141, 142, 143, 144, 145, 146, 147, 152, 153, 154, 155, 156, 157, 158, 159}) {
			if (!m.containsKey(vi)) { m.put(vi, Arrays.asList("Vital signs")); }
		}
		// Vital sign obs
		for (int vi : new int[]{6, 7, 8, 9, 10, 11, 12, 13, 23, 24, 25, 26, 27, 28, 29, 30, 37, 38, 39, 40, 41, 42, 43, 44, 54, 55, 56, 57, 58, 59, 60, 61, 67, 68, 69, 70, 71, 72, 73, 74, 80, 81, 82, 83, 84, 85, 86, 87, 98, 99, 100, 101, 102, 103, 104, 105, 111, 112, 113, 114, 115, 116, 117, 118, 126, 127, 128, 129, 130, 131, 132, 133, 140, 141, 142, 143, 144, 145, 146, 147, 152, 153, 154, 155, 156, 157, 158, 159}) {
			if (!m.containsKey(vi)) { m.put(vi, Arrays.asList("Vital signs")); }
		}
		FOURTH_DATASET_CATEGORY_HINTS = Collections.unmodifiableMap(m);

		// FIFTH_PATIENT_DATASET (same structure as FOURTH)
		m = new HashMap<Integer, List<String>>();
		for (int vi : new int[]{6, 7, 8, 9, 10, 11, 12, 13, 23, 24, 25, 26, 27, 28, 29, 30, 37, 38, 39, 40, 41, 42, 43, 44, 54, 55, 56, 57, 58, 59, 60, 61, 67, 68, 69, 70, 71, 72, 73, 74, 80, 81, 82, 83, 84, 85, 86, 87, 98, 99, 100, 101, 102, 103, 104, 105, 111, 112, 113, 114, 115, 116, 117, 118, 126, 127, 128, 129, 130, 131, 132, 133, 140, 141, 142, 143, 144, 145, 146, 147, 152, 153, 154, 155, 156, 157, 158, 159}) {
			if (!m.containsKey(vi)) { m.put(vi, Arrays.asList("Vital signs")); }
		}
		// Vital sign obs
		for (int vi : new int[]{6, 7, 8, 9, 10, 11, 12, 13, 23, 24, 25, 26, 27, 28, 29, 30, 37, 38, 39, 40, 41, 42, 43, 44, 54, 55, 56, 57, 58, 59, 60, 61, 67, 68, 69, 70, 71, 72, 73, 74, 80, 81, 82, 83, 84, 85, 86, 87, 98, 99, 100, 101, 102, 103, 104, 105, 111, 112, 113, 114, 115, 116, 117, 118, 126, 127, 128, 129, 130, 131, 132, 133, 140, 141, 142, 143, 144, 145, 146, 147, 152, 153, 154, 155, 156, 157, 158, 159}) {
			if (!m.containsKey(vi)) { m.put(vi, Arrays.asList("Vital signs")); }
		}
		FIFTH_DATASET_CATEGORY_HINTS = Collections.unmodifiableMap(m);
	}
}

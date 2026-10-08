package org.openmrs.module.chartsearchai.api.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

public class QueryPreprocessorStopwordsTest {

    @Test
    public void stripQueryStopwords_shouldPreserveCurrentAndLatestAsDistinctTerms() {
        assertEquals(
                "current cd4 count",
                LlmInferenceService.stripQueryStopwords("What is the current CD4 Count?"));

        assertEquals(
                "latest cd4 count",
                LlmInferenceService.stripQueryStopwords("What is the latest CD4 Count?"));
    }
}

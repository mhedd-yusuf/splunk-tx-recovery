package com.example.txrecovery.adapter.splunk;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.Map;

/**
 * Splunk {@code GET /services/search/jobs/{sid}/results?output_mode=json}
 * response. Each result is a free-form map keyed by the columns produced by the
 * SPL {@code | table ...} clause.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SplunkResultsResponse(List<Map<String, Object>> results) {
}

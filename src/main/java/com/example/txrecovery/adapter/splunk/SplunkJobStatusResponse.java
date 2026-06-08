package com.example.txrecovery.adapter.splunk;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Splunk {@code GET /services/search/jobs/{sid}?output_mode=json} response.
 * Splunk wraps job state in {@code entry[0].content} — we extract the relevant
 * fields and ignore the rest.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SplunkJobStatusResponse(List<Entry> entry) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Entry(Content content) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Content(
            @JsonProperty("isDone") boolean isDone,
            @JsonProperty("dispatchState") String dispatchState,
            @JsonProperty("resultCount") long resultCount
    ) {
    }

    /** True only when Splunk has finished collecting results for the job. */
    public boolean isDone() {
        return entry != null
                && !entry.isEmpty()
                && entry.get(0).content() != null
                && entry.get(0).content().isDone();
    }
}

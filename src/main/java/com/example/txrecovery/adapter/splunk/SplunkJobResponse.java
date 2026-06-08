package com.example.txrecovery.adapter.splunk;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Splunk {@code POST /services/search/jobs?output_mode=json} response. */
public record SplunkJobResponse(@JsonProperty("sid") String sid) {
}

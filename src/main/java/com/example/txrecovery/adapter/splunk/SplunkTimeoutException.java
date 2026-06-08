package com.example.txrecovery.adapter.splunk;

/** Thrown when polling for a Splunk search job exceeds {@code splunk.poll-timeout}. */
public class SplunkTimeoutException extends RuntimeException {
    public SplunkTimeoutException(String message) {
        super(message);
    }
}

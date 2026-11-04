package io.catalogsentinel.contracts;

/** Names of SQS message attributes used across producers and consumers. */
public final class MessageAttributes {

    public static final String EVENT_TYPE = "eventType";
    public static final String SCHEMA_VERSION = "schemaVersion";
    public static final String CORRELATION_ID = "correlationId";
    public static final String EVENT_ID = "eventId";
    /** Incremented each time an operator replays a message from a dead-letter queue. */
    public static final String REPLAY_COUNT = "replayCount";
    /** Set when a consumer dead-letters a message explicitly (poison message), with the failure reason. */
    public static final String FAILURE_REASON = "failureReason";

    private MessageAttributes() {
    }
}

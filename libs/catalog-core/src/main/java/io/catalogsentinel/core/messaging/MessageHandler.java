package io.catalogsentinel.core.messaging;

import io.catalogsentinel.core.messaging.QueueClient.ReceivedMessage;

/** Processes one message and says what to do with it. Exceptions are classified by the consumer. */
@FunctionalInterface
public interface MessageHandler {

    Outcome handle(ReceivedMessage message);

    /** What the consumer should do with a message after handling. */
    sealed interface Outcome permits Ack, Retry, DeadLetter {
    }

    /** Done; delete the message. */
    record Ack(String detail) implements Outcome {
    }

    /** Transient problem; make the message visible again after a backoff. */
    record Retry(String reason) implements Outcome {
    }

    /** Permanent problem (poison message); move it to the dead-letter queue immediately. */
    record DeadLetter(String reason) implements Outcome {
    }

    static Outcome ack(String detail) {
        return new Ack(detail);
    }

    static Outcome retry(String reason) {
        return new Retry(reason);
    }

    static Outcome deadLetter(String reason) {
        return new DeadLetter(reason);
    }
}

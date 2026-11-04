package io.catalogsentinel.core.messaging;

import io.catalogsentinel.core.json.JsonCodec;
import io.catalogsentinel.domain.common.DomainValidationException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionException;

import java.sql.SQLTransientException;

/**
 * Decides whether an exception thrown by a handler is transient (retry later) or permanent (dead-letter now).
 * Retrying a malformed message wastes capacity and delays every message behind it; dead-lettering on a
 * database blip loses work into a manual-replay queue. Unknown exceptions are treated as transient, bounded by
 * the consumer's maximum attempts.
 */
public final class FailureClassifier {

    private FailureClassifier() {
    }

    public static boolean isTransient(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof DomainValidationException || c instanceof JsonCodec.JsonCodecException
                    || c instanceof IllegalArgumentException) {
                return false;
            }
            if (c instanceof TransientDataAccessException || c instanceof DataAccessResourceFailureException
                    || c instanceof CannotGetJdbcConnectionException || c instanceof QueryTimeoutException
                    || c instanceof CannotCreateTransactionException || c instanceof TransactionException
                    || c instanceof SQLTransientException || c instanceof java.net.ConnectException) {
                return true;
            }
        }
        return true;
    }

    /** True for infrastructure failures that suggest the dependency itself is down (used to pause polling). */
    public static boolean isDependencyOutage(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof CannotGetJdbcConnectionException || c instanceof DataAccessResourceFailureException
                    || c instanceof CannotCreateTransactionException || c instanceof java.net.ConnectException) {
                return true;
            }
        }
        return false;
    }
}

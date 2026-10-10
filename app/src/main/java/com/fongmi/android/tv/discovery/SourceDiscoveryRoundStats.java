package com.fongmi.android.tv.discovery;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeoutException;

/** Aggregate debug counters only. Never stores source identity, metadata text or exception messages. */
final class SourceDiscoveryRoundStats {
    enum ErrorType {
        TimeoutException, SocketTimeoutException, InterruptedIOException, IOException,
        CancellationException, RejectedExecutionException, ExecutionException, RuntimeException, Exception
    }

    private int rawItems, rawAdultEvidence, acceptedItems, rejectedItems, unknownKindRejected;
    private final Map<ErrorType, Integer> errors = new EnumMap<>(ErrorType.class);

    static ErrorType errorType(Throwable error) {
        if (error instanceof ExecutionException && error.getCause() != null) return errorType(error.getCause());
        if (error instanceof SocketTimeoutException) return ErrorType.SocketTimeoutException;
        if (error instanceof InterruptedIOException) return ErrorType.InterruptedIOException;
        if (error instanceof IOException) return ErrorType.IOException;
        if (error instanceof TimeoutException) return ErrorType.TimeoutException;
        if (error instanceof CancellationException) return ErrorType.CancellationException;
        if (error instanceof RejectedExecutionException) return ErrorType.RejectedExecutionException;
        if (error instanceof ExecutionException) return ErrorType.ExecutionException;
        if (error instanceof RuntimeException) return ErrorType.RuntimeException;
        return ErrorType.Exception;
    }

    void error(ErrorType type) { if (type != null) errors.merge(type, 1, Integer::sum); }

    void item(boolean adultEvidence, boolean accepted, boolean unknownKind) {
        rawItems++;
        if (adultEvidence) rawAdultEvidence++;
        if (accepted) acceptedItems++;
        else {
            rejectedItems++;
            if (unknownKind) unknownKindRejected++;
        }
    }

    JsonObject summary(int tab, boolean adult, long round, boolean retry, int configured,
                       int initialized, int failed, int unsupported, int categories, int declaredAdultCategories,
                       int explicitQueries, int fallbackQueries, int itemKindQueries, int visible, boolean more) {
        JsonObject value = new JsonObject();
        value.addProperty("tab", tab); value.addProperty("adult", adult); value.addProperty("round", round);
        value.addProperty("retry", retry); value.addProperty("configured_sources", configured);
        value.addProperty("initialized_sources", initialized); value.addProperty("failed_sources", failed);
        value.addProperty("unsupported_sources", unsupported); value.addProperty("declared_categories", categories);
        value.addProperty("adult_categories", declaredAdultCategories); value.addProperty("explicit_queries", explicitQueries);
        value.addProperty("fallback_queries", fallbackQueries); value.addProperty("item_kind_required_queries", itemKindQueries);
        value.addProperty("round_raw_items", rawItems); value.addProperty("round_raw_adult_item_evidence", rawAdultEvidence);
        value.addProperty("round_accepted_items", acceptedItems); value.addProperty("round_rejected_items", rejectedItems);
        value.addProperty("round_unknown_kind_rejected", unknownKindRejected);
        value.addProperty("visible_items", visible); value.addProperty("more", more);
        JsonObject histogram = new JsonObject();
        errors.forEach((type, count) -> histogram.addProperty(type.name(), count));
        value.add("round_errors", histogram);
        return value;
    }
}

package io.catalogsentinel.domain.source;

/**
 * The role a catalog source plays in selection analysis.
 *
 * <ul>
 *   <li>{@link #MARKETPLACE}: a target catalog whose selection is being monitored.</li>
 *   <li>{@link #REFERENCE}: a supplier or competitor catalog used as the benchmark for selection gaps.</li>
 * </ul>
 */
public enum SourceRole {
    MARKETPLACE,
    REFERENCE
}

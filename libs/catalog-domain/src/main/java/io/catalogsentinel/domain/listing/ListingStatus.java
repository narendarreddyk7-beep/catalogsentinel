package io.catalogsentinel.domain.listing;

/** Lifecycle status of a listing. A withdrawn listing is a tombstone that still carries its version. */
public enum ListingStatus {
    ACTIVE,
    WITHDRAWN
}

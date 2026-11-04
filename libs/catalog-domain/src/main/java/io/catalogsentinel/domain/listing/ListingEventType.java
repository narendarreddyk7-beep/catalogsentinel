package io.catalogsentinel.domain.listing;

/** Kinds of listing events a source can emit. */
public enum ListingEventType {
    /** Create a listing or replace its content with a newer source version. */
    LISTING_UPSERTED,
    /** Withdraw a listing; recorded as a versioned tombstone. */
    LISTING_WITHDRAWN
}

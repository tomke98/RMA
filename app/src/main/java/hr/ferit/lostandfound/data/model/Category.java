package hr.ferit.lostandfound.data.model;

/**
 * Fixed item taxonomy (AD-16). Members are exactly and only the 10 PRD terms.
 * Stored and queried as {@link #name()}; Croatian display labels come from
 * strings.xml, never from {@code name()}.
 */
public enum Category {
    KEYS,
    DOCUMENTS_ID,
    WALLET,
    PHONE,
    ELECTRONICS,
    BAG,
    CLOTHING,
    BOOKS_NOTES,
    UMBRELLA,
    OTHER;

    private static final String TAG = "Category";
}

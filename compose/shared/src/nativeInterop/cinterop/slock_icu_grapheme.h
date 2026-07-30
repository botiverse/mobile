#ifndef SLOCK_ICU_GRAPHEME_H
#define SLOCK_ICU_GRAPHEME_H

#include <stdint.h>
#include <unicode/ubrk.h>

static inline int32_t slock_icu_first_grapheme_boundary_utf16(
    const uint16_t *text,
    int32_t length
) {
    if (text == NULL || length <= 0) {
        return -1;
    }

    UErrorCode status = U_ZERO_ERROR;
    UBreakIterator *iterator = ubrk_open(
        UBRK_CHARACTER,
        "root",
        (const UChar *)text,
        length,
        &status
    );
    if (U_FAILURE(status) || iterator == NULL) {
        if (iterator != NULL) {
            ubrk_close(iterator);
        }
        return -1;
    }

    int32_t first = ubrk_first(iterator);
    int32_t boundary = ubrk_next(iterator);
    ubrk_close(iterator);

    if (first != 0 || boundary <= 0 || boundary > length) {
        return -1;
    }
    return boundary;
}

#endif

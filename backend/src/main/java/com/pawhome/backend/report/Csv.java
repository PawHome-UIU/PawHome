package com.pawhome.backend.report;

import java.util.List;

/**
 * Building a CSV by hand, safely.
 *
 * NO LIBRARY, BECAUSE THE WHOLE FORMAT IS TWO RULES -- double the quotes,
 * wrap the field -- and a dependency for that is a dependency to keep
 * updated. What a library WOULD have given us for free is the pair of traps
 * below, so both are handled here explicitly rather than assumed away.
 *
 * TRAP 1: A FIELD THAT CONTAINS THE SEPARATOR.
 *
 * A shelter called "Paws, Claws & Co" splits into two columns the moment it is
 * written raw, and every column after it on that row shifts left. The rule is
 * RFC 4180's: a field containing a comma, a quote, a carriage return or a
 * newline is wrapped in quotes, and any quote inside it is doubled. Note that
 * this is decided PER FIELD -- quoting everything would also be correct, but
 * it makes the file unreadable in a text editor and hides which fields
 * actually needed it.
 *
 * TRAP 2: CSV INJECTION, WHICH IS THE ONE PEOPLE MISS.
 *
 * Excel and Sheets treat a cell beginning with = + - @ as a FORMULA, not as
 * text. A pet named `=cmd|'/c calc'!A1` -- a name an adopter can type into a
 * form on this very site -- becomes an executable cell in a spreadsheet an
 * admin opens on their own machine. Quoting does not help: the quotes are
 * stripped by the parser before the formula is evaluated.
 *
 * The fix is to prefix such a field with a single quote, which spreadsheets
 * read as "treat the rest as literal text" and which the format itself does
 * not care about. It costs a visible apostrophe in the rare cell that needs
 * one, and that is a much better outcome than the alternative.
 *
 * A NOTE ON THE BOM. Excel on Windows reads a CSV as the system codepage
 * unless the file opens with a UTF-8 byte-order mark, so a shelter with an
 * accent in its name arrives as mojibake. The BOM is written by the caller
 * (see ReportService.render) rather than here, because it belongs to the FILE
 * and this class builds rows.
 */
final class Csv {

    private Csv() {}

    static final String NEWLINE = "\r\n";   // RFC 4180 says CRLF, and Excel agrees.

    /** Characters that make a spreadsheet evaluate a cell instead of showing it. */
    private static final String FORMULA_STARTERS = "=+-@";

    /** One row, already terminated. */
    static String row(Object... fields) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < fields.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(field(fields[i]));
        }
        return sb.append(NEWLINE).toString();
    }

    static String row(List<?> fields) {
        return row(fields.toArray());
    }

    /**
     * One field, escaped.
     *
     * Null becomes empty rather than the four letters "null", which is what
     * String.valueOf would give and what somebody would then have to explain
     * to whoever opened the spreadsheet.
     */
    static String field(Object value) {
        if (value == null) return "";
        String s = String.valueOf(value);
        if (s.isEmpty()) return "";

        // Neutralise the formula BEFORE deciding on quoting: adding the
        // apostrophe can itself introduce nothing, but doing it after would
        // mean measuring the wrong string.
        if (FORMULA_STARTERS.indexOf(s.charAt(0)) >= 0) {
            s = "'" + s;
        }

        boolean needsQuotes = s.indexOf(',') >= 0 || s.indexOf('"') >= 0
                || s.indexOf('\n') >= 0 || s.indexOf('\r') >= 0;
        if (!needsQuotes) return s;

        return '"' + s.replace("\"", "\"\"") + '"';
    }
}

package com.pawhome.backend.daycare;

import java.time.LocalTime;

/**
 * The three daily bands a daycare subscription can hold. Maps the
 * `daycare_subscriptions.slot` ENUM in V13.
 *
 * THE TIMES LIVE HERE AND NOWHERE ELSE. They are not columns, because they are
 * not data: every shelter runs the same three bands, so storing 09:00 against
 * each subscription would be the same six numbers repeated on every row, with
 * nothing stopping two rows disagreeing about when MORNING is.
 *
 * IF A SHELTER EVER WANTS ITS OWN HOURS this enum becomes a table
 * (shelter_slots) and the column becomes a foreign key. V13 says so above the
 * ENUM. That is a bigger change than it looks -- the capacity check joins on
 * it -- which is exactly why it is worth writing down that nobody has asked
 * for it.
 */
public enum DaycareSlot {

    MORNING(LocalTime.of(9, 0), LocalTime.of(12, 0)),
    MIDDAY(LocalTime.of(12, 0), LocalTime.of(15, 0)),
    AFTERNOON(LocalTime.of(15, 0), LocalTime.of(18, 0));

    private final LocalTime from;
    private final LocalTime to;

    DaycareSlot(LocalTime from, LocalTime to) {
        this.from = from;
        this.to = to;
    }

    public LocalTime from() {
        return from;
    }

    public LocalTime to() {
        return to;
    }

    /** "09:00 - 12:00". What the customer sees; the enum name is not it. */
    public String label() {
        return from + " - " + to;
    }
}

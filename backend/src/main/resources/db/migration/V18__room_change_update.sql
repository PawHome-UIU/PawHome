-- =============================================================================
-- V18__room_change_update.sql
--
-- A ROOM CHANGE IS SOMETHING THAT HAPPENED DURING A STAY, SO IT BELONGS IN THE
-- TABLE OF THINGS THAT HAPPENED DURING A STAY.
--
-- V17 gave staff BoardingService.moveSuite -- a pet can be moved between
-- rooms mid-stay, which every real shelter needs and which the schema had no
-- answer for beyond completing the stay and starting another. The move itself
-- needs no new column: boarding_requests.suite_id is already the answer to
-- "which room", and moving is an UPDATE of it.
--
-- WHAT IT NEEDS IS A RECORD. The owner rings up and asks where their dog is,
-- and a shelter whose answer changed on Wednesday should be able to say when
-- and why. A notification alone will not do: notifications are read and
-- dismissed, and boarding_updates is the stay's timeline -- the thing
-- BoardingDetail shows the owner and keeps showing them.
--
-- SO update_type GAINS A VALUE.
--
-- WHY AN ENUM AND NOT A LOOKUP TABLE, since this is the second time it has had
-- to change: because the set is closed and small, the values are read by the
-- code (UpdateType is a Java enum, and NotificationService switches on its
-- sibling), and a lookup table would turn every read of a stay's timeline into
-- a join for a label nobody localises. The cost of the closed set is exactly
-- this migration, once, and it is cheaper than that join forever.
--
-- APPENDED AT THE END, WHICH IS THE WHOLE TRICK. MySQL stores an ENUM as the
-- INDEX of the value, not the text -- 'FEEDING' is 2 because it is second in
-- the list. Inserting a value in the middle would silently renumber every
-- value after it and every existing row would change meaning. Adding at the
-- end leaves 1..6 exactly where they were, so this ALTER rewrites no data and
-- reinterprets none.
--
-- SAME REASON V13 APPENDED 'SLIP_ISSUED' to notifications.type rather than
-- filing it alphabetically.
-- =============================================================================

ALTER TABLE boarding_updates
    MODIFY COLUMN update_type ENUM('ARRIVAL_CHECK_IN','FEEDING','PLAYTIME',
                                   'MEDICATION','HEALTH','DEPARTURE',
                                   'ROOM_CHANGE') NOT NULL;


-- =============================================================================
-- ROOM_CHANGE IS A THIRD AUTOMATIC TYPE, and the service enforces that.
--
-- ARRIVAL_CHECK_IN and DEPARTURE have never been postable by hand:
-- BoardingService.postUpdate refuses them, because each is the RECORD OF an
-- event rather than a comment about one -- an arrival note on a stay that has
-- not started would be a lie somebody typed. ROOM_CHANGE joins them.
-- moveSuite() writes it, and only moveSuite(), so a timeline entry saying the
-- pet moved always means the pet moved.
--
-- The refusal is a service rule and not a constraint, for the reason it always
-- is here: a CHECK sees one row of boarding_updates and cannot tell whether
-- the suite_id on ANOTHER table changed to put it there.
-- =============================================================================

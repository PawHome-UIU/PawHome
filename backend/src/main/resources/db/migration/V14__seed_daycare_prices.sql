-- =============================================================================
-- V14__seed_daycare_prices.sql
--
-- TURN DAYCARE ON FOR THE SEEDED SHELTERS.
--
-- WHY THIS IS NEEDED AT ALL. V13 had to give every existing shelter a default
-- for the two new columns, and the only honest default for a price is 0.00 --
-- a shelter that has never been asked cannot be assumed to run daycare, and
-- guessing a number would put a made-up price in front of customers.
--
-- But DaycareService.offers() filters on daycare_monthly_price > 0, precisely
-- so that shelters which have not opted in are not listed with a free
-- subscription button. Both halves are right, and together they mean the
-- feature ships INVISIBLE: five seeded shelters, every one of them priced at
-- zero, so the "Where you can subscribe" page is empty until somebody opens
-- the admin form and types a number.
--
-- That is fine for a real deployment and wrong for this project, where V2, V3
-- and V8 already seed an admin, five shelters and their boarding suites so a
-- fresh clone demonstrates itself. Daycare should arrive the same way.
--
-- THE PRICE IS DERIVED, NOT INVENTED: ten times the shelter's own nightly
-- boarding rate. A month of weekday mornings is roughly twenty sessions, so
-- ten nights' worth prices a half-day slot at about half a night -- which is
-- the right shape, and more importantly it keeps each shelter's daycare price
-- in proportion to how that shelter already prices itself. A flat number
-- across all five would have made the cheapest shelter the dearest.
--
-- SAFE TO RE-RUN, and safe on a database where somebody has already set real
-- prices: the WHERE clause touches only rows still at zero, so an admin's own
-- number is never overwritten.
-- =============================================================================

UPDATE shelters
   SET daycare_monthly_price = ROUND(boarding_rate_daily * 10, 2)
 WHERE daycare_monthly_price = 0.00
   AND boarding_rate_daily  > 0;


-- -----------------------------------------------------------------------------
-- CAPACITY: mostly the column default, with one deliberate exception.
-- -----------------------------------------------------------------------------
-- 20 per band is V13's default and a sensible number for a shelter of 100-180
-- kennels. Urban Kitty Rescue is cats-only with a foster network rather than a
-- big building, and South Valley describes itself as "a small operation" with
-- a capacity of 50 -- so a band of twenty at either would be a claim the
-- shelter could not honour.
--
-- It also makes the demo honest: three shelters at 20 and two smaller ones
-- shows the occupancy bar doing something other than looking identical
-- everywhere, and gives a "nearly full" band to point at without having to
-- invent thirty subscriptions first.

UPDATE shelters SET daycare_slot_capacity =  8 WHERE id = 3;   -- Urban Kitty Rescue
UPDATE shelters SET daycare_slot_capacity = 10 WHERE id = 4;   -- South Valley Rescue

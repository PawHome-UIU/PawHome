# PawHome — presentation speech

Fourteen slides, about **eleven minutes** at an unhurried pace. Timings are a
guide, not a script to race. Everything in square brackets is a stage
direction, not something to say.

A note on delivery before the words: the examiner has already sat through
several decks that list features. Almost nobody arrives with a number they
measured themselves. Your whole advantage is that every figure you say out
loud came out of a running database — so say the numbers slowly, and let them
land.

---

## The thirty-second version

*Use this if you are cut short, asked "what is it?" in a corridor, or need an
opening line before the formal start.*

> PawHome is a shelter management system for pet adoption and boarding. What
> makes it a database project rather than a web project is the rule set: an
> animal cannot be booked into two overlapping stays, a cat room cannot take a
> dog, approving one adoption must close every rival application in a single
> statement, and a bill, once issued, must never change. We pushed every one of
> those rules into the database itself — thirty-nine CHECK constraints,
> sixty-nine foreign keys, each with a deliberate delete policy — and where
> MySQL genuinely cannot express a rule, we used transactions holding locks.
> Finding the exact line between those two was the project.

---

## Slide 1 — Title  *(40 seconds)*

> Good morning. We are presenting **PawHome**, a pet adoption and boarding
> management system.
>
> The one-line description is that it manages the whole life of an animal in a
> shelter's care — not just the moment of adoption. A pet arrives, is listed,
> is applied for, is adopted. And then, as somebody's pet, it comes back: for
> boarding when the family travels, or for a daycare subscription. Each of
> those has rooms, capacity limits, a daily care record and a bill.
>
> I want to be upfront about the argument we are making today, because it
> shapes everything that follows. We set out to push as much of this system's
> correctness as possible into the database itself. The interesting part of the
> project turned out to be discovering exactly where that stops being possible.

[Do not introduce the team member by member — the names are on the slide.]

---

## Slide 2 — One animal, its whole life  *(60 seconds)*

> Three roles use the system. An **adopter** browses, takes a matching quiz,
> applies for a pet, and afterwards manages the pets they own. A **staff
> member** runs one shelter — its rooms, its intake, its applications, the
> animals currently boarding, the feeding and medication log. An
> **administrator** runs the network: shelters, accounts, the quiz bank,
> analytics, billing.
>
> The numbers on this slide: thirty-two base tables, two hundred and ninety
> columns, four views. Sixty-nine foreign keys and thirty-nine CHECK
> constraints. A hundred and fifty-one REST endpoints across forty-seven
> screens.
>
> One thing I would ask you to hold onto. **Every number in this deck was read
> out of the running database or counted from the source.** None of them is an
> estimate. If you ask me where a figure came from, I can tell you the query.

[Pause after that last sentence. It is the claim the rest of the talk rests on.]

---

## Slide 3 — Why we built it  *(75 seconds)*

> Three things pushed us towards this problem.
>
> **First** — small shelters here keep records in a notebook or a spreadsheet.
> That is perfectly fine for a list of names. It fails the moment an animal has
> a history. Which vaccinations are due. Who fed the dog in room three this
> morning. Whether the family applying for this cat already has an application
> open on another one. How many nights a boarding stay actually ran, and
> therefore what it costs. Every one of those questions is a join, and a
> notebook cannot do joins.
>
> **Second** — we wanted a project where the difficulty lived in the data, not
> in the interface. Shelter management is full of rules a form cannot enforce.
>
> **Third**, and this is the one that made the project worth doing: the same
> animal is a different thing at different times. It arrives as shelter stock.
> It becomes an adopted pet, owned by a person. It may come back as a paying
> guest. If you model that as two tables, you have duplicated the animal. If
> you model it as one table — which is what we did — then you have to express,
> *in constraints*, that a pet with an owner cannot be listed as available.
> That constraint is called `ck_pets_owner_when_placed`, and the database
> refuses the row rather than trusting anyone's code to check.

---

## Slide 4 — Key features  *(60 seconds)*

[Do not read the lists. Three sentences, one per column, then move.]

> Rather than read these out, let me point at one item in each column and tell
> you what is behind it.
>
> In the adopter column: **the matching quiz**. Every answer option carries
> weighted traits, so the quiz produces a trait profile, and pets are
> recommended by scoring their own traits against it — temperament, not breed
> and distance.
>
> In the staff column: **"approve closes all rivals."** Approving one adoption
> application has to reject every other pending application for that same pet.
> That is one UPDATE statement, however many rivals there are.
>
> In the admin column: **analytics**. Every tile on that dashboard — revenue,
> nights boarded, active subscriptions — comes out of a single view, so no two
> numbers on the screen can disagree.
>
> And three jobs run with nobody watching: monthly daycare billing,
> vaccination reminders, care-task announcements.

---

## Slide 5 — Database design approach  *(60 seconds)*

> Our guiding decision was this: **put every rule the database is capable of
> enforcing into the database, and accept the cost.**
>
> The reasoning is simple. A rule written in a Java service can be broken by a
> second service, by a migration script, or by somebody running a manual UPDATE
> at eleven at night. A rule written as a constraint cannot be broken by any of
> them.
>
> The schema is in third normal form with two deliberate departures, and I want
> to name one because it looks like a mistake. **We copy prices on purpose.**
> The daily rate is copied from the shelter onto the booking, and copied again
> onto the bill. Strict normalisation says store it once and join. That would
> be wrong — a shelter raising its rate would retroactively reprice every stay
> already booked, and reissuing an old bill would produce a different number
> from the one the customer was handed. Those three columns answer three
> different questions: what the shelter charges now, what this stay was booked
> at, and what this customer was actually billed.
>
> And Flyway owns the schema. Nineteen migrations, in order, every boot.
> Hibernate runs on `ddl-auto: validate` — it checks the Java classes against
> the live schema at start-up and **refuses to start** if they disagree. The
> code can never silently change the database.

---

## Slide 6 — The ERD  *(45 seconds)*

> This is the entity relationship diagram. Thirty-two entities, sixty-nine
> foreign keys, grouped by subject area.
>
> It was **generated directly from `information_schema`** — from the database's
> own catalogue — so nothing in it was transcribed by hand and it cannot be out
> of date with the schema the application actually runs on.
>
> The thing I would draw your attention to is the **colour of the lines**. The
> colour is not the relationship; it is the `ON DELETE` rule. Green is
> RESTRICT, red is CASCADE, purple is SET NULL. We coloured them that way
> because that rule, rather than the existence of the relationship, is where
> the design decisions actually live — which is the next slide.

---

## Slide 7 — Every foreign key answers one question  *(90 seconds)*

[The most important technical slide. Slow down.]

> Every one of our sixty-nine foreign keys had to answer the same question: if
> the parent row is deleted, what happens to this one?
>
> The clearest illustration is the `pets` table, because it uses **both**
> answers.
>
> Photos, traits, favourites, vaccinations, care logs and care tasks all
> **CASCADE**. A photograph of a dog means nothing once the dog's row is gone.
>
> But applications, adoptions, boarding requests and daycare subscriptions all
> **RESTRICT** — meaning the pet cannot be deleted at all while they exist. An
> adoption is an agreement between two parties. It cannot vanish because
> somebody tidied up a pet record.
>
> Same parent table. Opposite answers. Decided entirely by what the child row
> *means*.
>
> And cascades chain. Deleting a pet removes its vaccinations, and each
> vaccination takes the reminder rows attached to it — two levels. We tested
> that by running the delete and counting what was left.
>
> One last thing on this slide, because it surprises people: **none of this
> cascading is in Java.** There is no `CascadeType`, no `orphanRemoval`, no
> `@JoinColumn` anywhere in our codebase — we map foreign keys as plain `Long`
> columns by convention. JPA has no object graph to walk, so it *could not*
> cascade even if we asked it to. All twenty-one cascades are `ON DELETE`
> clauses in the migrations, and the database performs every one of them.

---

## Slide 8 — Views  *(60 seconds)*

> Four views, and all four are read by application code.
>
> We use a view when a **definition** has to live in exactly one place. And the
> trigger for that was not theory — it was real drift. The definition of what
> counts as a "vaccinated" pet had been hand-written into three separate
> queries, and they had already started to disagree with each other.
>
> `v_pet_listing` now owns that definition. `v_adopter_traits` owns the quiz
> scoring rule, read from three call sites. `v_daycare_day` joins eight tables
> so that the live attendance board and the history screen cannot disagree
> about what a visit is.
>
> And `v_shelter_revenue` exists for a reason worth stating: the `units` column
> on a billing slip means **nights** on a boarding slip and **months** on a
> subscription. The view keeps them in separate columns, so no caller can
> accidentally sum them and produce a number that is nights added to months.
>
> I should be honest about what a view costs. We measured reading the catalogue
> through `v_pet_listing` against writing the same query by hand, and the view
> is slower — MySQL materialises it before applying the LIMIT. **A view is a
> correctness tool, not a performance tool.** We chose one definition of
> "vaccinated" over a faster query, knowing what we were buying.

---

## Slide 9 — Transactions  *(90 seconds)*

> A transaction does exactly two jobs in this system.
>
> **The first is all-or-none.** Approving an application closes every rival,
> marks the pet adopted, notifies the applicant and writes an audit row — and
> either all of that happens or none of it does. There is a detail there I am
> quite pleased with: the audit row has **no** `@Transactional` annotation of
> its own. It deliberately joins the caller's transaction. An audit entry for
> an approval that rolled back would be a record of something that never
> happened.
>
> **The second job is the interesting one.** A CHECK constraint can only see
> one row. So "this room is not full" and "this animal is not already booked"
> cannot be CHECK constraints — they are statements about *other* rows, and
> MySQL has no way to express them declaratively at all.
>
> So those rules became transactions holding **pessimistic locks** — six of
> them. `lockOverlappingForPet` is the one that makes double-booking
> impossible. It is a SELECT with `FOR UPDATE`, which means the second staff
> member to click cannot even *read* the overlap set until the first one has
> committed — so they see the booking that was just made, and the system
> refuses.
>
> Without that lock, under REPEATABLE READ isolation, the second transaction
> reads a snapshot taken *before* the first one wrote. It sees no clash. It
> books the same animal twice. And the snapshot it read was perfectly
> consistent — it was just out of date, which is still wrong when you are about
> to write based on it.

---

## Slide 10 — Indexes  *(75 seconds)*

> A hundred and twenty-four indexes. Each one exists for a named query.
>
> **Column order is part of the design.** A composite index is only useful left
> to right, so `idx_pets_browse` on status, species, size is a statement about
> which filter the catalogue page offers first.
>
> `idx_boarding_overlap` is there for a reason worth hearing. It supports the
> double-booking check — and without it, that check becomes a full table scan
> *while holding row locks*. An index turns a correctness feature into
> something that also performs; without it you would have turned a correctness
> feature into a throughput problem.
>
> But the two at the bottom are my favourites, because they are **not
> optimisations at all — they are constraints.**
>
> We need to enforce "at most one cover photo per pet." In PostgreSQL you would
> write a partial index. MySQL does not have those. So we created a generated
> column, `cover_flag`, defined as `IF(is_cover, 1, NULL)` — and put a unique
> index on pet and cover flag. Because **NULLs do not collide in a unique
> index**, a pet can have twenty non-cover photos and exactly one cover.
>
> The same trick enforces one open application per person per pet:
> `pending_flag` is NULL unless the row is PENDING. So somebody may re-apply
> after being rejected, but can never have two applications open at once.

---

## Slide 11 — Benchmarking  *(75 seconds)*

> For benchmarking we compared against the two real products in this space,
> because they are genuinely different kinds of thing.
>
> **Petfinder** is a listing aggregator. An adopter searches, saves favourites,
> and then applies — but the application goes *to the shelter*, not through
> Petfinder. Their own help pages say so explicitly. So Petfinder holds a
> listing, not an operational record.
>
> **Shelterluv** is a full management platform, used by more than two and a
> half thousand shelters and rescues. Animal records, adoptions, medical
> templates written by shelter vets, foster management, donations, a microchip
> registry.
>
> I want to be straight about scope: **Shelterluv is far larger than a semester
> project, and we are not claiming to have beaten it.** You can see on this
> table where we are weaker — foster placements, donations, the microchip
> registry. We have none of those.
>
> But look at the three purple rows. **Boarding with rooms and capacity.
> Daycare subscriptions and attendance. Matching on temperament.** Those appear
> in *neither* product. Shelter platforms treat boarding as a note on an animal
> record, if they model it at all.
>
> Neither of those is novel research. But they are precisely where our schema
> had to do real work — capacity under concurrency, and weighted scoring across
> a junction table.

---

## Slide 12 — Limitations  *(60 seconds)*

> These are the limitations we know about — and I want to say how we found
> them, because that matters more than the list.
>
> We audited the system against itself. A mechanical cross-reference of all
> hundred and fifty-one endpoints against every call the front end makes. The
> good news from that audit: **no broken call, no role mismatch, and no private
> endpoint left ungated.**
>
> But it also found these. There is no admin screen for daycare subscriptions,
> though the endpoint exists and returns the data. Quiz questions cannot be
> reordered after creation. Fourteen repository methods compile and are called
> by nothing at all.
>
> And two design limits we accepted knowingly. The cross-row rules are only as
> strong as the discipline of always going through the service — a direct
> UPDATE against the database could still create an overlapping stay. And room
> allocation uses Hall's condition, which is a correct *feasibility* test but
> will not suggest the best arrangement of animals among rooms.
>
> We went looking for these. A project that claims no limitations has not been
> checked.

---

## Slide 13 — Future work  *(40 seconds)*

[Do not read all twelve items. Name three.]

> Three things I would do next, in order.
>
> **Foster placements** — it is the nearest real gap, and it is a natural
> extension of the boarding model: a pet living in a household that is not its
> owner's.
>
> **Automated tests around the capacity and locking rules specifically.** Those
> are exactly the places where a regression would be easy to introduce and very
> hard to notice.
>
> And **partitioning the attendance and stay-update tables by date** — both
> grow without bound and both are almost always read for a recent window.

---

## Slide 14 — Conclusion  *(50 seconds)*

> So — to the question we started with. How much of a system's correctness can
> live in the database?
>
> Our answer is: **most of it, but not all, and the boundary is sharp.**
>
> Thirty-nine CHECK constraints and sixty-nine foreign keys, each with a
> deliberate delete policy, carry every rule that fits inside a single row or a
> single relationship. That is a lot — more than we expected when we started.
>
> But the rules that span rows — this room is not full, this animal is not
> already booked — cannot be stated declaratively in MySQL at all. Those had to
> become transactions holding pessimistic locks, and they are only as strong as
> the code path that takes them.
>
> Learning to tell those two categories apart was the most useful thing this
> project taught us.
>
> Thank you. We are happy to take questions.

---

## Likely questions, and short answers

**"Why MySQL and not PostgreSQL?"**
> It was the recommended database for the course. It also made two things
> harder in instructive ways — no partial indexes, so we used generated
> columns; and ENUM stored by index, so a value can only ever be appended.

**"Why not use JPA cascades instead of ON DELETE?"**
> Because then the rule only holds for code that goes through JPA. The database
> enforces it for everything, including a manual DELETE. We map foreign keys as
> plain `Long` columns, so JPA has no object graph and could not cascade anyway.

**"How do you know the lock actually works?"**
> We tested it with two concurrent sessions. With `FOR UPDATE` the second one
> blocked until the first committed. With the lock removed it did not wait at
> all and would have written an overlapping stay.

**"Isn't copying the price denormalisation?"**
> Yes, deliberately. The three columns answer three different questions — the
> current rate, the booked rate and the billed rate. Joining to a single price
> would let a rate change rewrite history.

**"What happens if you delete a user?"**
> Mostly you cannot. Eighteen foreign keys to `users` are RESTRICT, so anyone
> who has done work in the system is undeletable. The designed path is
> `is_active = FALSE`, which is checked on every request. Their favourites and
> notifications cascade; the audit log keeps the row but blanks the name.

**"Which part was hardest?"**
> Room allocation. Fitting animals into rooms where some rooms only take one
> species is a bipartite matching problem. We apply Hall's condition as a
> feasibility test rather than computing a full matching on every request.

---

## Delivery notes

- **The numbers are the differentiator.** Say them deliberately — "thirty-nine
  CHECK constraints", not "a bunch of constraints".
- **Three places to pause:** after "none of them is an estimate" (slide 2),
  after "Same parent. Opposite answers." (slide 7), and after "most of it, but
  not all, and the boundary is sharp" (slide 14).
- **Never read a bullet list aloud.** Every slide with a list has a sentence
  above telling you which one item to talk about.
- **If you are running long**, cut slide 13 to a single sentence and compress
  slide 4. Do not cut slides 7, 9 or 10 — they are the ones that make this a
  database project.
- **If something is asked that you do not know**, say so and say where you
  would look. "I would check the migration" is a better answer than a guess,
  and this schema is documented well enough that it is a true answer.

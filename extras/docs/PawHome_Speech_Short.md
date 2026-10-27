# PawHome — short speech (5 minutes, simple words)

**About 700 words** — five to five and a half minutes at a normal speaking
pace. If you tend to speed up when nervous you will land nearer five; if you
pause a lot, drop slide 13 and you are safely inside.

Words in brackets are for you, not to be said out loud.

---

**[Slide 1 — Title]**

Good morning. Our project is **PawHome**. It is a system for animal shelters.

Most shelter apps stop at adoption. Ours keeps going — when the pet comes back
for boarding, or for daycare.

Our main idea is this: **we let the database hold the rules, not the code.**

---

**[Slide 2 — What it is]**

Three kinds of people use it. An **adopter** finds a pet and applies for it. A
**staff member** runs one shelter. An **admin** runs all the shelters together.

It has 32 tables and 47 screens. Every number we show today came out of the real
database. We did not guess any of them.

---

**[Slide 3 — Why we made it]**

Small shelters here still use notebooks. That is fine for a list of names. It
fails when an animal has a history.

Which injection is due? Who fed the dog in room three? How many nights did the
pet stay? Every one of those needs a database.

---

**[Slide 4 — Features]**

One thing from each role.

The adopter takes a **quiz**, so we can suggest pets that match the person — not
just the breed.

Staff **approve an application**, and the system automatically rejects all the
others for that same pet.

The admin sees **charts** — money earned, nights booked, pets adopted.

---

**[Slide 5 — Design approach]**

Our rule was simple: **if the database can check something, let the database
check it.**

A rule written in code can be broken — someone types a command by hand at
night. A rule written into the database cannot be broken by anyone.

So we wrote 39 CHECK rules and 69 foreign keys.

---

**[Slide 6 — ERD]**

This is our ERD — 32 tables, 69 links.

We did not draw it. We asked the database to describe itself. So it is always
correct.

---

**[Slide 7 — Delete rules]**

This is my favourite part.

For every link we asked one question: **if the parent is deleted, what happens
to the child?**

Look at the pets table. It gives two different answers.

Photos and health records are **deleted with the pet**. A photo of a dog means
nothing once the dog is gone.

But adoptions and bookings **stop you deleting the pet at all**. An adoption is
an agreement between two people. It cannot disappear because someone tidied up a
record.

Same table. Two different answers. It depends on what the row means.

[Pause here.]

---

**[Slide 8 — Views]**

We made four views. A view lets us write a rule **once**.

Before, the rule for "this pet is vaccinated" sat in three places — and they had
already started to disagree. Now it lives in one.

---

**[Slide 9 — Transactions]**

A CHECK rule can only look at **one row**. So it cannot answer "is this room
full?" — that needs other rows.

For those we use **locks**.

Imagine two staff book the same dog at the same second. Without a lock, both see
an empty calendar and both save. The dog is booked twice.

With our lock, the second person must wait. Then they see the new booking, and
the system says no.

---

**[Slide 10 — Indexes]**

We have 124 indexes. Two of them are not for speed — they are **rules**.

MySQL has no simple way to say "only one cover photo per pet". So we made a
small trick column that stays empty unless the photo is the cover. Empty values
never clash. So a pet can have many photos, but only one cover.

---

**[Slide 11 — Comparison]**

We compared ourselves with two real products.

**Petfinder** only shows listings — you apply to the shelter, not through the
app. **Shelterluv** is a full system used by over 2,500 shelters, much bigger
than ours. We are not claiming we beat it.

But look at the purple rows. **Boarding with rooms. Daycare. Personality
matching.** Neither product has them at all.

---

**[Slide 12 — Limits]**

We checked our own system, found problems, and are showing them. The admin has
no daycare screen yet. Quiz questions cannot be reordered. And some rules are
only safe if you go through the app.

---

**[Slide 13 — Future work]**

Next: **foster homes**, **tests** for the booking rules, and **online payment**.

---

**[Slide 14 — End]**

So — how much of a system's safety can live inside the database?

Our answer: **most of it, but not all.** Rules about one row fit easily. Rules
about many rows do not fit, and need locks instead.

Learning that difference was the best thing this project taught us.

Thank you. We are happy to take questions.

---

## If they ask you something

**Why MySQL?** — It was the recommended database for the course.

**How do you know the lock works?** — We tested it with two users at once. With
the lock, the second one waited. Without it, the pet was booked twice.

**Why copy the price onto the booking?** — So that if the shelter raises its
price later, old bookings keep the old price.

**Can you delete a user?** — Usually no. If they have done any work in the
system, the database refuses. We switch the account off instead.

**What was hardest?** — Fitting pets into rooms, when some rooms only take cats
and some only take dogs.

---

## Tips

- Pause after **"Same table. Two different answers."** That is the line to let
  sink in.
- Do not read the bullets on the slides. Say the one sentence under each.
- Running out of time? Cut slides 4 and 13. Keep 7, 9 and 10 — those are the
  database parts.

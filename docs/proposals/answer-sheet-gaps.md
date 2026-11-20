# Answer sheet: blank instead of `?`, and tappable gaps

Filed as [issue #1](https://github.com/fishhead2567/logicgrid-android/issues/1).
Kept here as well because the reasoning outlives the issue tracker, and because
the "deliberately not doing" section below is the part most likely to be
re-proposed by someone who has not read it.

---

The answer sheet is now correct — `Board.answer` chains positive links
transitively, so filling any two blocks of a three-category grid reads the
third off, with or without assist. What it is not yet is *useful* when
incomplete.

A blank entry currently renders as `?`, which reads like an error state and
invites "why won't it tell me?" It is really just "not yet", and the header
already carries the count (`12/15 placed`).

Two changes worth making together, whenever the answer sheet next gets
touched:

**1. Render a gap as blank, not `?`.** Five minutes. A `?` editorialises; a
blank does not.

**2. Make the gaps tappable.** Tapping an undetermined entry scrolls the chart
to that row and selects it, so the sheet stops being a scoreboard and becomes
a way to navigate to unfinished work. Leaks nothing about the answer. Needs
the sheet rebuilt as a real table — it is currently a monospace text block
(`.take(14).padEnd(14)` at 9sp), which wants replacing anyway.

### Deliberately not doing: a third glyph for "deducible"

With assist **off** and all but one cell in a row X'd out, the answer is
logically determined but the board does not infer it (last-cell-standing is a
deduction, so assist-off correctly declines to make it). No circle exists,
nothing chains, and the sheet says `?` — technically right, mildly annoying.

Showing a distinct glyph there is cheap (run the closure both ways and diff;
it is pure), but it tells the player where a deduction is available, which is
a hint. If that is ever wanted it belongs in a hint system, behind the assist
setting — not in the answer sheet.

### Context

Came out of a solving session on `09-build_server`: all of blocks 1 and 3
filled, grid fully determined, and the sheet showed `? ?` until block 2 was
filled in. The correctness half of that was fixed in `a994b42`; this issue is
the presentation half that was left.

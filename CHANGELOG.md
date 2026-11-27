# Changelog

All notable changes to this project are listed here, newest first. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/). The app has no version numbers yet, so each
entry is named after the pull request that brought the change in. Add a new entry with every merged PR that
changes the app (documentation-only PRs may be skipped).

## [Unreleased]

## [PR #7] - 2026-11-27: Late players between Swiss rounds

### Added
- **Add late player** button in the standings review step (between two Swiss rounds). It opens a popup where you
  type a new name and/or tick registered players.
- A late player starts with one loss for every Swiss round already played (using the point system's loss
  value) and is paired from the next round. The late entries are saved with the tournament; older saves still load.
- Late players can only be added in the first stage while it is a Swiss stage, and not once 80% of its rounds are
  played or fewer than 2 rounds remain.

## [PR #6] - 2026-11-13: README

### Added
- `README.md` with an app walkthrough, screenshots (`docs/screenshots`), how pairing and tiebreakers work, and
  the known caveats and to-do list.

## [PR #5] - 2026-11-13: Standings preview and drop players

### Added
- **Standings preview before pairing.** Pressing the next-step button on a Swiss round opens the standings first;
  the button becomes "Pair round N · K players" and a second press pairs the remaining players. The preview also
  appears when a Swiss stage ends and an elimination stage follows.
- **Drop with a bin button.** Each standings row has a red-bordered bin button (restore button for dropped
  players), both behind a confirmation popup.
- Pairing is blocked with a note when fewer than 2 players are active.

### Changed
- Tapping a standings row no longer drops a player.

## [PR #4] - 2026-11-13: Download results as images

### Added
- **Download tournament result** button on a finished tournament. Tournaments with several stages offer a picker
  so you choose which stage results to save; each one is saved as its own PNG in `Pictures/PairingApp`.
- Each image shows the standings at the end of that stage (rank, name, points, W-L-D, deck, MW% / OMW% / OOMW%)
  in the app's dark style. Fields over 50 players are split into pages.
- `Tournament.stageResults()` returns one frozen standings snapshot per played stage.

## [PR #3] - 2026-11-13: Round timer

### Added
- **Minutes per round** (default 45, 0 = no timer) and an optional **additional time on time-out** switch on the
  new-tournament page, saved with the tournament.
- Countdown card on the Pairings tab (Start / Pause / Resume, Reset, Silence); every new round starts with a
  fresh clock.
- At zero the phone vibrates and plays the default alarm ringtone. With additional time on, the clock restarts
  with the extra minutes and rings again at zero. It is scheduled with the system alarm clock so it normally fires
  while the app is in the background.

### Fixed
- Back, previous/next round and remove buttons (and the stage hint) showed broken characters on some phones; they now use vector icons.

## [PR #2] - 2026-11-13: Scroll to top

### Changed
- The page scrolls back to the top after pressing the button to start the next round.

## [PR #1] - 2026-11-13: Code-review fixes

### Fixed
- Dropped players who are reinstated are paired again in later rounds of the same Swiss stage.
- A damaged saved tournament no longer breaks loading: bad tournament records are skipped and the raw data is backed up.
- A double elimination stage with a single entrant now finishes.
- Screen state survives rotation.

### Changed
- Swiss pairing search is time-boxed (2 s in total, 300 ms per bye candidate).
- Saves are batched (500 ms) and flushed when the app pauses and after a new round starts.
- Machine-specific `.idea` files are no longer tracked.

## [Initial commit] - 2026-11-13

### Added
- TCG-first tournament pairing app for Android: Swiss, single elimination, double elimination, or a mix of stages.
- Configurable point system (win, draw, loss, double loss, bye).
- Registered players list and inline new-player input on the new-tournament page.
- Standings with points, W-L-D, match win %, opponents' match win % and opponents' opponents' match win %.
- Deck names entered at the end of the tournament; share results as text.
- Dark violet and gold UI.

[Unreleased]: https://github.com/themarvinsod/simple-pairing-app/compare/b0ed2b6...HEAD
[PR #7]: https://github.com/themarvinsod/simple-pairing-app/pull/7
[PR #6]: https://github.com/themarvinsod/simple-pairing-app/pull/6
[PR #5]: https://github.com/themarvinsod/simple-pairing-app/pull/5
[PR #4]: https://github.com/themarvinsod/simple-pairing-app/pull/4
[PR #3]: https://github.com/themarvinsod/simple-pairing-app/pull/3
[PR #2]: https://github.com/themarvinsod/simple-pairing-app/pull/2
[PR #1]: https://github.com/themarvinsod/simple-pairing-app/pull/1
[Initial commit]: https://github.com/themarvinsod/simple-pairing-app/commit/f6d1f2a

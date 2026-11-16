# #1952 evidence: chat composer flash during widget/ADB voice handoff to Actions

Device: S23U (`RFCW11265XH`), debug build `ai.jandal.assistant.debug`.
Method: `repro-handoff-recording.sh <name> [light|dark]` takes `/tmp/jandal-s23u.lock`, sets
`cmd uimode night` for the run (restored on exit), cold-starts the app, sends a chat turn
(`chat_input`), waits, starts `screenrecord`, then sends the widget-voice handoff
(`quick_action_input` + `quick_action_is_voice=true`). Frames: `ffmpeg -vf fps=10`, compared
frame-to-frame. Frame numbers are 1-based at 10 fps.

## Before (pre-fix build, dark mode)
- `before-1`: chat, before the handoff.
- `before-2`, `before-3`: the chat composer ("Message Jandal…", PTT/Loop) is still visible after the
  handoff starts (about 2 frames, ~0.2 s).
- `before-4`: first Actions frame. A faint chat ghost is visible at the top-left.

## After (final build)
The final build uses `EnterTransition.None` for chat→Actions widget handoffs, plus an opaque cover
on both chat destinations while a quick action is pending.
- Dark: `after-dark-1` is the last chat frame before the handoff. `after-dark-2` is the first
  Actions frame, with no chat-composer frame in between.
- Light: `after-light-1` is the last chat frame. `after-light-2` is the opaque cover, which replaces
  chat for one frame. `after-light-3` is the first Actions frame (no content yet). `after-light-4`
  is Actions fully loaded.

## Why the cover is kept
A dark-mode run without the cover still showed a partly-moved chat composer for one frame after
the trigger. The cover removes that frame.

## Back to Actions
`popEnterTransition = { null }` restores NavHost's default for pop. This is verified by reasoning
about navigation-compose 2.8.9 only. It was not recorded on device.

Frames are scaled to 540 px wide. Only chat test bubbles and the top Actions test cards are shown;
the rest of the Actions history on this phone is not included.

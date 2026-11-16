# #1952 evidence: chat composer flash during widget/ADB voice handoff to Actions

Device: S23U (`RFCW11265XH`), debug build `ai.jandal.assistant.debug`.
Method: `repro-handoff-recording.sh <name>` cold-starts the app, sends a chat turn
(`chat_input`), waits, starts `adb shell screenrecord`, then sends the widget-voice
handoff (`quick_action_input` + `quick_action_is_voice=true`). Frames extracted with
`ffmpeg -vf fps=10` and compared frame-to-frame.

- Before: the pre-fix build. Chat composer ("Message Jandal…", PTT/Loop) stays on screen for
  two frames after the handoff starts (`before-2`, `before-3`), then Actions appears
  (`before-4`, with a faint chat ghost at the top-left).
- After: the fixed build. The chat screen is replaced without any chat-composer frame.
  `after-2` is the opaque placeholder covering chat until navigation completes; `after-3` is
  Actions' first frame.

Frames are scaled to 540 px wide. Only chat bubbles with made-up test text and the empty
Actions state are shown; the Actions history list (which contains calendar data on this
phone) is not in these frames.

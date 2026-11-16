#!/usr/bin/env python3
"""Issue #1920: reduce one command's `logcat -v epoch` dump to one line of timing metrics.

usage: parse_run.py <raw.log> [key=value ...]   (extra key=value pairs are echoed first)

All times are device clock, ms. t0 = ActivityTaskManager START of MainActivity (the command).
  t0_to_accepted     chat accepted the command (ChatViewModel TTFT_STAGE stage=accepted)
  accepted_to_gen    accepted -> generate() holds the generation lock (engine generation_lock_wait)
  t0_to_gen          t0 -> generate(); every conversation rebuild of the command happens before it
  rebuilds           CONV_REBUILD lines between t0 and generate(), with their reasons
  create_ms          createConversation durations logged by resetConversationForConfig
  engine_ttft        generate() -> first visible answer token (includes thinking)
  litert_ttft_ms     LiteRT benchmark TTFT of the last prefill (thinking-independent)
  thinking_chars     thinking text length of the generation
  t0_to_first_output t0 -> first model text of any kind (stage first_thinking_text or first_token)
  t0_to_first_token  t0 -> first visible answer token (ChatViewModel stage=first_token)
"""
import re
import sys

LINE = re.compile(r"^\s*(\d+\.\d+)\s+\d+\s+\d+\s+\w\s+([^:]+?)\s*:\s(.*)$")


def main() -> None:
    path, extras = sys.argv[1], sys.argv[2:]
    events = []
    with open(path, errors="replace") as f:
        for raw in f:
            m = LINE.match(raw)
            if m:
                events.append((float(m.group(1)) * 1000.0, m.group(2), m.group(3)))

    def first(pred, after=float("-inf"), before=float("inf")):
        return next(((t, msg) for t, tag, msg in events if after <= t <= before and pred(tag, msg)), None)

    out = dict(kv.split("=", 1) for kv in extras)
    start = first(lambda tag, msg: "START u0" in msg and "com.kernel.ai.MainActivity" in msg)
    token = first(lambda tag, msg: "TTFT_STAGE commandId=" in msg and "stage=first_token" in msg)
    if start is None or token is None:
        out["error"] = "no_start" if start is None else "no_first_token"
        print(" ".join(f"{k}={v}" for k, v in out.items()))
        return
    t0 = start[0]
    command_id = re.search(r"commandId=(\S+)", token[1]).group(1)

    def stage(name):
        hit = first(lambda tag, msg: f"commandId={command_id} stage={name} " in msg)
        return hit[0] if hit else None

    accepted = stage("accepted")
    gen = first(lambda tag, msg: "TTFT_STAGE engine generation_lock_wait_ms" in msg, after=accepted or t0)
    gen_t = gen[0] if gen else None
    window_end = gen_t if gen_t is not None else token[0]
    rebuilds = [msg for t, tag, msg in events if t0 <= t <= window_end and "CONV_REBUILD" in msg
                and "CONV_REBUILD_TRIGGER" not in msg]
    reasons = [re.search(r"reason=(\w+)", r).group(1) for r in rebuilds if "reason=" in r]
    creates = [re.search(r"create_ms=(\d+)", msg).group(1) for t, tag, msg in events
               if t0 <= t <= window_end and "conversation_reset" in msg and "create_ms=" in msg]
    engine_ttft = first(lambda tag, msg: msg.startswith("TTFT (Time to First Token)"), after=gen_t or t0)
    bench = first(lambda tag, msg: msg.startswith("LiteRT benchmark:"), after=gen_t or t0)
    thinking = first(lambda tag, msg: msg.startswith("Thinking tokens:"), after=gen_t or t0)
    complete = first(lambda tag, msg: msg.startswith("Generation complete:"), after=gen_t or t0)

    def ms(a, b):
        return "NA" if a is None or b is None else str(round(b - a))

    out["t0_to_accepted"] = ms(t0, accepted)
    out["accepted_to_gen"] = ms(accepted, gen_t)
    out["t0_to_gen"] = ms(t0, gen_t)
    out["rebuilds"] = str(len(rebuilds))
    out["reasons"] = ",".join(reasons) or "-"
    out["create_ms"] = ",".join(creates) or "-"
    out["prompt_reset_ms"] = ms(stage("prompt_reset_start"), stage("prompt_reset_done"))
    out["engine_ttft"] = re.search(r"(\d+)ms", engine_ttft[1]).group(1) if engine_ttft else "NA"
    if bench:
        b = bench[1]
        out["litert_ttft_ms"] = str(round(float(re.search(r"TTFT=([\d.]+)s", b).group(1)) * 1000))
        out["prefill_tokens"] = re.search(r"prefill=(\d+) tokens", b).group(1)
        out["decode_tokens"] = re.search(r"decode=(\d+) tokens", b).group(1)
    out["thinking_chars"] = re.search(r"(\d+) chars", thinking[1]).group(1) if thinking else "0"
    out["generation_total_ms"] = re.search(r"total=(\d+)ms", complete[1]).group(1) if complete else "NA"
    thinking_start = stage("first_thinking_text")
    out["t0_to_first_output"] = ms(t0, min(t for t in (thinking_start, token[0]) if t is not None))
    out["t0_to_first_token"] = ms(t0, token[0])
    print(" ".join(f"{k}={v}" for k, v in out.items()))


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
"""Turns a Minecraft log with SpeechTimeline lines into a playtest report.

Run the game with -Dmc_talking.speechTimeline=true, then:

    python3 scripts/speech-timeline-report.py path/to/latest.log [--player Dev] [--json out.json]

Reports, for everything a citizen said aloud:
  * a timeline (who, what kind of speech, when, how long),
  * overlaps: two citizens within earshot of each other audible at the same time,
  * missing animation: audio played while the citizen's synced status was not TALKING,
  * repeats: lines by different citizens that say nearly the same thing,
  * how often citizens addressed the player unprompted,
  * silent turns: the provider finished a turn with a transcript but no playable audio.
Exit code 1 when overlaps are found, so scripts can gate on it.
"""
import argparse
import json
import re
import sys
from datetime import datetime

MARKER = "[SpeechTimeline] "
LOG_TIME = re.compile(r"^\[(\d{2}\w{3}\d{4} \d{2}:\d{2}:\d{2}\.\d{3})\]")
STATUS = re.compile(r"Citizen (.+?) has dirty AI status (\w+), sending update")
EVENTS = [
    (re.compile(r"\[CasualGreeting\] Citizen (.+?) greeting player"), "casual greeting"),
    (re.compile(r"\[CitizenContact\] Citizen (.+?) initiating walk-to-player"), "urgent contact walk"),
    (re.compile(r"Playing back pregenerated audio for (.+)$"), "pregenerated clip"),
    (re.compile(r"\[RumorMill\] (.+? shared a rumor with .+)$"), "rumor passed"),
    (re.compile(r"\[RandomConv\] Starting conversation between (.+)$"), "pair conversation"),
    (re.compile(r"Campfire night in .+ with \[(.+)\]"), "campfire night"),
]
EARSHOT = 48.0  # two voices this close are both clearly audible to a listener between them (Simple Voice Chat range)
HEARD = 32.0  # a player this close to a voice hears it clearly (the core's speech floor radius)
LISTENER_SLACK_MS = 3000  # listener positions are logged every two seconds
MERGE_GAP_MS = 800
OVERLAP_MIN_MS = 300
ADDRESSING_KINDS = {"URGENT_CONTACT", "PREGENERATED"}


def log_millis(line):
    match = LOG_TIME.match(line)
    if not match:
        return None
    return int(datetime.strptime(match.group(1), "%d%b%Y %H:%M:%S.%f").timestamp() * 1000)


def parse(path):
    segments, said, marks, statuses, events, turns, listeners = [], [], [], [], [], [], []
    with open(path, encoding="utf-8", errors="replace") as log:
        for line in log:
            if MARKER in line:
                try:
                    entry = json.loads(line.split(MARKER, 1)[1])
                except json.JSONDecodeError:
                    continue
                {"segment": segments, "said": said, "mark": marks, "turn": turns,
                 "listener": listeners}.get(entry.get("type"), []).append(entry)
                continue
            at = log_millis(line)
            if at is None:
                continue
            status = STATUS.search(line)
            if status:
                statuses.append((at, status.group(1), status.group(2)))
                continue
            for pattern, label in EVENTS:
                found = pattern.search(line)
                if found:
                    events.append((at, label, found.group(1).strip()))
                    break
    return segments, said, marks, statuses, events, turns, listeners


def merge(segments):
    """Joins one voice's segments separated by short buffering gaps into one utterance.

    A voice is a speaker and kind: a citizen playing a greeting clip during their own campfire turn
    is two voices at once, and counts as an overlap."""
    merged = []
    for seg in sorted(segments, key=lambda s: (s["id"], s["kind"], s["start"])):
        last = merged[-1] if merged else None
        if (last and last["id"] == seg["id"] and last["kind"] == seg["kind"]
                and seg["start"] - last["end"] <= MERGE_GAP_MS):
            last["end"] = seg["end"]
            last["audioMs"] += seg["audioMs"]
        else:
            merged.append(dict(seg))
    return sorted(merged, key=lambda s: s["start"])


def distance(a, b):
    return ((a["x"] - b["x"]) ** 2 + (a["y"] - b["y"]) ** 2 + (a["z"] - b["z"]) ** 2) ** 0.5


def heard_both(a, b, start, end, listeners):
    """Whether a player was within earshot of both voices while they overlapped. Logs without listener
    positions (older runs) count every overlap."""
    if not listeners:
        return True
    return any(start - LISTENER_SLACK_MS <= p["at"] <= end + LISTENER_SLACK_MS
               and distance(p, a) <= HEARD and distance(p, b) <= HEARD for p in listeners)


def overlaps(utterances, listeners=()):
    found = []
    for i, a in enumerate(utterances):
        for b in utterances[i + 1:]:
            if b["start"] >= a["end"]:
                break
            if a["id"] == b["id"] and a["kind"] == b["kind"]:
                continue
            shared = min(a["end"], b["end"]) - max(a["start"], b["start"])
            if (shared >= OVERLAP_MIN_MS and distance(a, b) <= EARSHOT
                    and heard_both(a, b, max(a["start"], b["start"]), min(a["end"], b["end"]), listeners)):
                found.append((a, b, shared))
    return found


def status_at(statuses, name, at):
    current = "NONE"
    for when, who, status in statuses:
        if when > at:
            break
        if who == name:
            current = status
    return current


def without_animation(utterances, statuses):
    """Utterances during which the speaker was never synced as TALKING."""
    missing = []
    for u in utterances:
        if u["kind"] == "CITIZEN_PAIR":
            continue  # pair conversations show IN_CONVERSATION for both, by design
        seen = {status_at(statuses, u["speaker"], u["start"] + 250)}
        seen |= {s for when, who, s in statuses if who == u["speaker"] and u["start"] <= when <= u["end"]}
        if "TALKING" not in seen:
            missing.append((u, sorted(seen)))
    return missing


WORD = re.compile(r"[a-z']+")
STOP = set("a an the and or but i you we they he she it is are was were to of in on at for with my your our "
           "their me us them this that be have has had do does did not so just all can will would".split())


def words(text):
    return {w for w in WORD.findall(text.lower()) if w not in STOP and len(w) > 2}


def repeats(said, threshold=0.4):
    found = []
    for i, a in enumerate(said):
        for b in said[i + 1:]:
            if a["id"] == b["id"] or abs(a["at"] - b["at"]) > 10 * 60 * 1000:
                continue
            wa, wb = words(a["text"]), words(b["text"])
            if not wa or not wb:
                continue
            score = len(wa & wb) / len(wa | wb)
            if score >= threshold:
                found.append((a, b, score))
    return found


def addressing(utterances, said, player):
    """Unprompted lines aimed at the player: urgent/pregenerated kinds or lines naming them."""
    times = [u["start"] for u in utterances if u["kind"] in ADDRESSING_KINDS]
    times += [s["at"] for s in said if s["kind"] not in ("PLAYER", "CONTROLLED") and player.lower() in s["text"].lower()]
    times = sorted(set(times))
    gaps = [(b - a) / 1000 for a, b in zip(times, times[1:])]
    return times, gaps


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("log")
    parser.add_argument("--player", default="Dev")
    parser.add_argument("--json", help="also write the findings as JSON")
    args = parser.parse_args()

    segments, said, marks, statuses, events, turns, listeners = parse(args.log)
    if not segments and not said:
        print("No SpeechTimeline lines found. Was the game started with -Dmc_talking.speechTimeline=true?")
        return 2
    utterances = merge(segments)
    t0 = min([u["start"] for u in utterances] + [s["at"] for s in said] + [m["at"] for m in marks])

    def rel(ms):
        return f"{(ms - t0) / 1000:7.1f}s"

    print("== Timeline")
    rows = [(u["start"], f"{rel(u['start'])} {(u['end'] - u['start']) / 1000:5.1f}s  {u['kind']:<15} {u['speaker']}")
            for u in utterances]
    rows += [(m["at"], f"{rel(m['at'])}         -- {m['what']}") for m in marks]
    rows += [(at, f"{rel(at)}         .. {label}: {who}") for at, label, who in events if at >= t0]
    for _, row in sorted(rows):
        print(row)

    print("\n== What was said")
    for s in sorted(said, key=lambda s: s["at"]):
        print(f"{rel(s['at'])} {s['kind']:<15} {s['speaker']}: {s['text']}")

    found_overlaps = overlaps(utterances, listeners)
    where = f"heard by a player (within {HEARD:.0f} blocks of both)" if listeners else f"within {EARSHOT:.0f} blocks"
    print(f"\n== Overlaps {where}: {len(found_overlaps)}")
    for a, b, shared in found_overlaps:
        print(f"{rel(max(a['start'], b['start']))} {shared / 1000:4.1f}s  {a['speaker']} ({a['kind']}) + "
              f"{b['speaker']} ({b['kind']}), {distance(a, b):.0f} blocks apart")

    missing = without_animation(utterances, statuses)
    print(f"\n== Audio without TALKING status (no speaking animation): {len(missing)} of {len(utterances)}")
    for u, seen in missing:
        print(f"{rel(u['start'])} {u['kind']:<15} {u['speaker']} (status {', '.join(seen)})")

    found_repeats = repeats(said)
    print(f"\n== Near-repeats between citizens: {len(found_repeats)}")
    for a, b, score in found_repeats:
        print(f"{score:.2f}  {a['speaker']}: {a['text'][:90]}\n      {b['speaker']}: {b['text'][:90]}")

    silent = [t for t in turns if t["transcriptChars"] > 0 and t["acceptedBytes"] == 0]
    print(f"\n== Silent turns (transcript but no playable audio): {len(silent)} of {len(turns)}")
    for t in silent:
        print(f"{rel(t['at'])} {t['kind']:<15} {t['speaker']}: received {t['receivedBytes']} B, dropped "
              f"{t['droppedBytes']} B, gate rejected {t['rejectedBytes']} B, suppressed {t['suppressed']}")

    times, gaps = addressing(utterances, said, args.player)
    print(f"\n== Unprompted lines addressing {args.player}: {len(times)}", end="")
    if gaps:
        ordered = sorted(gaps)
        print(f", gaps min {ordered[0]:.0f}s / median {ordered[len(ordered) // 2]:.0f}s")
    else:
        print()

    total_audio = sum(u["audioMs"] for u in utterances) / 1000
    span = (max(u["end"] for u in utterances) - t0) / 1000 if utterances else 0
    print(f"\n== Summary: {len(utterances)} utterances, {total_audio:.0f}s of audio in {span:.0f}s, "
          f"{len(found_overlaps)} overlaps, {len(missing)} without animation, {len(found_repeats)} near-repeats, "
          f"{len(silent)} silent turns")

    if args.json:
        with open(args.json, "w", encoding="utf-8") as out:
            json.dump({
                "utterances": utterances, "said": said, "marks": marks,
                "overlaps": [{"a": a, "b": b, "sharedMs": s} for a, b, s in found_overlaps],
                "withoutAnimation": [{"utterance": u, "statuses": s} for u, s in missing],
                "repeats": [{"a": a, "b": b, "score": s} for a, b, s in found_repeats],
                "addressingGapsSeconds": gaps,
                "silentTurns": silent,
            }, out, indent=2)
    return 1 if found_overlaps else 0


if __name__ == "__main__":
    sys.exit(main())

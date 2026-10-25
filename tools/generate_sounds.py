#!/usr/bin/env python3
"""Synthesize NeonLine's sound effects offline.

The game has no music, only effects. Everything here is generated with the
standard library - no samples, no network - and written as 16-bit mono WAV into res/raw, which
SoundPool plays back directly.

    python3 tools/generate_sounds.py
"""
import math
import os
import struct
import wave

SAMPLE_RATE = 44100
OUTPUT_DIR = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "app", "src", "main", "res", "raw")


def envelope(length, attack=0.005, release=0.05):
    """A short attack/release envelope over the sample length."""
    attack_samples = max(1, int(attack * SAMPLE_RATE))
    release_samples = max(1, int(release * SAMPLE_RATE))
    for i in range(length):
        if i < attack_samples:
            yield i / attack_samples
        elif i >= length - release_samples:
            yield max(0.0, (length - i) / release_samples)
        else:
            yield 1.0


def tone(frequency, duration, kind="sine", gain=0.5, glide_to=None, envelope_attack=0.005, envelope_release=0.06):
    """One tone, optionally gliding from frequency to glide_to."""
    length = int(duration * SAMPLE_RATE)
    samples = []
    phase = 0.0
    for i, level in zip(range(length), envelope(length, envelope_attack, envelope_release)):
        t = i / length
        frequency_now = frequency + (glide_to - frequency) * t if glide_to else frequency
        phase += 2.0 * math.pi * frequency_now / SAMPLE_RATE
        if kind == "sine":
            value = math.sin(phase)
        elif kind == "square":
            value = 1.0 if math.sin(phase) >= 0 else -1.0
        elif kind == "saw":
            value = 2.0 * ((phase / (2.0 * math.pi)) % 1.0) - 1.0
        else:  # noise
            value = math.sin(phase * 12.9898) * 43758.5453 % 2.0 - 1.0
        samples.append(int(32767 * gain * level * max(-1.0, min(1.0, value))))
    return samples


def mix(*tracks):
    length = max(len(track) for track in tracks)
    out = [0] * length
    for track in tracks:
        for i, value in enumerate(track):
            out[i] += value
    peak = max(1, max(abs(value) for value in out))
    return [max(-32767, min(32767, int(value * 32767 / peak))) for value in out]


def write_wav(name, samples):
    path = os.path.join(OUTPUT_DIR, name + ".wav")
    os.makedirs(OUTPUT_DIR, exist_ok=True)
    with wave.open(path, "wb") as handle:
        handle.setnchannels(1)
        handle.setsampwidth(2)
        handle.setframerate(SAMPLE_RATE)
        handle.writeframes(b"".join(struct.pack("<h", value) for value in samples))
    print("wrote %s (%d frames)" % (name, len(samples)))


def drop():
    """A piece falling: a short downward glide plus a soft thud."""
    fall = tone(680, 0.09, "sine", 0.35, glide_to=240)
    thud = tone(120, 0.12, "sine", 0.5, glide_to=70)
    return mix(fall + [0] * int(0.02 * SAMPLE_RATE), [0] * len(fall) + thud)


def bounce():
    return tone(180, 0.07, "sine", 0.4, glide_to=320)


def buzz(step=0):
    """Neon lighting up: each piece of the winning line rings a little higher."""
    base = 520 * (1.0 + 0.12 * step)
    return mix(tone(base, 0.10, "sine", 0.5, glide_to=base * 1.25), tone(base * 2, 0.10, "sine", 0.18))


def pop():
    return tone(300, 0.16, "sine", 0.55, glide_to=90)


def rotate():
    return mix(tone(240, 0.30, "saw", 0.28, glide_to=460), tone(90, 0.30, "sine", 0.3, glide_to=150))


def bomb():
    noise = tone(90, 0.32, "noise", 0.5)
    low = tone(70, 0.34, "sine", 0.6, glide_to=40)
    return mix(noise, low)


def lock():
    return mix(tone(300, 0.06, "square", 0.3), tone(200, 0.12, "square", 0.3, glide_to=140))


def wild():
    return mix(tone(700, 0.12, "sine", 0.3, glide_to=980), tone(980, 0.12, "sine", 0.25, glide_to=700))


def tick():
    return tone(1200, 0.03, "square", 0.28)


def hint():
    return mix(tone(880, 0.08, "sine", 0.4), tone(1320, 0.10, "sine", 0.28))


def undo():
    return tone(420, 0.14, "sine", 0.35, glide_to=700)


def star(step):
    base = 660 * (1.0 + 0.18 * step)
    return mix(tone(base, 0.14, "sine", 0.5, glide_to=base * 1.5), tone(base * 1.5, 0.16, "sine", 0.3))


def win():
    return mix(tone(523, 0.14, "sine", 0.45), tone(659, 0.14, "sine", 0.4), tone(784, 0.22, "sine", 0.4))


def lose():
    return mix(tone(392, 0.18, "sine", 0.4, glide_to=294), tone(196, 0.30, "sine", 0.35, glide_to=147))


def draw():
    return mix(tone(392, 0.20, "sine", 0.35, glide_to=392), tone(294, 0.26, "sine", 0.3, glide_to=294))


def sign_lit():
    """A whole neon sign starting up: tubes lighting in sequence with a buzz."""
    tracks = []
    for step in range(6):
        offset = int(0.05 * step * SAMPLE_RATE)
        tracks.append([0] * offset + buzz(step))
    return mix(*tracks)


def click():
    return tone(900, 0.03, "square", 0.22)


def main():
    write_wav("drop", drop())
    write_wav("bounce", bounce())
    for step in range(4):
        write_wav("buzz%d" % (step + 1), buzz(step))
    write_wav("pop", pop())
    write_wav("rotate", rotate())
    write_wav("bomb", bomb())
    write_wav("lock", lock())
    write_wav("wild", wild())
    write_wav("tick", tick())
    write_wav("hint", hint())
    write_wav("undo", undo())
    for step in range(3):
        write_wav("star%d" % (step + 1), star(step))
    write_wav("win", win())
    write_wav("lose", lose())
    write_wav("draw", draw())
    write_wav("sign_lit", sign_lit())
    write_wav("click", click())


if __name__ == "__main__":
    main()

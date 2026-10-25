#!/usr/bin/env python3
"""Build a probe journal from the published witnesses, without reimplementing game rules.

Run from the repo root. Pass the output JSON as the second AndroidContentProbe argument.
The Android probe computes the two opening hashes from its packaged level data.
"""
import json
import re
import sys
from pathlib import Path

root = Path(__file__).resolve().parent.parent
first = json.loads((root / 'content/certificates/level-0050/hard.json').read_text())
second = json.loads((root / 'content/certificates/level-0050/hard-round-2.json').read_text())

def ply(step):
    match = re.fullmatch(r'Drop\(col=(\d+), kind=NORMAL\)', step['move'])
    if match is None:
        raise ValueError('Probe fixture expects classic normal drops')
    return dict(action='drop', column=int(match[1]), kind='NORMAL',
                aiStateHex=step['rngHex'], afterHash=step['afterStateHash'])

fixture = dict(levelId='level-0050', difficulty='HARD', initialHash='_ROUND1_',
               stateHash=second['steps'][0]['afterStateHash'], hintsLeft=1, undosLeft=0,
               steps=[ply(second['steps'][0])], bossHistory=[dict(
                   initialHash='_ROUND0_', stateHash=first['steps'][-1]['afterStateHash'],
                   hintsLeft=1, undosLeft=0, steps=[ply(step) for step in first['steps']])])
Path(sys.argv[1]).write_text(json.dumps(fixture) + '\n')

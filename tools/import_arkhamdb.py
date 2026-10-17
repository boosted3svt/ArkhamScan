#!/usr/bin/env python3
"""Convert a user-supplied ArkhamDB checkout to a metadata-only local catalog.
No network calls; no images, rules text, or claims of verified retail manifests.
"""
import argparse
import hashlib
import json
import pathlib
import subprocess


def convert(root: pathlib.Path):
    packs = {p['code']: p['name'] for p in json.loads((root / 'packs.json').read_text())}
    try:
        revision = subprocess.check_output(['git', '-C', str(root), 'rev-parse', 'HEAD'], text=True, stderr=subprocess.DEVNULL).strip()
    except subprocess.CalledProcessError:
        revision = 'unversioned-local-input'
    cards = {}
    files = sorted((root / 'pack').rglob('*.json'))
    for file in files:
        for c in json.loads(file.read_text()):
            if c.get('hidden') or not c.get('name'):
                continue
            pack = c.get('pack_code')
            if pack not in packs:
                raise ValueError(f'Unknown pack {pack} in {file.name}')
            item = dict(id='arkhamdb:' + str(c['code']), title=c['name'], number=str(c.get('position', '')),
                        pack=pack, packName=packs[pack], type=c.get('type_code', ''), encounter=c.get('encounter_code', ''))
            if item['id'] in cards and cards[item['id']] != item:
                raise ValueError(f'Conflicting printing ID {item["id"]}')
            cards[item['id']] = item
    if not cards:
        raise ValueError('No reference cards found')
    return dict(schemaVersion=1, source=f'https://github.com/Kamalisk/arkhamdb-json-data/tree/{revision}',
                sourceRevision=revision, rightsStatus='User must establish permitted use; no image rights granted',
                retailManifestsVerified=False, cards=sorted(cards.values(), key=lambda c: c['id']))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('checkout', type=pathlib.Path)
    parser.add_argument('output', type=pathlib.Path)
    args = parser.parse_args()
    catalog = convert(args.checkout)
    # Refuse accidental overwrite of a user file.
    with args.output.open('x', encoding='utf-8') as f:
        json.dump(catalog, f, ensure_ascii=False, indent=2)
    raw = args.output.read_bytes()
    print(f'{len(catalog["cards"])} printings; {len(set(c["pack"] for c in catalog["cards"]))} reference packs')
    print(f'SHA-256 {hashlib.sha256(raw).hexdigest()}')
    print('Reference packs and quantities have NOT been verified as retail manifests.')

if __name__ == '__main__':
    main()

#!/usr/bin/env python3
"""Read-only verification against the snapshot taken before creating new/."""
import hashlib
import json
from pathlib import Path
import sys
root=Path(__file__).resolve().parents[1]
originals=root.parent
expected=json.loads((root/'original-sha256.json').read_text(encoding='utf-8'))
changed=[]
for name,digest in expected.items():
    path=originals/name
    if not path.is_file() or hashlib.sha256(path.read_bytes()).hexdigest()!=digest:changed.append(name)
if changed:
    print('Original files changed or missing:\n'+'\n'.join(changed),file=sys.stderr)
    sys.exit(1)
print(f'All {len(expected)} original files are byte-for-byte unchanged.')

#!/usr/bin/env python3
"""Scoped Android UI capture. Every interaction requires this app in front."""
import json
import re
import shlex
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
ADB = ROOT / '.tooling/android-sdk/platform-tools/adb'
PACKAGE = 'com.parker.imageborder'
OUT = ROOT / '.tooling/audit-2.0'
OUT.mkdir(parents=True, exist_ok=True)

def adb(*args, binary=False):
    result = subprocess.run([str(ADB), '-d', *args], capture_output=True, timeout=25)
    if result.returncode:
        raise RuntimeError(result.stderr.decode(errors='replace').strip())
    return result.stdout if binary else result.stdout.decode(errors='replace')

def require_front():
    state = adb('shell', 'dumpsys', 'window')
    line = next((x for x in state.splitlines() if 'mCurrentFocus=' in x), '')
    if PACKAGE + '/' not in line:
        # Compose menus use a nameless sub-window. Verify that exact window's
        # owner and parent, rather than trusting the last focused activity.
        token = re.search(r'Window\{(\w+) .*Pop-Up Window', line)
        windows = adb('shell', 'dumpsys', 'window', 'windows') if token else ''
        owned = [b for b in windows.split('Window #')
                 if token and re.match(r'\d+ Window\{' + re.escape(token.group(1)) + r'\b', b)]
        if len(owned) != 1 or f'package={PACKAGE} ' not in owned[0] or not re.search(r'mParentWindow=.*' + re.escape(PACKAGE) + '/', owned[0]):
            raise RuntimeError('APP_NOT_FOREGROUND: stopped without interacting')

def snapshot():
    require_front()
    adb('shell', 'uiautomator', 'dump', '/sdcard/editor-audit.xml')
    raw = adb('shell', 'cat', '/sdcard/editor-audit.xml')
    require_front()
    root = ET.fromstring(raw)
    parents = {child: parent for parent in root.iter() for child in parent}
    nodes = [node for node in root.iter('node') if node.get('package') == PACKAGE]
    return nodes, parents

def bounds(node):
    return tuple(map(int, re.findall(r'\d+', node.get('bounds', ''))))

def labels(nodes):
    return [dict(text=n.get('text') or n.get('content-desc'), bounds=bounds(n),
                 kind=n.get('class').rsplit('.', 1)[-1], enabled=n.get('enabled'),
                 selected=n.get('selected'), checked=n.get('checked'))
            for n in nodes if n.get('text') or n.get('content-desc') or n.get('class') == 'android.widget.EditText']

command = sys.argv[1]
try:
    if command == 'start':
        adb('shell', 'am', 'start', '-n', PACKAGE + '/.MainActivity')
        time.sleep(.5)
        require_front()
    elif command == 'inspect':
        items = labels(snapshot()[0])
        if len(sys.argv) > 2:
            items = [item for item in items if re.search(sys.argv[2], item['text'] or '')]
        print(json.dumps(items, ensure_ascii=False))
    elif command == 'capture':
        name = Path(sys.argv[2]).name
        require_front()
        pixels = adb('exec-out', 'screencap', '-p', binary=True)
        require_front()
        path = OUT / (name + '.png')
        path.write_bytes(pixels)
        nodes, _ = snapshot()
        (OUT / (name + '.json')).write_text(json.dumps(labels(nodes), ensure_ascii=False, indent=2))
        print(path)
    elif command in ('tap', 'field'):
        nodes, parents = snapshot()
        query = sys.argv[2] if command == 'tap' else None
        candidates = []
        for node in nodes:
            if command == 'field':
                match = node.get('class') == 'android.widget.EditText'
            else:
                match = node.get('text') == query or node.get('content-desc') == query
            if not match:
                continue
            target = node
            while target is not None and target.get('clickable') != 'true':
                target = parents.get(target)
            if target is not None and target.get('package') == PACKAGE and target.get('enabled') == 'true':
                if target not in candidates:
                    candidates.append(target)
        if len(candidates) != 1:
            raise RuntimeError(f'Expected one actionable match; found {len(candidates)}')
        x1,y1,x2,y2 = bounds(candidates[0])
        require_front()
        adb('shell', 'input', 'tap', str((x1+x2)//2), str((y1+y2)//2))
    elif command == 'scroll':
        nodes, _ = snapshot()
        regions = [n for n in nodes if n.get('scrollable') == 'true']
        regions = [n for n in regions if bounds(n)[3]-bounds(n)[1] > 250 and bounds(n)[1] > 800]
        if len(regions) != 1:
            raise RuntimeError(f'Expected one tool panel; found {len(regions)}')
        x1,y1,x2,y2 = bounds(regions[0]);require_front()
        adb('shell','input','swipe',str((x1+x2)//2),str(y2-50),str((x1+x2)//2),str(y1+50),'350')
    elif command == 'gallery_scroll':
        nodes, _ = snapshot()
        if not any(n.get('text') == '选择照片' for n in nodes):
            raise RuntimeError('Native gallery is not open')
        regions = [n for n in nodes if n.get('scrollable') == 'true' and bounds(n)[3] - bounds(n)[1] > 500]
        if len(regions) != 1:
            raise RuntimeError(f'Expected one gallery grid; found {len(regions)}')
        x1,y1,x2,y2 = bounds(regions[0]); require_front()
        direction = sys.argv[2] if len(sys.argv) > 2 else 'down'
        start, end = (y2-80, y1+80) if direction == 'down' else (y1+80, y2-80)
        adb('shell','input','swipe',str((x1+x2)//2),str(start),str((x1+x2)//2),str(end),'350')
    elif command == 'back':
        require_front();adb('shell','input','keyevent','KEYCODE_BACK')
    elif command == 'text':
        nodes, _ = snapshot()
        fields = [n for n in nodes if n.get('class') == 'android.widget.EditText' and n.get('focused') == 'true']
        if len(fields) != 1:
            raise RuntimeError('No single focused app text field')
        require_front();adb('shell','input','text',shlex.quote(sys.argv[2].replace(' ','%s')))
    else:
        raise RuntimeError('Unknown command')
except Exception as error:
    print(str(error), file=sys.stderr)
    sys.exit(3)

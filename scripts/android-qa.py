#!/usr/bin/env python3
"""Focused QA capture following Test Android Apps' UI-tree coordinate rule."""
import argparse
import pathlib
import re
import subprocess
import time
import xml.etree.ElementTree as ET

p = argparse.ArgumentParser()
p.add_argument('--serial', required=True)
p.add_argument('--package', default='app.englishdub')
p.add_argument('--adb', default='adb')
p.add_argument('--output', required=True)
p.add_argument('--playback', action='store_true', help='Open the generated English-dub test fixture')
a = p.parse_args()
out = pathlib.Path(a.output)
out.mkdir(parents=True, exist_ok=True)

def adb(*args, timeout=45):
    return subprocess.check_output([a.adb, '-s', a.serial, *args], timeout=timeout)

def save(name, content):
    (out / name).write_bytes(content)

def dump(name):
    data = adb('exec-out', 'uiautomator', 'dump', '/dev/tty')
    start = data.find(b'<?xml')
    if start < 0:
        raise RuntimeError('No UI XML returned; inspect emulator responsiveness')
    end = data.rfind(b'</hierarchy>') + len(b'</hierarchy>')
    xml = data[start:end]
    save(name + '.xml', xml)
    return ET.fromstring(xml)

def tap_text(text, name):
    tree = dump(name)
    node = next((n for n in tree.iter('node') if n.get('text') == text), None)
    for attempt in range(4):
        if node is not None:
            break
        # The skill requires scrolling once before concluding a scrollable target is absent.
        scroll = next((n for n in tree.iter('node') if n.get('scrollable') == 'true'), None)
        if scroll is not None:
            x1, y1, x2, y2 = map(int, re.findall(r'\d+', scroll.get('bounds', '')))
            x = (x1 + x2) // 2
            adb('shell', 'input', 'swipe', str(x), str((y1 + 3 * y2) // 4), str(x), str((3 * y1 + y2) // 4), '400')
            tree = dump(name + '-scrolled-' + str(attempt + 1))
            node = next((n for n in tree.iter('node') if n.get('text') == text), None)
    if node is None:
        raise RuntimeError('UI target missing: ' + text)
    x1, y1, x2, y2 = map(int, re.findall(r'\d+', node.get('bounds', '')))
    adb('shell', 'input', 'tap', str((x1 + x2) // 2), str((y1 + y2) // 2))
    time.sleep(1)

save('device.txt', adb('shell', 'getprop'))
try:
    adb('logcat', '-c')
except subprocess.CalledProcessError as error:
    save('logcat-clear-warning.txt', str(error).encode())
adb('shell', 'am', 'force-stop', a.package)
activity = adb('shell', 'cmd', 'package', 'resolve-activity', '--brief', a.package).decode().strip().splitlines()[-1]
if '/' not in activity:
    raise RuntimeError('No launchable activity: ' + activity)
save('startup.txt', adb('shell', 'am', 'start', '-W', '-n', activity, timeout=90))
time.sleep(2)
dump('library')
save('library.png', adb('exec-out', 'screencap', '-p'))
save('meminfo-library.txt', adb('shell', 'dumpsys', 'meminfo', a.package))
adb('shell', 'dumpsys', 'gfxinfo', a.package, 'reset')
tap_text('Offline setup', 'before-offline')
offline_tree = dump('offline')
if not any('English voice' in n.get('text', '') for n in offline_tree.iter('node')):
    raise RuntimeError('Offline setup did not open; inspect offline.xml')
save('offline.png', adb('exec-out', 'screencap', '-p'))
tap_text('Library', 'before-library-return')
returned = dump('library-return')
if not any('Open a video' in n.get('text', '') for n in returned.iter('node')):
    raise RuntimeError('Library did not reopen; inspect library-return.xml')
save('gfxinfo-navigation.txt', adb('shell', 'dumpsys', 'gfxinfo', a.package))
save('meminfo-return.txt', adb('shell', 'dumpsys', 'meminfo', a.package))
if a.playback:
    tap_text('Watch in English', 'before-player')
    save('player.png', adb('exec-out', 'screencap', '-p'))
    save('meminfo-player.txt', adb('shell', 'dumpsys', 'meminfo', a.package))
    player_tree = dump('player')
    if not any('Prepared English dub' in n.get('text', '') for n in player_tree.iter('node')):
        raise RuntimeError('Prepared English player did not open')
    adb('shell', 'input', 'keyevent', '4')
save('crash-log.txt', adb('logcat', '-b', 'crash', '-d'))
save('logcat.txt', adb('logcat', '-d'))
print('QA artifacts:', out)

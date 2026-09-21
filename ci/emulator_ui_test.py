#!/usr/bin/env python3
"""GetSub emulator UI-test driver (CI Stage 1).

Drives the installed app on a GitHub Actions Android emulator purely through
`adb` + `uiautomator dump` — no gradle, no androidx, no on-device test APK,
matching the project's zero-dependency build philosophy.

Steps (each captures screenshots into $GETSUB_ARTIFACTS, default ci-artifacts/):
  1. install APK, grant POST_NOTIFICATIONS
  2. launch MainActivity, assert window focus
  3. home UI renders (header, tagline, input field, Get, RECENT DOWNLOADS,
     CLEAR, empty state)
  4. paste flow: type URL into the field, tap Get -> job row appears ->
     reaches terminal status (DONE, or ERROR from YouTube's datacenter-IP
     bot check -- BOTH prove service+JobStore+list wiring end to end)
  5. share flow: real ACTION_SEND intent at ShareActivity -> second job
     logged and terminal
  6. CLEAR flow: confirm dialog -> Clear -> empty state returns
  7. relaunch from HOME: state persists

Offline self-test (no device):  python3 ci/emulator_ui_test.py --selftest
Runs the dump-parsing / node-finding / bounds-tap math against synthetic
uiautomator XML matching what MainActivity actually renders, so the driver
logic itself is verified even where no emulator exists.
"""
import json
import os
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

PKG = 'com.getsub.share'
MAIN = PKG + '/.MainActivity'
SHARE = PKG + '/.ShareActivity'
APK = os.environ.get('GETSUB_APK', 'build/apk/app.apk')
ART = os.environ.get('GETSUB_ARTIFACTS', 'ci-artifacts')
URL1 = 'https://www.youtube.com/watch?v=GYo-NG7tbok'
URL2 = 'https://youtu.be/dQw4w9WgXcQ'
JOB_TIMEOUT = int(os.environ.get('GETSUB_JOB_TIMEOUT', '150'))

RESULTS = []
_shot = 0

# ---------------------------------------------------------------- utilities

def log(msg):
    print('[ui-test] ' + str(msg), flush=True)


def record(name, ok, detail=''):
    RESULTS.append((name, bool(ok), detail))
    log(('PASS  ' if ok else 'FAIL  ') + name + ('  | ' + detail if detail else ''))


def adb(*args, **kw):
    binary = kw.get('binary', False)
    check = kw.get('check', True)
    cmd = ['adb'] + [str(a) for a in args]
    p = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    if check and p.returncode != 0:
        raise RuntimeError('adb failed (%d): %s\n%s'
                           % (p.returncode, ' '.join(cmd),
                              p.stderr.decode('utf-8', 'ignore')[:1500]))
    return p.stdout if binary else p.stdout.decode('utf-8', 'ignore')


def shell(cmd, check=False):
    p = subprocess.run(['adb', 'shell', cmd],
                       stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    out = p.stdout.decode('utf-8', 'ignore')
    if check and p.returncode != 0:
        raise RuntimeError('adb shell failed (%d): %s\n%s'
                           % (p.returncode, cmd, out[:1500]))
    return out


def dump_ui(retries=6):
    """Capture the current view hierarchy via uiautomator dump.

    STRICT by design (v2.5.3-ci2): the on-device dump file is deleted first,
    so a failed/hung dump can never be masked by a stale previous capture
    (run #1's bug: waits kept re-reading the empty-home hierarchy while the
    real screen showed the job row). Hung dumps are retried; UiAutomation
    needs a settle moment between attempts after a timeout.
    """
    last = ''
    for attempt in range(retries):
        shell('rm -f /sdcard/getsub_ui.xml')
        shell('uiautomator dump /sdcard/getsub_ui.xml')
        xml = adb('exec-out', 'cat', '/sdcard/getsub_ui.xml', check=False)
        last = xml[:200]
        if xml.strip().startswith('<'):
            try:
                return ET.fromstring(xml)
            except ET.ParseError:
                pass
        # A dump that times out (screen never went idle) leaves its
        # UiAutomation connection registered, which can hang the NEXT dump
        # too (seen in CI run #1). Kill leftovers before retrying.
        shell('pkill -f com.android.commands.uiautomator', check=False)
        time.sleep(3)
    raise RuntimeError('could not capture UI dump (last: %r)' % last)


def notif_state():
    """Raw notification_manager dump (records carry pkg + id, NOT the text).

    Used to assert WHICH notifications exist: id 1001 = ongoing foreground
    notification, ids 2000+ = per-job result notifications (v2.5.3)."""
    return shell('dumpsys notification_manager')


def notif_has_id(nid):
    for line in notif_state().splitlines():
        if ('id=%d' % nid) in line and PKG in line:
            return True
    return False


def jobs_state():
    """JobStore contents read straight from the app's private dir.

    AOSP emulator images allow `adb root`, so the tests can observe the real
    state machine (Queued -> Done/Error) with zero UI dependence. Returns a
    newest-first list of (id, status, result), or None if unreadable."""
    try:
        out = shell('cat /data/data/%s/files/jobs.json 2>/dev/null || echo []' % PKG)
        arr = json.loads(out.strip() or '[]')
        return [(j.get('id'), j.get('status'), j.get('result', ''))
                for j in reversed(arr)]
    except Exception:
        return None


def wait_jobs_terminal(min_count, timeout=JOB_TIMEOUT):
    """Wait until >= min_count jobs exist and none is Queued anymore."""
    def pred():
        st = jobs_state()
        if st is None:  # oracle fallback: result notifications appeared
            n = notif_state()
            done = sum(1 for i in range(2000, 2100)
                       if any(('id=%d' % i) in l and PKG in l for l in n.splitlines()))
            return done >= min_count
        return len(st) >= min_count and all(s[1] != 'Queued' for s in st)
    wait_until('%d job(s) terminal' % min_count, pred, timeout=timeout)
    return jobs_state() or []


BOUNDS = re.compile(r'\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]')


def nodes(root, text=None, text_contains=None, cls=None, rid=None):
    hits = []
    for n in root.iter('node'):
        if text is not None and n.get('text') != text:
            continue
        if text_contains is not None and text_contains not in (n.get('text') or ''):
            continue
        if cls is not None and n.get('class') != cls:
            continue
        if rid is not None and n.get('resource-id') != rid:
            continue
        hits.append(n)
    return hits


def center(node):
    m = BOUNDS.search(node.get('bounds', ''))
    if not m:
        raise RuntimeError('node without bounds: %s' % node.get('class'))
    x1, y1, x2, y2 = (int(v) for v in m.groups())
    return (x1 + x2) // 2, (y1 + y2) // 2


def tap_node(n):
    x, y = center(n)
    log('tap (%d,%d) on %s %r' % (x, y, n.get('class'), (n.get('text') or '')[:40]))
    shell('input tap %d %d' % (x, y), check=True)


def screenshot(name):
    global _shot
    _shot += 1
    path = os.path.join(ART, '%02d_%s.png' % (_shot, name))
    data = adb('exec-out', 'screencap', '-p', binary=True, check=False)
    if data and data[:8] == b'\x89PNG\r\n\x1a\n':
        with open(path, 'wb') as f:
            f.write(data)
        log('screenshot -> ' + path)
    else:
        log('WARNING: screencap failed for ' + name)


def wait_until(desc, pred, timeout=30, interval=2):
    deadline = time.time() + timeout
    while time.time() < deadline:
        try:
            if pred():
                return True
        except Exception:
            pass
        time.sleep(interval)
    raise RuntimeError('timeout (%ds) waiting for: %s' % (timeout, desc))


def job_statuses(root):
    """Status badge texts of visible job rows, newest first."""
    return [n.get('text') for n in nodes(root, cls='android.widget.TextView')
            if n.get('text') in ('QUEUED', 'DONE', 'ERROR')]


def focused_on_pkg():
    out = shell('dumpsys window | grep -E "mCurrentFocus|mFocusedApp"')
    return PKG in out


# ------------------------------------------------------------------- steps

def step_boot_install():
    wait_until('device boot', lambda: shell('getprop sys.boot_completed').strip() == '1',
               timeout=180)
    log('boot complete; Android ' + shell('getprop ro.build.version.release').strip())
    adb('root', check=False)          # AOSP images allow it; enables jobs.json oracle
    adb('wait-for-device', check=False)
    time.sleep(2)
    adb('install', '-r', '-t', APK, check=True)
    shell('pm grant %s android.permission.POST_NOTIFICATIONS' % PKG)  # API33+; harmless no-op otherwise
    record('APK installs on fresh emulator', True)
    record('jobs.json oracle available', jobs_state() is not None,
           'adb root state machine access' if jobs_state() is not None else 'falling back to notifications')


def step_launch():
    adb('shell', 'am', 'start', '-n', MAIN, check=True)
    wait_until('MainActivity focused', focused_on_pkg, timeout=30)
    record('app launches, MainActivity gains focus', True)


def step_home_ui():
    root = dump_ui()
    checks = [
        ('header "GetSub"', nodes(root, text='GetSub')),
        ('tagline', nodes(root, text_contains='subtitle downloader')),
        ('link input field', nodes(root, cls='android.widget.EditText')),
        ('Get button', nodes(root, text='Get')),
        ('RECENT DOWNLOADS label', nodes(root, text='RECENT DOWNLOADS')),
        ('CLEAR button', nodes(root, text='CLEAR')),
        ('empty state ("No downloads yet")', nodes(root, text='No downloads yet')),
    ]
    for name, hits in checks:
        record('home UI: ' + name, len(hits) >= 1)
    screenshot('home_empty')


def step_paste_flow():
    root = dump_ui()
    edits = nodes(root, cls='android.widget.EditText')
    if not edits:
        raise RuntimeError('link input field not found')
    tap_node(edits[0])
    time.sleep(1.5)
    shell("input text '%s'" % URL1, check=True)
    time.sleep(1)
    screenshot('typed_url')
    shell('input keyevent 4')  # dismiss soft keyboard so Get is reachable
    time.sleep(1)

    root = dump_ui()
    gets = nodes(root, text='Get')
    if not gets:
        raise RuntimeError('Get button not found after typing')
    tap_node(gets[0])

    # State machine oracle = jobs.json via root shell (UI is never polled
    # while animations run: spinner/toast/heads-up keep uiautomator's idle
    # state from ever arriving, see capsule §30).
    wait_until('job row in JobStore', lambda: len(jobs_state() or []) >= 1, timeout=20)
    # While the job is Queued, the foreground notification (id 1001) must exist.
    ongoing_seen = False
    for _ in range(8):
        if notif_has_id(1001):
            ongoing_seen = True
            break
        time.sleep(1)
    record('paste flow: foreground notification while fetching', ongoing_seen)
    screenshot('job_queued')

    st = wait_jobs_terminal(1)
    record('paste flow: job reached terminal status %s' % (st[0][1] if st else '?',),
           st and st[0][1] in ('Done', 'Error'))

    time.sleep(7)  # let the heads-up retract + list poller settle -> static screen
    record('paste flow: result notification posted (id 2000+)', notif_has_id(2000 + int(st[0][0])) if st else False)
    root = dump_ui()
    ui_st = job_statuses(root)
    record('paste flow: list row shows %s' % (ui_st or '?'), ui_st and ui_st[0] in ('DONE', 'ERROR'))

    texts = [n.get('text') or '' for n in nodes(root, cls='android.widget.TextView')]
    has_result = any(t.startswith('Saved: ') or t.startswith('YouTube: ')
                     or t.startswith('Could not ') or t.startswith('No ')
                     for t in texts)
    record('paste flow: result line rendered', has_result)
    screenshot('job_terminal')


def step_share_flow():
    shell("am start -a android.intent.action.SEND -t 'text/plain' "
          "--eu android.intent.extra.TEXT '%s' -n %s" % (URL2, SHARE), check=True)
    wait_until('focus back on MainActivity', focused_on_pkg, timeout=20)
    wait_until('second job in JobStore', lambda: len(jobs_state() or []) >= 2, timeout=20)
    record('share intent: ShareActivity logged a second job', True)
    st = wait_jobs_terminal(2)
    record('share intent: both jobs terminal', len(st) >= 2 and all(s[1] != 'Queued' for s in st))
    time.sleep(7)  # heads-up retract + settle -> static screen for the dump
    root = dump_ui()
    ui_st = job_statuses(root)
    record('share intent: list shows both rows %s' % (ui_st or '?'), len(ui_st) >= 2)
    screenshot('share_terminal')


def step_clear():
    root = dump_ui()
    clears = nodes(root, text='CLEAR')
    if not clears:
        raise RuntimeError('CLEAR button not found')
    tap_node(clears[0])
    wait_until('confirm dialog',
               lambda: nodes(dump_ui(), text_contains='Clear history?'), timeout=10)
    record('clear: confirmation dialog appears', True)
    screenshot('clear_dialog')

    btns = nodes(dump_ui(), text='Clear', cls='android.widget.Button')
    if not btns:
        raise RuntimeError('dialog Clear button not found')
    tap_node(btns[0])
    wait_until('empty state returns',
               lambda: nodes(dump_ui(), text='No downloads yet'), timeout=10)
    record('clear: history emptied, empty state back', True)
    screenshot('cleared')


def step_relaunch():
    shell('input keyevent 3')  # HOME
    time.sleep(1.5)
    adb('shell', 'am', 'start', '-a', 'android.intent.action.MAIN',
        '-c', 'android.intent.category.LAUNCHER', '-n', MAIN, check=True)
    wait_until('MainActivity focused again', focused_on_pkg, timeout=20)
    wait_until('persisted empty state',
               lambda: nodes(dump_ui(), text='No downloads yet'), timeout=10)
    record('relaunch from launcher: state persisted', True)
    screenshot('relaunch')


# ---------------------------------------------------------------- selftest

HOME_XML = '''<hierarchy rotation="0">
 <node class="android.widget.LinearLayout" bounds="[0,0][1080,1920]" package="com.getsub.share">
  <node class="android.widget.TextView" text="GetSub" bounds="[60,150][300,210]"/>
  <node class="android.widget.TextView" text="YouTube subtitle downloader" bounds="[120,214][700,250]"/>
  <node class="android.widget.EditText" text="" bounds="[60,300][800,390]"/>
  <node class="android.widget.Button" text="Get" bounds="[830,300][1020,390]"/>
  <node class="android.widget.TextView" text="RECENT DOWNLOADS" bounds="[60,450][500,480]"/>
  <node class="android.widget.TextView" text="CLEAR" bounds="[900,450][1020,480]"/>
  <node class="android.widget.LinearLayout" resource-id="android:id/empty" bounds="[0,500][1080,1920]">
   <node class="android.widget.TextView" text="No downloads yet" bounds="[300,850][780,900]"/>
  </node>
  <node class="android.widget.ListView" bounds="[0,500][1080,1920]"/>
 </node>
</hierarchy>'''

JOBS_XML = '''<hierarchy rotation="0">
 <node class="android.widget.ListView" bounds="[0,500][1080,1920]" package="com.getsub.share">
  <node class="android.widget.LinearLayout" bounds="[36,516][1044,700]">
   <node class="android.widget.TextView" text="ERROR" bounds="[80,540][200,570]"/>
   <node class="android.widget.TextView" text="https://youtu.be/dQw4w9WgXcQ" bounds="[80,580][1000,610]"/>
   <node class="android.widget.TextView" text="YouTube: Sign in to confirm you're not a bot" bounds="[80,620][1000,650]"/>
  </node>
  <node class="android.widget.LinearLayout" bounds="[36,716][1044,900]">
   <node class="android.widget.TextView" text="DONE" bounds="[80,740][200,770]"/>
   <node class="android.widget.TextView" text="https://www.youtube.com/watch?v=GYo-NG7tbok" bounds="[80,780][1000,810]"/>
   <node class="android.widget.TextView" text="Saved: Actually a Wild Situation.txt" bounds="[80,820][1000,850]"/>
  </node>
 </node>
</hierarchy>'''

DIALOG_XML = '''<hierarchy rotation="0">
 <node class="android.widget.FrameLayout" bounds="[0,0][1080,1920]" package="com.getsub.share">
  <node class="android.widget.TextView" resource-id="android:id/alertTitle" text="Clear history?" bounds="[120,800][960,860]"/>
  <node class="android.widget.TextView" resource-id="android:id/message" text="Remove all 2 downloads from this list? Saved .txt files in Download/ are kept." bounds="[120,880][960,980]"/>
  <node class="android.widget.Button" resource-id="android:id/button2" text="Cancel" bounds="[500,1000][700,1080]"/>
  <node class="android.widget.Button" resource-id="android:id/button1" text="Clear" bounds="[740,1000][940,1080]"/>
 </node>
</hierarchy>'''


def selftest():
    fails = 0
    root = ET.fromstring(HOME_XML)
    checks = [
        ('find header text', len(nodes(root, text='GetSub')) == 1),
        ('find tagline contains', len(nodes(root, text_contains='subtitle downloader')) == 1),
        ('find EditText by class', len(nodes(root, cls='android.widget.EditText')) == 1),
        ('Get button center math', center(nodes(root, text='Get')[0]) == (925, 345)),
        ('empty state present', len(nodes(root, text='No downloads yet')) == 1),
        ('empty view by resource-id', len(nodes(root, rid='android:id/empty')) == 1),
        ('text match is case-sensitive (CLEAR vs Clear)',
         len(nodes(root, text='CLEAR')) == 1 and len(nodes(root, text='Clear')) == 0),
        ('no job rows on empty home', job_statuses(root) == []),
    ]
    root2 = ET.fromstring(JOBS_XML)
    checks += [
        ('job statuses newest-first', job_statuses(root2) == ['ERROR', 'DONE']),
        ('error result line found',
         len(nodes(root2, text_contains='YouTube: Sign in')) == 1),
        ('saved result line found',
         any((n.get('text') or '').startswith('Saved: ')
             for n in nodes(root2, cls='android.widget.TextView'))),
    ]
    root3 = ET.fromstring(DIALOG_XML)
    checks += [
        ('dialog title found', len(nodes(root3, text_contains='Clear history?')) == 1),
        ('dialog positive button (exact text+class)',
         len(nodes(root3, text='Clear', cls='android.widget.Button')) == 1),
        ('dialog button center math', center(nodes(root3, text='Clear')[0]) == (840, 1040)),
    ]
    for name, ok in checks:
        print(('PASS  ' if ok else 'FAIL  ') + name)
        if not ok:
            fails += 1
    print('SELFTEST: %d/%d passed' % (len(checks) - fails, len(checks)))
    sys.exit(1 if fails else 0)


# -------------------------------------------------------------------- main

def main():
    os.makedirs(ART, exist_ok=True)
    steps = [step_boot_install, step_launch, step_home_ui, step_paste_flow,
             step_share_flow, step_clear, step_relaunch]
    crashed = None
    for s in steps:
        log('--- %s ---' % s.__name__)
        try:
            s()
        except Exception as e:
            crashed = (s.__name__, e)
            record(s.__name__ + ' CRASHED', False, str(e)[:300])
            try:
                screenshot('crash_' + s.__name__)
            except Exception:
                pass
            break

    passed = sum(1 for _, ok, _ in RESULTS if ok)
    lines = ['%s: %s%s' % ('PASS' if ok else 'FAIL', name, (' | ' + d) if d else '')
             for name, ok, d in RESULTS]
    report = '\n'.join(lines) + '\n\nTOTAL: %d/%d checks passed' % (passed, len(RESULTS))
    with open(os.path.join(ART, 'report.txt'), 'w') as f:
        f.write(report + '\n')
    print(report, flush=True)
    sys.exit(0 if (passed == len(RESULTS) and crashed is None) else 1)


if __name__ == '__main__':
    if '--selftest' in sys.argv:
        selftest()
    main()

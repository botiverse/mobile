"""Measure a clean, signed-out Pixel before any credentials enter the emulator."""
import json
import re
import subprocess
import time
import xml.etree.ElementTree as ET


def adb(*args, check=True):
    return subprocess.run(['adb', *args], stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                          text=True, errors='replace', timeout=45, check=check).stdout


def crash_report(text):
    processes = list(dict.fromkeys(re.findall(r'\bProcess:\s*([A-Za-z][\w.]*(?::[\w.]+)?)(?=[,\s]|$)', text)))
    exceptions = list(dict.fromkeys(re.findall(r'\b((?:[A-Za-z_$][\w$]*\.)+[\w$]*(?:Exception|Error))\b', text)))
    frames = list(dict.fromkeys(re.findall(r'\bat\s+([\w.$<>-]+\((?:[\w.$-]+:(?:\d+)|Native Method|Unknown Source)\))', text)))
    return {'processes': processes[:6], 'exceptions': exceptions[:6], 'frames': frames[:24],
            'fatal': 'FATAL EXCEPTION' in text, 'duplicate_key': 'was used multiple times' in text,
            'rect_list': 'not found in RectList' in text}


def write(label, data):
    print(label, json.dumps(data, separators=(',', ':')), flush=True)


def dialog_facts(tree):
    win = adb('shell','dumpsys','window','windows',check=False)
    focus = next((x for x in win.splitlines() if 'mCurrentFocus=' in x), '')
    owner = re.search(r'(?:Application (?:Not Responding|Error):\s*)([A-Za-z][\w.:-]*)', focus, re.I)
    anr = adb('shell','dumpsys','activity','lastanr',check=False)
    records = adb('logcat','-b','events','-d','-s','am_anr:I','am_crash:I',check=False)
    event_names = re.findall(r'\b(am_anr|am_crash)\s*:\s*\[\d+,\d+,([A-Za-z][\w.:_-]*)',records)
    fixed = ('System UI', 'Process system', 'Pixel Launcher', 'Google Play services', 'Raft', 'Chrome', 'Settings')
    labels = []
    xml = re.search(r'(<hierarchy\b.*?</hierarchy>)',tree,re.S)
    if xml:
        for node in ET.fromstring(xml[1]).iter('node'):
            if node.get('package') != 'android': continue
            for label in fixed:
                if node.get('text','').casefold().startswith(label.casefold()+' '): labels.append(label)
    return {'owner':owner[1] if owner else None,'labels':sorted(set(labels)),
            'wait_button':'android:id/aerr_wait' in tree,'restart_button':'android:id/aerr_restart' in tree,
            'anr_processes':re.findall(r'ANR in ([A-Za-z][\w.:_-]*)',anr)[:3],'recent_events':event_names[-5:]}


def thread_report(data):
    # Match the first traced process and its own main thread, never another process further down a dump.
    section = re.split(r'(?m)^----- end \d+ -----',data,maxsplit=1)[0]
    process = re.search(r'(?m)^Cmd line:\s*([A-Za-z][\w.:_-]*)',section)
    thread = re.search(r'(?ms)^"main"[^\n]*\n(.*?)(?=^"[^\n]+"|^-----|\Z)',section)
    stack = thread[1] if thread else ''
    return {'process':process[1] if process else None,'main_present':bool(thread),
            'main_frames':crash_report(stack)['frames'],'launch_gate':'RaftDatabaseLaunchGate' in stack,'base_pager':'BasePager' in stack}


def latest_drop(tag):
    dump = adb('shell','dumpsys','dropbox','--print',tag,check=False)
    entries = list(re.finditer(r'(?m)^\d{4}-\d{2}-\d{2}[^\n]*\b'+re.escape(tag)+r'\s+\([^\n]*\)\s*$',dump))
    entry = dump[entries[-1].end():] if entries else ''
    return {'entry_found':bool(entries),**crash_report(entry),**thread_report(entry)}


def latest_anr():
    def files():
        return [name.strip() for name in adb('shell','ls','-1t','/data/anr',check=False).splitlines()
                if re.fullmatch(r'(?:anr_[\w.-]+|traces\.txt)',name.strip())]
    names=files()
    if not names:
        adb('root', check=False)
        adb('wait-for-device', check=False)
        names=files()
    trace=adb('exec-out','cat','/data/anr/'+names[0],check=False) if names else ''
    return {'readable':bool(trace),**thread_report(trace)}


def capture(tree):
    write('QA231_DIALOG',dialog_facts(tree))
    write('QA231_CRASH_BUFFER',crash_report(adb('logcat','-b','crash','-d',check=False)))
    write('QA231_LATEST_DROPBOX',latest_drop('data_app_crash'))
    app_anr=latest_drop('data_app_anr')
    write('QA231_DATA_APP_ANR',app_anr)
    write('QA231_SYSTEM_APP_ANR',latest_drop('system_app_anr'))
    if not app_anr['main_present']: write('QA231_TRACE',latest_anr())


before = adb('exec-out','uiautomator','dump','/dev/tty',check=False)
write('QA231_BEFORE_LAUNCH',{'dialog':'android:id/aerr_close' in before})
if 'android:id/aerr_close' in before:
    capture(before)

for launch in range(1, 4):
    if launch > 1:
        adb('shell', 'am', 'force-stop', 'build.raft.app')
    # Use the same launcher/fallback as qa-acceptance. The emulator is unique to this job.
    result = subprocess.run(['adb','shell','monkey','-p','build.raft.app','-c','android.intent.category.LAUNCHER','1'],
                            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=30)
    if result.returncode:
        adb('shell','am','start','-n','build.raft.app/.MainActivity')
    time.sleep(8)
    signed_out = False
    for observation in range(3):
        tree = adb('exec-out','uiautomator','dump','/dev/tty', check=False)
        dialog = 'android:id/aerr_close' in tree
        signed_out = 'package="build.raft.app"' in tree and 'Sign in to Raft' in tree
        write('QA231_STARTUP', {'launch': launch,'sample': observation + 1,'dialog': dialog,'app_sign_in': signed_out})
        if dialog:
            capture(tree)
            raise SystemExit(2)
        if observation < 2:
            time.sleep(4)
    if not signed_out:
        write('QA231_CRASH_BUFFER', crash_report(adb('logcat','-b','crash','-d', check=False)))
        raise SystemExit(3)

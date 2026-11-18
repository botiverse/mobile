"""Measure a clean, signed-out Pixel before any credentials enter the emulator."""
import json
import re
import subprocess
import time


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
            write('QA231_CRASH_BUFFER', crash_report(adb('logcat','-b','crash','-d', check=False)))
            drop = adb('shell','dumpsys','dropbox','--print','data_app_crash', check=False)
            entries = re.split(r'(?m)^\d{4}-\d{2}-\d{2} [^\n]* data_app_crash\b[^\n]*\n', drop)
            write('QA231_LATEST_DROPBOX', {'entry_found': len(entries) > 1, **crash_report(entries[-1] if len(entries)>1 else '')})
            raise SystemExit(2)
        if observation < 2:
            time.sleep(4)
    if not signed_out:
        write('QA231_CRASH_BUFFER', crash_report(adb('logcat','-b','crash','-d', check=False)))
        raise SystemExit(3)

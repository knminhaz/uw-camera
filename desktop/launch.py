"""Open Camera Lab with the locally configured Python and Android platform tools."""
from pathlib import Path
import json
import subprocess
import sys
import urllib.request
import webbrowser

root=Path(__file__).resolve().parent
config_path=root/'launch.local.json'
if not config_path.is_file():
    raise SystemExit('Create desktop/launch.local.json as described in desktop/README.md')
config=json.loads(config_path.read_text(encoding='utf-8-sig'))
port=int(config.get('port',8765))
url='http://127.0.0.1:'+str(port)
try:
    with urllib.request.urlopen(url+'/api/state',timeout=2) as response:
        if response.headers.get('Server','').startswith('CameraLab/'):
            webbrowser.open(url)
            raise SystemExit(0)
except (OSError,ValueError):
    pass
adb=Path(config['adb'])
if not adb.is_file(): raise SystemExit('Android platform-tools adb.exe was not found. Update desktop/launch.local.json.')
result=subprocess.run([str(adb),'start-server'])
if result.returncode: raise SystemExit('ADB server could not start. Check USB debugging and retry.')
args=[sys.executable,str(root/'server.py'),'--adb',str(adb),'--port',str(port)]
if config.get('archive'): args.extend(['--archive',config['archive']])
if config.get('package'): args.extend(['--package',config['package']])
# Open the page after the service binds; this timer exits if startup fails.
import threading
stop=threading.Event()
def open_when_ready():
    for _ in range(40):
        if stop.wait(.25): return
        try:
            with urllib.request.urlopen(url+'/api/state',timeout=1) as response:
                if response.headers.get('Server','').startswith('CameraLab/'):
                    webbrowser.open(url); return
        except OSError: pass
threading.Thread(target=open_when_ready,daemon=True).start()
print('Keep this window open while capturing. Ctrl+C stops Camera Lab.\n')
try: raise SystemExit(subprocess.call(args))
except KeyboardInterrupt: pass
finally: stop.set()

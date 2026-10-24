
import json, sys, time, datetime, urllib.request

url = sys.argv[1] if len(sys.argv) > 1 else "http://127.0.0.1:8002/status"
deadline = float(sys.argv[2]) if len(sys.argv) > 2 else 7200.0
t0 = time.time()
last = None
while time.time() - t0 < deadline:
    now = datetime.datetime.now().strftime("%H:%M:%S")
    try:
        with urllib.request.urlopen(url, timeout=10) as r:
            body = r.read().decode("utf-8", "replace")
            code = r.status
        print(f"{now} HTTP {code} {body[:160]}", flush=True)
        if code == 200:
            print(f"STATUS_OK after {time.time()-t0:.0f}s", flush=True)
            sys.exit(0)
    except Exception as e:
        msg = f"{type(e).__name__}: {e}"
        if msg != last:
            print(f"{now} FAIL {msg}", flush=True)
            last = msg
    time.sleep(10)
print("STATUS_TIMEOUT", flush=True)
sys.exit(1)

# Acts like the phone app over the internet: sends commands to Firebase, listens to the live
# state stream, and checks the simulated board (running the real firmware) against it.
import json, subprocess, sys, threading, time, os

DB = "airqualityproject-e49ae-default-rtdb.firebaseio.com"
KEY = "ghSIMtest" + str(int(time.time()))[-8:] + "zzzzzz"
BASE = f"https://{DB}/hbrt/{KEY}"
HERE = os.path.dirname(os.path.abspath(__file__))
now = lambda: int(time.time() * 1000)


def curl(method, path, body=None):
    a = ["curl", "-s", "-m", "10", "-X", method, BASE + path + ".json"]
    if body is not None: a += ["--data-binary", json.dumps(body)]
    return subprocess.run(a, capture_output=True, text=True).stdout


pins, stream, lock = [], [], threading.Lock()


def board_reader(p):
    for line in p.stdout:
        line = line.strip()
        if line.startswith("PIN"):
            _, d, v, t = line.split()
            with lock: pins.append((d, int(v), int(t)))
        elif line: print("   board:", line)


def stream_reader():
    p = subprocess.Popen(["curl", "-s", "-N", "-H", "Accept: text/event-stream", BASE + "/live/s.json"], stdout=subprocess.PIPE, text=True)
    ev = None
    doc = {}
    for line in p.stdout:
        line = line.strip()
        if line.startswith("event:"): ev = line[6:].strip()
        elif line.startswith("data:") and ev in ("put", "patch"):
            d = json.loads(line[5:])
            path, data = d["path"], d["data"]
            if path == "/" and ev == "put": doc = data or {}
            elif path == "/": doc.update(data or {})
            else: doc[path.strip("/")] = data
            with lock: stream.append((now(), dict(doc)))


def heartbeat():
    while True:
        curl("PUT", "/in/seen", {".sv": "timestamp"})
        time.sleep(5)


board = subprocess.Popen([HERE + "/cloudsim", KEY, sys.argv[1] if len(sys.argv) > 1 else "150"], stdout=subprocess.PIPE, text=True)
threading.Thread(target=board_reader, args=(board,), daemon=True).start()
threading.Thread(target=heartbeat, daemon=True).start()
time.sleep(4)
threading.Thread(target=stream_reader, daemon=True).start()

# wait until the board's state is in the cloud
t0 = time.time()
while time.time() - t0 < 40:
    with lock:
        if stream and stream[-1][1].get("fw"): break
    time.sleep(0.2)
print("board online, firmware", stream[-1][1].get("fw") if stream else "?")
time.sleep(8)   # let it switch to fast mode


def send(d, v):
    body = {"device": d, "value": v, "t": {".sv": "timestamp"}}
    if d != "mode": body["mins"] = 0
    t = now()
    curl("POST", "/in/cmd", body)
    return t


results, fails = [], 0


def measure(d, v):
    global fails
    with lock: ns, np_ = len(stream), len(pins)
    t = send(d, v)
    relay = app = None
    deadline = time.time() + 15
    while time.time() < deadline and (app is None or (relay is None and d != "mode")):
        with lock:
            for (dd, vv, tt) in pins[np_:]:
                if dd == d and vv == (1 if v else 0) and relay is None: relay = tt - t
            for (tt, doc) in stream[ns:]:
                want = doc.get("mode") == v if d == "mode" else doc.get(d) == v
                if want and app is None: app = tt - t
        time.sleep(0.02)
    # no flicker: after the state showed the new value it must never go back
    time.sleep(3)
    flick = False
    with lock:
        seen = False
        for (tt, doc) in stream[ns:]:
            ok = doc.get("mode") == v if d == "mode" else doc.get(d) == v
            if ok: seen = True
            elif seen: flick = True
        relay_now = [x for x in pins if x[0] == d]
    bad = app is None or (d != "mode" and relay is None) or flick
    if bad: fails += 1
    print(f"  {'FAIL' if bad else 'ok  '}  {d:6} -> {str(v):6}  relay after {relay if relay is not None else '--':>5} ms   app sees it after {app if app is not None else '--':>5} ms{'   FLICKER' if flick else ''}")
    results.append((relay, app))


print("\nCommands through the internet, like the app:")
measure("mode", "manual")
for i in range(4):
    measure("pump", True)
    measure("pump", False)
measure("lights", True)
measure("fan", True)
measure("lights", False)
measure("fan", False)
measure("heater", True)
measure("heater", False)

rel = [r for r, a in results if r is not None]
app = [a for r, a in results if a is not None]
print(f"\nrelay switched: avg {sum(rel)//max(1,len(rel))} ms, worst {max(rel) if rel else '--'} ms")
print(f"app updated:    avg {sum(app)//max(1,len(app))} ms, worst {max(app) if app else '--'} ms")
print(f"{len(results) - fails} of {len(results)} commands OK")
board.wait()
subprocess.run(["curl", "-s", "-X", "DELETE", BASE + ".json"], capture_output=True)

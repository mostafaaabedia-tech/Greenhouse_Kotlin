# The real website in a browser + the real firmware (simulated board) through the real cloud.
# Taps buttons in Manual mode and checks the relays and the screen.
import asyncio, os, subprocess, sys, threading, time
from playwright.async_api import async_playwright

DB = "airqualityproject-e49ae-default-rtdb.firebaseio.com"
KEY = "ghWEBtest" + str(int(time.time()))[-8:] + "zzzzzz"
HERE = os.path.dirname(os.path.abspath(__file__))
PROXY = os.environ.get("HTTPS_PROXY") or os.environ.get("https_proxy")
pins = []
board = subprocess.Popen([HERE + "/cloudsim", KEY, "200"], stdout=subprocess.PIPE, text=True)


def reader():
    for line in board.stdout:
        p = line.split()
        if p and p[0] == "PIN": pins.append((p[1], int(p[2]), int(p[3])))


threading.Thread(target=reader, daemon=True).start()
now = lambda: int(time.time() * 1000)


async def main():
    async with async_playwright() as pw:
        b = await pw.chromium.launch(proxy={"server": PROXY, "bypass": "localhost"})
        pg = await (await b.new_context(viewport={"width": 390, "height": 844})).new_page()
        errs = []
        pg.on("pageerror", lambda e: errs.append(str(e)))
        await pg.goto(f"http://localhost:8765/#g={KEY}@{DB}")
        await pg.wait_for_function("typeof S !== 'undefined' && S.live.s && S.live.s.fw", timeout=60000)
        print("website connected to board, firmware", await pg.evaluate("S.live.s.fw"))
        await pg.click("#nav button[data-v=control]")
        await pg.wait_for_timeout(4000)
        await pg.click("#cModes button[data-m=manual]")
        await pg.wait_for_function("S.live.s.mode === 'manual'", timeout=15000)
        print("manual mode confirmed by the board")
        bad = 0
        for i, (d, v) in enumerate([("pump", 1), ("pump", 0), ("lights", 1), ("pump", 1), ("lights", 0), ("pump", 0), ("fan", 1), ("fan", 0), ("pump", 1), ("pump", 0)]):
            n = len(pins)
            t = now()
            await pg.click(f"#cDev .bub[data-d={d}]")
            # watch the button for 4 s: it must turn to the new state and never flick back
            states = []
            for _ in range(80):
                states.append(await pg.get_attribute(f"#cDev .bub[data-d={d}]", "aria-pressed"))
                await pg.wait_for_timeout(50)
            want = "true" if v else "false"
            first = states.index(want) if want in states else -1
            flick = first >= 0 and any(s != want for s in states[first:])
            relay = next((tt - t for (dd, vv, tt) in pins[n:] if dd == d and vv == v), None)
            ok = first >= 0 and not flick and relay is not None and states[-1] == want
            bad += not ok
            print(f"  {'ok  ' if ok else 'FAIL'} tap {d:6} -> {'ON ' if v else 'OFF'}  relay after {relay if relay is not None else '--':>5} ms   button {'flicked back!' if flick else 'steady'}")
        on_screen = await pg.evaluate("[...document.querySelectorAll('#cDev .bub[data-d]')].map(b => b.dataset.d + '=' + b.getAttribute('aria-pressed')).join(' ')")
        print("screen now:", on_screen)
        await pg.screenshot(path=HERE + "/web_manual.png")
        print("page errors:", errs or "none")
        print("RESULT", "all OK" if bad == 0 else f"{bad} problems")
        await b.close()


asyncio.run(main())
board.kill()
subprocess.run(["curl", "-s", "-X", "DELETE", f"https://{DB}/hbrt/{KEY}.json"], capture_output=True)

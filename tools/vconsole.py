"""Pleos Connect Virtual Console 스크립트 클라이언트 — 시나리오 주입·시연 자동화용.

전제: 에뮬레이터 실행 + `adb -s emulator-5554 forward tcp:8088 tcp:8088`.
Virtual Console(http://127.0.0.1:8088)은 브라우저 세션 heartbeat(`POST /browser/session`, 1초 주기)가
마지막으로 온 뒤 3초 안에만 쓰기를 받는다(아니면 503 emulator_inactive · 페이지 배지 DDS OFFLINE).
첫 heartbeat가 오면 에뮬레이터 안 vehicle_api_dds_emulator가 DDS 브리지를 켠다(logcat `VehicleApiDdsEmu` web-activated).
이 클라이언트가 heartbeat를 대신 보내므로 브라우저 탭이 없어도·백그라운드여도 된다.
엔드포인트는 Virtual Console 페이지 스크립트에서 확인한 내부 API다(공식 문서 미기재 → 버전 따라 바뀔 수 있음).
파라미터는 `GET /vehicle/catalog`의 write_endpoint·control.params가 정본(예: 비상등 feature=hazard).

    python -I tools/vconsole.py state
    python -I tools/vconsole.py gear Drive              # Park | Reverse | Neutral | Drive
    python -I tools/vconsole.py speed 60                # km/h (accelerator/brake 0)
    python -I tools/vconsole.py put vehicle/lights feature=hazard value=true   # 앞 '/' 생략 가능(Git Bash 경로 변환 회피)
    python -I tools/vconsole.py scenario crash          # 주행 60km/h → 급정거 → 비상등 (체인 경고 시연)
    python -I tools/vconsole.py scenario lap normal 0.25   # H1 앱 서킷 한 바퀴 — steady|normal|rough, 서킷 비율(1=6km, 0.25=시연 1.5km)
"""
import json, math, sys, threading, time, urllib.request, uuid

BASE = "http://127.0.0.1:8088"
CLIENT = "cli-" + uuid.uuid4().hex[:8]


def call(method, path, **params):
    qs = "&".join(f"{k}={urllib.parse.quote(str(v))}" for k, v in params.items())
    url = BASE + path + (("&" if "?" in path else "?") + qs if qs else "")
    req = urllib.request.Request(url, method=method, data=b"" if method != "GET" else None)
    try:
        with urllib.request.urlopen(req, timeout=5) as r:
            body = r.read().decode("utf-8", "replace")
            return r.status, body
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8", "replace")


def heartbeat(stop):
    while not stop.is_set():
        call("POST", "/browser/session", client=CLIENT)
        stop.wait(1.0)
    call("POST", "/browser/session", client=CLIENT, closing=1)


def session(fn):
    """heartbeat를 켠 채로 fn을 실행하고, heartbeat를 끊기 전에 상태를 찍는다.
    끊은 뒤엔 3초 안에 emulator_active=false로 돌아가므로 그때 찍은 상태로 고장을 판단하지 말 것."""
    stop = threading.Event()
    t = threading.Thread(target=heartbeat, args=(stop,), daemon=True)
    t.start()
    try:
        for _ in range(20):  # 첫 활성화(DDS 노드 생성)는 실측 ~0.4초
            time.sleep(0.25)
            if json.loads(call("GET", "/vehicle/state")[1]).get("controls", {}).get("emulator_active"):
                break
        else:
            print("! 5초 안에 emulator_active가 안 켜짐 — adb forward·에뮬레이터 부팅 확인, "
                  "logcat -s VehicleApiDdsEmu 에서 web-activated 이벤트 확인")
        result = fn()
        state()
        return result
    finally:
        stop.set()
        t.join(timeout=3)


def show(label, res):
    code, body = res
    print(f"{label:<40} [{code}] {body[:160]}")


def state():
    code, body = call("GET", "/vehicle/state")
    s = json.loads(body)
    c = s.get("controls", {})
    print(json.dumps({"gear": s.get("gear", {}).get("actual"), "speed_kph": c.get("speed_kph"),
                      "source": c.get("source"), "emulator_active": c.get("emulator_active")}, ensure_ascii=False))


def speed(kph, accel=0, brake=0):
    return call("PUT", "/vehicle/controls", speed=kph, accelerator=accel, brake=brake)


def scenario_crash():
    """체인 경고 시연: Drive → 60km/h 주행 → 급정거(브레이크 100) → 0km/h → 비상등 ON."""
    show("gear Drive", call("PUT", "/vehicle/gear", gear="Drive"))
    for v in (20, 40, 60, 60, 60):
        show(f"speed {v}", speed(v, accel=30))
        time.sleep(1)
    for v in (35, 10, 0):
        show(f"brake → {v}", speed(v, brake=100))
        time.sleep(0.4)
    show("hazard on", call("PUT", "/vehicle/lights", feature="hazard", value="true"))


# H1 앱(android/ RaceModel.kt)과 같은 서킷: (시작 m, 제한 km/h) — 비율로 줄여 쓴다
LAP_ZONES = [(0, 60), (1300, 80), (3500, 30), (4200, 60)]
DRIVERS = {  # look: 낮은 제한을 몇 m 앞부터 반영 · offset: 제한 대비 겨냥 · noise: 출렁임 km/h · 가감속 m/s²
    "steady": dict(look=350, offset=-4, noise=1.5, accel=1.0, decel=1.6),
    "normal": dict(look=140, offset=0, noise=4.5, accel=1.6, decel=3.0),
    "rough": dict(look=40, offset=6, noise=7, accel=2.8, decel=5.5),
}


def scenario_lap(style="normal", scale=0.25, dt=0.25):
    """기어 D → 운전자 유형대로 서킷 한 바퀴(거리는 여기서 적분) → 정차 → P. 앱은 실제 AAOS 속도로 판정한다."""
    d = DRIVERS[style]
    zones = [(x * scale, lim) for x, lim in LAP_ZONES]
    lap = 6000 * scale
    limit = lambda x: [lim for z, lim in zones if x >= z][-1]
    show("gear Drive", call("PUT", "/vehicle/gear", gear="Drive"))
    x = v = t = 0.0
    last = time.time()
    while x < lap or v > 0.1:
        if x < lap:
            base = min([limit(x)] + [lim for z, lim in zones if x < z <= x + d["look"]])  # 예측 거리는 물리 거리(서킷 비율 무관)
            want = (base + d["offset"] + d["noise"] * (0.6 * math.sin(t * 0.35) + 0.4 * math.sin(t * 0.13 + 1))) / 3.6
        else:
            want = 0.0
        a = max(-d["decel"], min(d["accel"], 0.6 * (want - v)))
        v = max(0.0, v + a * dt); x += v * dt; t += dt
        code, body = call("PUT", "/vehicle/controls", speed=round(v * 3.6), accelerator=round(max(0, a) * 30), brake=round(max(0, -a) * 18))
        if code != 200:  # 속도는 정수 km/h만 받는다(소수면 400 invalid_speed)
            print(f"  ! controls [{code}] {body[:80]}")
        if int(t / dt) % 20 == 0:
            print(f"  t={t:5.1f}s x={x:6.0f}/{lap:.0f}m v={v * 3.6:5.1f}km/h limit={limit(min(x, lap - 1))}")
        last += dt
        time.sleep(max(0.0, last - time.time()))
    show("stop", call("PUT", "/vehicle/controls", speed=0, accelerator=0, brake=0))
    time.sleep(1.0)
    show("gear Park", call("PUT", "/vehicle/gear", gear="Park"))


def main():
    sys.stdout.reconfigure(encoding="utf-8")
    a = sys.argv[1:]
    if not a or a[0] == "state":
        state()
    elif a[0] == "gear":
        session(lambda: show(f"gear {a[1]}", call("PUT", "/vehicle/gear", gear=a[1])))
    elif a[0] == "speed":
        session(lambda: show(f"speed {a[1]}", speed(a[1])))
    elif a[0] == "put":
        kv = dict(x.split("=", 1) for x in a[2:])
        path = "/" + a[1].lstrip("/")
        session(lambda: show(f"PUT {path}", call("PUT", path, **kv)))
    elif a[0] == "scenario" and a[1:] == ["crash"]:
        session(scenario_crash)
    elif a[0] == "scenario" and a[1:2] == ["lap"]:
        style = a[2] if len(a) > 2 else "normal"
        scale = float(a[3]) if len(a) > 3 else 0.25
        session(lambda: scenario_lap(style, scale))
    else:
        print(__doc__)


if __name__ == "__main__":
    import urllib.parse  # noqa: E402 — call()에서 사용
    main()

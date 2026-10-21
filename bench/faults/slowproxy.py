#!/usr/bin/env python3
"""Userspace network-fault proxy (for hosts where `tc netem` is not available): forwards TCP from LISTEN to TARGET and
can add latency, limit bandwidth and cut connections.

  slowproxy.py --listen 8091 --target localhost:8090 [--delay-ms 50] [--rate-kbps 2000] [--cut-after-bytes N] [--cut-direction c2s|s2c]

--cut-after-bytes closes BOTH sides of a connection (RST-like) after N bytes have gone in --cut-direction (default c2s).
"""
import argparse, asyncio, struct, socket


async def pipe(r, w, delay, rate, cut_after, state, direction, my_dir):
    try:
        while True:
            data = await r.read(16384)
            if not data:
                break
            if delay: await asyncio.sleep(delay)
            if rate: await asyncio.sleep(len(data) / rate)
            if cut_after is not None and direction == my_dir:
                state["n"] += len(data)
                if state["n"] >= cut_after:
                    sock = w.get_extra_info("socket")
                    sock.setsockopt(socket.SOL_SOCKET, socket.SO_LINGER, struct.pack("ii", 1, 0))  # RST on close
                    raise ConnectionResetError("cut")
            w.write(data); await w.drain()
    except Exception:
        pass
    finally:
        try: w.close()
        except Exception: pass


async def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--listen", type=int, required=True); ap.add_argument("--target", required=True)
    ap.add_argument("--delay-ms", type=float, default=0); ap.add_argument("--rate-kbps", type=float, default=0)
    ap.add_argument("--cut-after-bytes", type=int); ap.add_argument("--cut-direction", default="c2s")
    a = ap.parse_args()
    th, tp = a.target.rsplit(":", 1)

    async def handle(cr, cw):
        try:
            sr, sw = await asyncio.open_connection(th, int(tp))
        except OSError:
            cw.close(); return
        st = {"n": 0}
        rate = a.rate_kbps * 1024 if a.rate_kbps else 0
        await asyncio.gather(pipe(cr, sw, a.delay_ms / 1000, rate, a.cut_after_bytes, st, a.cut_direction, "c2s"),
                             pipe(sr, cw, a.delay_ms / 1000, rate, a.cut_after_bytes, st, a.cut_direction, "s2c"))
    srv = await asyncio.start_server(handle, "127.0.0.1", a.listen)
    print(f"proxy 127.0.0.1:{a.listen} -> {a.target}", flush=True)
    async with srv:
        await srv.serve_forever()

asyncio.run(main())

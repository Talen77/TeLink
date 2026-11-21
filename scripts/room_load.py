"""把长连接稳住后再发消息。每个连接单独一间房，避免一条消息被复制到全部连接。

示例：
  python scripts/room_load.py --connections 1000 --messages 3000000
"""
import argparse
import base64
import json
import os
import socket
import struct
import threading
import time
import uuid

PORTS = [8082, 8083, 8084, 8085]


class Socket:
    def __init__(self, port):
        last = None
        for _ in range(8):
            try:
                sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
                sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
                sock.settimeout(20)
                sock.connect(("127.0.0.1", port))
                self.sock = sock
                break
            except OSError as exc:
                last = exc
                time.sleep(0.05)
        else:
            raise last
        key = base64.b64encode(os.urandom(16)).decode()
        self.sock.sendall(
            f"GET /ws HTTP/1.1\r\nHost: 127.0.0.1:{port}\r\nUpgrade: websocket\r\n"
            f"Connection: Upgrade\r\nSec-WebSocket-Key: {key}\r\nSec-WebSocket-Version: 13\r\n\r\n".encode()
        )
        data = b""
        while b"\r\n\r\n" not in data:
            chunk = self.sock.recv(4096)
            if not chunk:
                raise ConnectionError("handshake closed")
            data += chunk
        if b" 101 " not in data.split(b"\r\n", 1)[0]:
            raise ConnectionError("upgrade failed")
        self.buf = data.split(b"\r\n\r\n", 1)[1]
        self.sock.settimeout(None)
        self.sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)

    def send(self, text):
        payload = text.encode()
        mask = os.urandom(4)
        header = bytearray([0x81, 0x80 | len(payload)])
        if len(payload) >= 126:
            header = bytearray([0x81, 0x80 | 126]) + struct.pack("!H", len(payload))
        self.sock.sendall(bytes(header) + mask + bytes(b ^ mask[i % 4] for i, b in enumerate(payload)))

    def recv(self):
        def take(n):
            while len(self.buf) < n:
                chunk = self.sock.recv(max(4096, n - len(self.buf)))
                if not chunk:
                    raise ConnectionError("closed")
                self.buf += chunk
            out, self.buf = self.buf[:n], self.buf[n:]
            return out

        while True:
            b0, b1 = take(2)
            opcode, ln = b0 & 15, b1 & 127
            if ln == 126:
                ln = struct.unpack("!H", take(2))[0]
            elif ln == 127:
                ln = struct.unpack("!Q", take(8))[0]
            data = take(ln)
            if opcode == 8:
                raise ConnectionError("close")
            if opcode == 1:
                return data.decode()


def client(index, count, stats, online, send_gate):
    port = PORTS[index % len(PORTS)]
    sock = Socket(port)
    room = 940000 + index
    pending = []
    lock = threading.Lock()
    done = threading.Event()

    def read():
        try:
            while not done.is_set():
                kind = json.loads(sock.recv()).get("type")
                if kind == "MESSAGE_ACCEPTED":
                    with lock:
                        stats["accepted"] += 1
                        if pending:
                            pending.pop()
        except Exception:
            if not done.is_set():
                stats["readErrors"] += 1
        finally:
            done.set()

    threading.Thread(target=read, daemon=True).start()
    sock.send(json.dumps({"type": "CREATE_ROOM", "roomId": room, "userId": 40000 + index}))
    time.sleep(0.2)
    with lock:
        online[0] += 1
    if not send_gate.wait(90):
        return
    for n in range(count):
        while True:
            with lock:
                if done.is_set():
                    return
                if len(pending) < 32:
                    pending.append(1)
                    break
            time.sleep(0.002)
        sock.send(json.dumps({
            "type": "CHAT_MESSAGE",
            "messageId": str(uuid.uuid4()),
            "userId": 40000 + index,
            "roomId": room,
            "content": f"m{index}-{n}",
            "sentAt": int(time.time() * 1000),
        }))
        with lock:
            stats["sent"] += 1
    time.sleep(3)
    done.set()
    sock.sock.close()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--connections", type=int, default=1000)
    parser.add_argument("--messages", type=int, default=3_000_000)
    args = parser.parse_args()
    per = args.messages // args.connections
    extra = args.messages % args.connections
    stats = {"sent": 0, "accepted": 0, "readErrors": 0, "connectErrors": 0}
    online = [0]
    send_gate = threading.Event()
    threads = []
    started = time.time()
    for i in range(args.connections):
        count = per + (1 if i < extra else 0)

        def run(index=i, total=count):
            try:
                client(index, total, stats, online, send_gate)
            except Exception as exc:
                stats["connectErrors"] += 1
                if "firstError" not in stats:
                    stats["firstError"] = f"{type(exc).__name__}: {exc}"

        thread = threading.Thread(target=run, daemon=True)
        thread.start()
        threads.append(thread)
        time.sleep(0.02)
    last = 0
    deadline = time.time() + 60
    while time.time() < deadline and online[0] + stats["connectErrors"] < args.connections:
        time.sleep(1)
    print(json.dumps({"phase": "connected", "online": online[0], "connectErrors": stats["connectErrors"], "firstError": stats.get("firstError", "")}, ensure_ascii=False), flush=True)
    send_gate.set()
    while any(thread.is_alive() for thread in threads):
        time.sleep(5)
        now = stats["accepted"]
        print(json.dumps({
            "online": online[0],
            "connectErrors": stats["connectErrors"],
            "firstError": stats.get("firstError", ""),
            "sent": stats["sent"],
            "accepted": now,
            "acceptedPerSecond": round((now - last) / 5, 1),
        }, ensure_ascii=False), flush=True)
        last = now
    elapsed = max(time.time() - started, 0.001)
    print(json.dumps({
        "done": True,
        "connections": args.connections,
        "online": online[0],
        "connectErrors": stats["connectErrors"],
        "sent": stats["sent"],
        "accepted": stats["accepted"],
        "seconds": round(elapsed, 1),
    }, ensure_ascii=False), flush=True)


if __name__ == "__main__":
    main()

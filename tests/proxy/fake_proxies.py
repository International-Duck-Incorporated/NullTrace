import socket, threading, sys, json

def http_connect_proxy(port_holder):
    srv = socket.socket(); srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind(("127.0.0.1", 0)); srv.listen(5); port_holder.append(srv.getsockname()[1])
    def serve():
        while True:
            conn, _ = srv.accept()
            try:
                data = conn.recv(4096).decode("latin-1")
                if data.startswith("CONNECT "):
                    conn.sendall(b"HTTP/1.1 200 Connection established\r\n\r\n")
            except Exception:
                pass
            finally:
                conn.close()
    threading.Thread(target=serve, daemon=True).start()

def socks5_proxy(port_holder):
    srv = socket.socket(); srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind(("127.0.0.1", 0)); srv.listen(5); port_holder.append(srv.getsockname()[1])
    def serve():
        while True:
            conn, _ = srv.accept()
            try:
                greeting = conn.recv(3)
                if greeting[:2] == b"\x05\x01":
                    conn.sendall(b"\x05\x00")   # no-auth accepted
            except Exception:
                pass
            finally:
                conn.close()
    threading.Thread(target=serve, daemon=True).start()

def closed_port(holder):
    s = socket.socket(); s.bind(("127.0.0.1", 0)); holder.append(s.getsockname()[1]); s.close()

http_p, socks_p, dead_p = [], [], []
http_connect_proxy(http_p); socks5_proxy(socks_p); closed_port(dead_p)
print(json.dumps({"http": http_p[0], "socks": socks_p[0], "dead": dead_p[0]}), flush=True)
threading.Event().wait()

#!/usr/bin/env python3
"""Простой HTTP-приёмник для TV Unlocker.

Принимает файлы, которые приложение отправляет кнопкой [U]
(и автозагрузкой протоколов), и сохраняет их в текущий каталог.

Запуск:
    python listener.py            # слушает 0.0.0.0:8888

Порт должен быть открыт в брандмауэре:
    netsh advfirewall firewall add rule name="TV Unlocker 8888" ^
        dir=in action=allow protocol=TCP localport=8888
"""

import datetime
import http.server
import os
import socketserver
import urllib.parse

OUT_DIR = os.getcwd()
PORT = 8888


class Handler(http.server.BaseHTTPRequestHandler):
    def do_POST(self):
        try:
            name = "upload.bin"
            query = urllib.parse.parse_qs(urllib.parse.urlparse(self.path).query)
            if "name" in query:
                name = os.path.basename(urllib.parse.unquote(query["name"][0])) or name

            length = int(self.headers.get("Content-Length", 0))
            data = self.rfile.read(length)

            stamp = datetime.datetime.now().strftime("%d%m_%H%M%S")
            path = os.path.join(OUT_DIR, f"{stamp}_{name}")
            with open(path, "wb") as f:
                f.write(data)

            print(f"[{datetime.datetime.now():%H:%M:%S}] {name}: "
                  f"{len(data)} байт от {self.client_address[0]} -> {path}")

            self.send_response(200)
            self.send_header("Content-Type", "text/plain; charset=utf-8")
            self.end_headers()
            self.wfile.write(f"OK saved {path} ({len(data)} bytes)".encode())
        except Exception as e:  # noqa: BLE001
            self.send_response(500)
            self.end_headers()
            self.wfile.write(f"ERR {e}".encode())

    def log_message(self, *args):  # глушим стандартный шумный лог
        pass


if __name__ == "__main__":
    socketserver.TCPServer.allow_reuse_address = True
    with socketserver.TCPServer(("0.0.0.0", PORT), Handler) as srv:
        print(f"listener on 0.0.0.0:{PORT}, сохраняю в {OUT_DIR}")
        try:
            srv.serve_forever()
        except KeyboardInterrupt:
            pass

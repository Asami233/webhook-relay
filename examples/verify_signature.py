"""校验 Relay 的 X-Relay-Signature（仅依赖标准库）。

签名格式：t=<unix 秒>,v1=<hex(HMAC-SHA256(secret, "<t>.<原始请求体>"))>

要点：
- 用收到的**原始请求体字节**校验，不要先解析 JSON 再序列化。
- 拒绝时间戳超出容忍窗口（默认 300 秒）的请求，防重放。
- 用 hmac.compare_digest 常量时间比较。
- 投递是“至少一次”：校验通过后再按 X-Relay-Event-Id 去重。

自检：python verify_signature.py --self-test
"""
import hashlib
import hmac
import sys
import time

TOLERANCE_SECONDS = 300


def verify(secret: str, header: str, body: bytes, now: float | None = None,
           tolerance: int = TOLERANCE_SECONDS) -> bool:
    timestamp = None
    signatures = []
    for part in header.split(","):
        key, sep, value = part.strip().partition("=")
        if not sep:
            continue
        if key == "t":
            try:
                timestamp = int(value)
            except ValueError:
                return False
        elif key == "v1":
            signatures.append(value)
    if timestamp is None or not signatures:
        return False
    if abs((time.time() if now is None else now) - timestamp) > tolerance:
        return False
    expected = hmac.new(secret.encode(), f"{timestamp}.".encode() + body, hashlib.sha256).hexdigest()
    return any(hmac.compare_digest(expected, s) for s in signatures)


# Flask 用法示例：
#
#   @app.post("/webhooks/relay")
#   def relay_webhook():
#       if not verify(SECRET, request.headers.get("X-Relay-Signature", ""), request.get_data()):
#           abort(400)
#       event_id = request.headers["X-Relay-Event-Id"]
#       if already_processed(event_id):   # 至少一次投递：按事件 ID 去重
#           return "", 200
#       handle(request.get_json())
#       return "", 200


if __name__ == "__main__" and "--self-test" in sys.argv:
    secret = "whsec_test"
    body = b'{"id":1,"type":"order.created","data":{"a":1}}'
    t = 1760000000
    digest = hmac.new(secret.encode(), f"{t}.".encode() + body, hashlib.sha256).hexdigest()
    header = f"t={t},v1={digest}"
    assert verify(secret, header, body, now=t)
    assert not verify(secret, header, body + b" ", now=t)
    assert not verify(secret, header, body, now=t + 301)
    print(digest)

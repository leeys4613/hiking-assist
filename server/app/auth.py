"""단일 사용자 정적 토큰 인증 (VPN 과 이중 보호). 헤더 또는 쿠키로 받는다."""
import hmac

from fastapi import HTTPException, Request

from . import config


def _extract(request: Request) -> str:
    h = request.headers.get("authorization", "")
    if h.lower().startswith("bearer "):
        return h[7:].strip()
    return request.headers.get("x-hiking-token") or request.cookies.get(config.COOKIE_NAME, "")


def token_ok(candidate: str) -> bool:
    # 서버에 토큰이 설정돼 있지 않으면 모든 요청을 거부한다(실수로 무인증이 되는 것 방지)
    return bool(config.TOKEN) and hmac.compare_digest(candidate.encode(), config.TOKEN.encode())


def require_token(request: Request) -> None:
    if not token_ok(_extract(request)):
        raise HTTPException(status_code=401, detail="unauthorized")

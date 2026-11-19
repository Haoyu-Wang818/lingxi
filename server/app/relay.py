"""手机与手表之间的 WebSocket 中继。

Wear Data Layer 依赖 Google Play 服务，国行手机通常没有；模拟器里配对也要登录 Google 账号。
中继让两端都连到网关，由网关在同一配对码下转发消息，消息内容（JSON 文本）原样透传。
模拟器访问开发机上的网关地址为 ws://10.0.2.2:8000/v1/relay/{pair_code}/{role}。
"""

import asyncio
from typing import Literal

from fastapi import WebSocket

Role = Literal["phone", "watch"]
PEER: dict[str, Role] = {"phone": "watch", "watch": "phone"}

MAX_MESSAGE_CHARS = 64_000


class RelayHub:
    """按配对码登记两端连接并互相转发。同一角色重复连接时，新连接替换旧连接。"""

    def __init__(self) -> None:
        self._rooms: dict[str, dict[str, WebSocket]] = {}
        self._lock = asyncio.Lock()

    async def join(self, pair_code: str, role: Role, ws: WebSocket) -> None:
        async with self._lock:
            room = self._rooms.setdefault(pair_code, {})
            old = room.get(role)
            room[role] = ws
        if old is not None:
            await old.close(code=4000, reason="replaced")

    async def leave(self, pair_code: str, role: Role, ws: WebSocket) -> None:
        async with self._lock:
            room = self._rooms.get(pair_code, {})
            if room.get(role) is ws:
                del room[role]
            if not room:
                self._rooms.pop(pair_code, None)

    async def forward(self, pair_code: str, sender: Role, text: str) -> bool:
        """转发给对端，对端不在线时返回 False，由发送方决定是否重试。"""
        peer = self._rooms.get(pair_code, {}).get(PEER[sender])
        if peer is None:
            return False
        await peer.send_text(text)
        return True


hub = RelayHub()

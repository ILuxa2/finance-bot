"""In-memory registry of recent Telegram message identifiers."""

from collections import defaultdict


MAX_RECENT_MESSAGES = 100


class MessageHistory:
    """Keep at most the latest message ids for each Telegram user."""

    def __init__(self) -> None:
        self.message_ids: dict[int, list[int]] = defaultdict(list)
        self.chat_users: dict[int, int] = {}

    def register_chat(self, chat_id: int, user_id: int) -> None:
        self.chat_users[chat_id] = user_id

    def add(self, user_id: int, message_id: int) -> None:
        messages = self.message_ids[user_id]
        if message_id not in messages:
            messages.append(message_id)
        del messages[:-MAX_RECENT_MESSAGES]

    def get(self, user_id: int) -> list[int]:
        return list(self.message_ids.get(user_id, []))

    def clear(self, user_id: int) -> None:
        self.message_ids.pop(user_id, None)

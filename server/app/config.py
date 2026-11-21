"""网关配置，从环境变量或 .env 文件读取。"""

from functools import lru_cache

from pydantic import AliasChoices, Field
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", extra="ignore", populate_by_name=True)

    llm_base_url: str = "https://dashscope.aliyuncs.com/compatible-mode/v1"
    # 兼容阿里云百炼文档中的标准变量名 DASHSCOPE_API_KEY。
    llm_api_key: str = Field(default="", validation_alias=AliasChoices("LLM_API_KEY", "DASHSCOPE_API_KEY"))
    llm_model: str = "qwen-plus"
    # 整理流水线（笔记、知识点、出卡）拆成多次短调用，用速度快的模型满足 15 秒目标（NFR-PERF-01）。
    llm_model_fast: str = "qwen-flash"
    llm_timeout_seconds: float = 30.0
    gateway_token: str = ""
    # 每设备每分钟 AI 请求上限，超出返回 429（04-api.md 1.1 节，FR-GW-05）。
    rate_limit_per_minute: int = 20


@lru_cache
def get_settings() -> Settings:
    return Settings()

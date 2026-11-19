"""网关配置，从环境变量或 .env 文件读取。"""

from functools import lru_cache

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", extra="ignore")

    llm_base_url: str = "https://dashscope.aliyuncs.com/compatible-mode/v1"
    llm_api_key: str = ""
    llm_model: str = "qwen-plus"
    llm_timeout_seconds: float = 30.0
    gateway_token: str = ""


@lru_cache
def get_settings() -> Settings:
    return Settings()

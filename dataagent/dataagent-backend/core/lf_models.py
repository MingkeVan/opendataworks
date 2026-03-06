from __future__ import annotations

"""
LF(JSON DSL) 模型定义
"""

from typing import Any, List, Literal, Optional

from pydantic import BaseModel, Field


class LfSelectItem(BaseModel):
    field: str
    agg: Optional[str] = None
    alias: Optional[str] = None


class LfFilterItem(BaseModel):
    field: str
    op: Literal["=", "!=", ">", ">=", "<", "<=", "in", "not_in", "like", "between"]
    value: Any


class LfOrderItem(BaseModel):
    field: str
    direction: Literal["asc", "desc"] = "asc"


class LogicForm(BaseModel):
    action: Literal["query", "explain_only"] = "query"
    database: Optional[str] = None
    table: Optional[str] = None
    select: List[LfSelectItem] = Field(default_factory=list)
    filters: List[LfFilterItem] = Field(default_factory=list)
    group_by: List[str] = Field(default_factory=list)
    order_by: List[LfOrderItem] = Field(default_factory=list)
    limit: Optional[int] = None
    notes: Optional[str] = None

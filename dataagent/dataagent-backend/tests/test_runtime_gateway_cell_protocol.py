import asyncio
import io
import pytest
from core.agent_runtime.contracts import CellProtocolFrame
from runtime_gateway.cell_protocol import make_frame, read_frame, write_frame


@pytest.mark.asyncio
async def test_make_and_serialize_frame():
    frame = make_frame(
        cell_id="cell-1",
        run_id="run-1",
        task_attempt_id="att-1",
        frame_type="run.start",
        payload={"foo": "bar"},
    )
    assert frame.cell_id == "cell-1"
    assert frame.run_id == "run-1"
    assert frame.type == "run.start"
    assert frame.payload == {"foo": "bar"}

    # Test reading frame from StreamReader
    reader = asyncio.StreamReader()
    raw_line = frame.model_dump_json() + "\n"
    reader.feed_data(raw_line.encode("utf-8"))
    reader.feed_eof()

    parsed = await read_frame(reader)
    assert parsed is not None
    assert parsed.cell_id == "cell-1"
    assert parsed.type == "run.start"
    assert parsed.payload == {"foo": "bar"}


@pytest.mark.asyncio
async def test_read_frame_eof_and_invalid():
    reader = asyncio.StreamReader()
    reader.feed_eof()
    assert await read_frame(reader) is None

    reader_invalid = asyncio.StreamReader()
    reader_invalid.feed_data(b"not valid json\n")
    reader_invalid.feed_eof()

    with pytest.raises(ValueError, match="Invalid frame format"):
        await read_frame(reader_invalid)

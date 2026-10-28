from ..utils.logger import get_formatted_logger

logger = get_formatted_logger()


async def stream_output(
    type, content, output, websocket=None, output_log=True, metadata=None
):
    """
    Streams output to the websocket
    Args:
        type:
        content:
        output:

    Returns:
        None
    """
    if (not websocket or output_log) and type != "images":
        try:
            logger.info(f"{output}")
        except UnicodeEncodeError:
            # Option 1: Replace problematic characters with a placeholder
            logger.error(output.encode(
                'cp1252', errors='replace').decode('cp1252'))

    if websocket:
        await websocket.send_json(
            {"type": type, "content": content,
                "output": output, "metadata": metadata}
        )

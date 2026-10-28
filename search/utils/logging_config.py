import logging


def get_json_handler():
    return getattr(logging.getLogger('research'), 'json_handler', None)

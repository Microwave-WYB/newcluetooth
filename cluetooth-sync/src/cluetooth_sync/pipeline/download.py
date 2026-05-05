from cluetooth_sync.pipeline.storage import StorageClient


def read_blob_bytes(
    storage_client: StorageClient,
    blob_uri: str,
) -> bytes:
    return storage_client.read_blob_bytes(blob_uri)

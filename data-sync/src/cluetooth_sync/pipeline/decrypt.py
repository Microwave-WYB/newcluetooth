from nacl.public import PrivateKey, SealedBox


def decrypt_blob_bytes(ciphertext: bytes, private_key: bytes) -> bytes:
    return SealedBox(PrivateKey(private_key)).decrypt(ciphertext)

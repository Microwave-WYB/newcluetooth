---
qmd:
  metadata:
    title: "ResMed AirMini candidate"
    date: "2026-10-14"
    tags: [hospital, respiratory-therapy]
    confidence: medium
---

# ResMed AirMini candidate

## Identification and hospital relevance

The sanitized name shape `ResMed <six digits>` matches ResMed's documented
AirMini discovery naming and is accepted by the inspected AirMini client.
This is a relatively strong **AirMini candidate**, not authenticated hardware
identification: the client regex is broader than six digits and does not verify
a model. AirMini is a respiratory-therapy product, but this lead does not
establish clinical use, a hospital installation, or ownership.

## Static Android sample provenance

- Exact third-party download source, **not official ResMed distribution**:
  <https://d.apkpure.net/b/APK/com.resmed.airmini?version=latest>.
- Package `com.resmed.airmini`, version **1.9.0.0.342**.
- XAPK/archive SHA256:
  `4242106d891a1e4bd059b46617de02dec5528b798d97a578e79ae8fdbfd630f5`.
- Base APK SHA256:
  `4e2cb9ce8db5100035ae3bc371698a14409c603dcddbc69b30c4ea03bf431fb4`.
- Static unpacking/JADX only; no installation, execution, login, pairing, or
  connection. JADX reported **19 partial decompilation errors**; the cited paths
  were readable. These conclusions are limited to this sample.
  The `latest` URL is mutable; hashes identify the historical sample.

## Discovery: a client name rule, not a radio fingerprint

Code paths are relative to the external research run and were inspected at the
quoted lines. `resmed-decompiled/sources/p047i4/a.java:15-22`:

```java
public static final Pattern f6396b = Pattern.compile("resmed\\s*\\d+");

public static boolean a(String str) {
    if (str == null) {
        return false;
    }
    return f6396b.matcher(str.toLowerCase()).matches();
}
```

This accepts spaces and any nonempty digit run after lowercasing.
`resmed-decompiled/sources/D3/a.java:76-80` applies it to discovered names:

```java
String name = bluetoothDevice2.getName();
if (name != null && !name.trim().isEmpty() && !fVar4.f438b.containsKey(name)) {
    com.resmed.mon.utils.log.b.a(AppFileLog$LogType.Bluetooth, "ACTION_FOUND Bluetooth device found: ".concat(b.b(bluetoothDevice2)));
    if (p047i4.a.a(name)) {
        fVar4.f438b.put(name, bluetoothDevice2);
```

`resmed-decompiled/sources/com/resmed/mon/ipc/rmon/handler/C0336c.java:222`
starts Android system discovery:

```java
zStartDiscovery = ((BluetoothAdapter) lVar2.f5266l).startDiscovery();
```

## Inspected transport: Classic SPP, not a demonstrated BLE GATT path

`resmed-decompiled/sources/A3/c.java:15`:

```java
public static final UUID f58d = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
```

The same file, line 63:

```java
O0.b bVar2 = new O0.b(1, this.f60b.createRfcommSocketToServiceRecord(uuid));
```

This is RFCOMM with the Serial Port Profile UUID. Socket streams are obtained in
`resmed-decompiled/sources/F3/e.java:33-49` and read at lines 73–86.
WiGLE's BLE/BT labels do not demonstrate the transport of a later session;
dual-mode hardware or source/type aggregation remain possible.

## Application data: FIG-wrapped JSON-RPC, not plain JSON on air

`resmed-decompiled/sources/com/resmed/mon/bluetooth/rpc/request/RpcRequest.java:23-27`:

```java
@b("jsonrpc")
private String jsonrpc = "2.0";

@b("id")
private int id = -1;
```

The `method` field is defined at lines 18–19.
`resmed-decompiled/sources/C3/c.java:31` sends JSON through the FIG wrapper:

```java
this.f255n = d.a().encode(rpcRequest.toJson());
```

`resmed-decompiled/sources/C3/d.java:21-27` returns a `FigWrapper`,
selecting encrypted or unencrypted instances according to client key state.
`resmed-decompiled/sources/com/resmed/mon/fig/FigWrapper.java:117-119`:

```java
public byte[] encode(String str) {
    return nativeEncode(str);
}
```

The same file loads `figlib` at lines 54–55 and declares native encode/decode
at lines 169–175. Thus Java JSON-RPC objects are **not evidence of bare,
unencrypted JSON lines over the radio**. No over-the-air frame header,
per-message encryption mode, or CRC layout was verified.

## Pairing and supported client functions

ResMed's official pairing guide requires the device's four-digit authentication
code or QR code. Client request fields independently support pairing/session
key handling:

- `resmed-decompiled/sources/com/resmed/mon/bluetooth/rpc/request/GetPairKeyRpcRequest.java:16-17`:
  `@b("passKey")` / `private final String passKey;`
- `resmed-decompiled/sources/com/resmed/mon/bluetooth/rpc/request/GetSessionKeyRpcRequest.java:16-17`:
  `@b("masterPairKey")` / `private final String masterPairKey;`

The FIG wrapper implements AES-CBC callbacks at
`resmed-decompiled/sources/com/resmed/mon/fig/FigWrapper.java:109-132`
and encrypted-instance initialization at lines 189–200. This establishes
available client machinery, not that every message is encrypted.

The command enum at
`resmed-decompiled/sources/com/resmed/mon/bluetooth/rpc/RpcCommand.java:25-48`
includes settings/log retrieval, streaming, therapy start/stop, and firmware
upgrade methods. These are **static client capabilities**, not actions performed,
an authorization bypass, a demonstrated vulnerability, or verified behavior of
the research candidate. No raw ADV/GATT or actual therapy data was available.

## Sources

- [ResMed: How do I pair AirMini to a smartphone? — original manufacturer guide](https://ap.resmed.com/knowledge/how-do-i-pair-airmini-to-a-smartphone).
  Naming and user pairing requirements, not the APK download source.
  No dated Wayback capture was verified for this URL.
- Private research: `resmed_airmini_protocol_notes.md` and
  `today_all_records_2026-10-14.md`; research record date 2026-10-14.
  Third-party sample URL and hashes are recorded above; APKs and decompiled
  source trees remain outside Git.

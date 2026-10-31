import polars as pl


SAMPLES = [
    {
        "label": "duplicate_manufacturer_data",
        "raw": "0201021bff7500021862a1ef88b77116fda9718ed8cc074742a3da5d66a6fa09ff7500f9de6daffc6400000000000000000000000000000000000000",
    },
    {
        "label": "service_uuid_list_16bit",
        "raw": "02010605030d180f18",
    },
]
MAX_STRUCTS = 8
BLUETOOTH_BASE_UUID_SUFFIX = "-0000-1000-8000-00805f9b34fb"
SERVICE_UUID_TYPES = {
    0x02: 2,
    0x03: 2,
    0x04: 4,
    0x05: 4,
    0x06: 16,
    0x07: 16,
}


def add_ad_columns(
    lf: pl.LazyFrame, raw_col: str = "raw", max_structs: int = MAX_STRUCTS
) -> tuple[pl.LazyFrame, list[str]]:
    raw_bytes = pl.col(raw_col).str.decode("hex")
    size = raw_bytes.bin.size()
    struct_cols: list[str] = []

    for i in range(max_structs):
        start = (
            pl.lit(0, dtype=pl.UInt32)
            if i == 0
            else (
                pl.col(f"_ad_{i - 1}_start")
                + pl.col(f"_ad_{i - 1}_len").fill_null(0)
                + 1
            ).cast(pl.UInt32)
        )
        valid = start < size
        length = (
            pl.when(valid)
            .then(raw_bytes.bin.get(start).cast(pl.UInt32))
            .otherwise(None)
        )
        ad_type = (
            pl.when((start + 1) < size)
            .then(raw_bytes.bin.get(start + 1).cast(pl.UInt32))
            .otherwise(None)
        )
        data_len = (
            pl.when(length.is_not_null() & (length > 0))
            .then(length - 1)
            .otherwise(None)
        )
        data = (
            pl.when(
                valid & data_len.is_not_null() & ((start + 1 + data_len) < (size + 1))
            )
            .then(raw_bytes.bin.slice(start + 2, data_len))
            .otherwise(None)
        )
        struct_col = f"_ad_{i}_struct"

        lf = lf.with_columns(
            start.alias(f"_ad_{i}_start"),
            length.alias(f"_ad_{i}_len"),
            pl.when(valid & length.is_not_null() & (length > 0) & ad_type.is_not_null())
            .then(
                pl.struct(
                    start.alias("start"),
                    length.alias("len"),
                    ad_type.alias("ad_type"),
                    data.bin.encode("hex").alias("data"),
                )
            )
            .otherwise(None)
            .alias(struct_col),
        )
        struct_cols.append(struct_col)

    return lf, struct_cols


def chunk_hex_expr(hex_expr: pl.Expr, width_bytes: int) -> pl.Expr:
    width = width_bytes * 2
    return (
        pl.int_ranges(
            0,
            hex_expr.str.len_chars(),
            step=width,
            eager=False,
        )
        .list.eval(hex_expr.str.slice(pl.element(), width), parallel=False)
        .list.drop_nulls()
    )


def normalize_uuid_hex_expr(chunk_expr: pl.Expr, width_bytes: int) -> pl.Expr:
    if width_bytes == 2:
        return (
            chunk_expr.str.slice(2, 2) + chunk_expr.str.slice(0, 2)
        ).str.to_lowercase().str.zfill(8) + pl.lit(BLUETOOTH_BASE_UUID_SUFFIX)
    if width_bytes == 4:
        return (
            chunk_expr.str.slice(6, 2)
            + chunk_expr.str.slice(4, 2)
            + chunk_expr.str.slice(2, 2)
            + chunk_expr.str.slice(0, 2)
        ).str.to_lowercase() + pl.lit(BLUETOOTH_BASE_UUID_SUFFIX)
    if width_bytes == 16:
        reordered = (
            chunk_expr.str.slice(14, 2)
            + chunk_expr.str.slice(12, 2)
            + chunk_expr.str.slice(10, 2)
            + chunk_expr.str.slice(8, 2)
            + chunk_expr.str.slice(6, 2)
            + chunk_expr.str.slice(4, 2)
            + chunk_expr.str.slice(2, 2)
            + chunk_expr.str.slice(0, 2)
            + chunk_expr.str.slice(30, 2)
            + chunk_expr.str.slice(28, 2)
            + chunk_expr.str.slice(26, 2)
            + chunk_expr.str.slice(24, 2)
            + chunk_expr.str.slice(22, 2)
            + chunk_expr.str.slice(20, 2)
            + chunk_expr.str.slice(18, 2)
            + chunk_expr.str.slice(16, 2)
        ).str.to_lowercase()
        return (
            reordered.str.slice(0, 8)
            + pl.lit("-")
            + reordered.str.slice(8, 4)
            + pl.lit("-")
            + reordered.str.slice(12, 4)
            + pl.lit("-")
            + reordered.str.slice(16, 4)
            + pl.lit("-")
            + reordered.str.slice(20, 12)
        )
    raise ValueError(f"unsupported uuid width: {width_bytes}")


def add_service_uuid_columns(lf: pl.LazyFrame) -> pl.LazyFrame:
    exploded = (
        lf.select("label", "raw", "structs")
        .explode("structs")
        .with_columns(
            pl.col("structs").struct.field("ad_type").alias("ad_type"),
            pl.col("structs").struct.field("data").alias("data"),
        )
    )

    uuid_frames: list[pl.LazyFrame] = []
    for ad_type, width_bytes in SERVICE_UUID_TYPES.items():
        chunks = chunk_hex_expr(pl.col("data"), width_bytes)
        uuid_frames.append(
            exploded.filter(pl.col("ad_type") == ad_type)
            .with_columns(uuid_chunk=chunks)
            .explode("uuid_chunk")
            .filter(pl.col("uuid_chunk").is_not_null() & (pl.col("uuid_chunk") != ""))
            .with_columns(
                service_uuid=normalize_uuid_hex_expr(pl.col("uuid_chunk"), width_bytes)
            )
            .select("label", "service_uuid")
        )

    uuid_rows = (
        pl.concat(uuid_frames)
        if uuid_frames
        else pl.LazyFrame({"label": [], "service_uuid": []})
    )
    service_uuids = uuid_rows.group_by("label").agg(pl.col("service_uuid"))

    return (
        lf.join(service_uuids, on="label", how="left")
        .with_columns(
            pl.col("service_uuid")
            .fill_null(pl.lit([], dtype=pl.List(pl.String)))
            .alias("service_uuids")
        )
        .drop("service_uuid")
    )


def main() -> None:
    lf, struct_cols = add_ad_columns(pl.LazyFrame(SAMPLES))
    parsed = lf.with_columns(
        structs=pl.concat_list([pl.col(name) for name in struct_cols]).list.drop_nulls()
    ).select("label", "raw", "structs")

    parsed = add_service_uuid_columns(parsed).collect()

    print("PARSED ROWS")
    print(parsed.select("label", "service_uuids"))
    print()

    for row in parsed.iter_rows(named=True):
        structs = pl.DataFrame(row["structs"])
        print(f"SAMPLE: {row['label']}")
        print("STRUCTS")
        print(structs)
        print("SERVICE UUIDS")
        print(row["service_uuids"])
        print()


if __name__ == "__main__":
    main()

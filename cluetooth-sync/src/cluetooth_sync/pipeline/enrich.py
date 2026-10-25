import cluetooth_sync.pipeline.db as db

from cluetooth_sync.pipeline.queries import read_query


def insert_builtin_ad_structures(
    database_url: str,
    *,
    scans_after_id: int | None = None,
    scans_through_id: int | None = None,
) -> None:
    with db.session(database_url, autocommit=True) as session:
        if scans_after_id is None or scans_through_id is None:
            session.cursor.execute(
                read_query("adv_enrichments/insert_builtin_ad_structures.sql")
            )
            return
        if scans_through_id <= scans_after_id:
            return
        session.cursor.execute(
            read_query("adv_enrichments/insert_builtin_ad_structures_range.sql"),
            [scans_after_id, scans_through_id],
        )

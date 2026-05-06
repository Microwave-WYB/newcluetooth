import cluetooth_sync.pipeline.db as db

from cluetooth_sync.pipeline.queries import read_query


def insert_builtin_ad_structures(database_url: str) -> None:
    with db.session(database_url, autocommit=True) as session:
        session.cursor.execute(
            read_query("adv_enrichments/insert_builtin_ad_structures.sql")
        )

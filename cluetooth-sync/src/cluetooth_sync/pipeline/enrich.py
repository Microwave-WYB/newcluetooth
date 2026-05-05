import adbc_driver_postgresql.dbapi as pg_dbapi

from cluetooth_sync.pipeline.queries import read_query


def insert_builtin_ad_structures(database_url: str) -> None:
    connection = pg_dbapi.connect(database_url, autocommit=True)
    cursor = connection.cursor()
    try:
        cursor.execute(read_query("adv_enrichments/insert_builtin_ad_structures.sql"))
    finally:
        cursor.close()
        connection.close()

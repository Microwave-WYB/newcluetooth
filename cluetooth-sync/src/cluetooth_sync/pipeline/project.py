import cluetooth_sync.pipeline.db as db

from cluetooth_sync.pipeline.queries import read_query


def project_advs(database_url: str) -> None:
    with db.session(database_url) as session:
        session.cursor.execute(read_query("advs/project.sql"))

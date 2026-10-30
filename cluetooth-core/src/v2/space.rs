//! SPEC R-10: the data volume's free space and the uploaded raw files (`archive/`, `docs/v2-api.md` §1.5).

use std::path::Path;

use super::error::CoreError;

/// Bytes an unprivileged writer can still use on the volume of `dir`; `None` when it cannot be read.
pub(crate) fn free_bytes(dir: &Path) -> Option<u64> {
    let s = rustix::fs::statvfs(dir).ok()?;
    s.f_bavail.checked_mul(s.f_frsize)
}

/// The size of the uploaded raw files: every regular file under `archive/`.
pub(crate) fn uploaded_raw_bytes(root: &Path) -> Result<u64, CoreError> {
    let mut total = 0;
    walk(
        &root.join(crate::payload_identity::ARCHIVE_ROOT),
        &mut |_, len| {
            total += len;
            Ok(())
        },
    )?;
    Ok(total)
}

/// O-1 item 8 "Raw files": every regular file under `pending/` (not uploaded yet) and `archive/` (uploaded).
pub(crate) fn raw_files_bytes(root: &Path) -> Result<u64, CoreError> {
    let mut total = 0;
    for area in [
        crate::payload_identity::PENDING_ROOT,
        crate::payload_identity::ARCHIVE_ROOT,
    ] {
        walk(&root.join(area), &mut |_, len| {
            total += len;
            Ok(())
        })?;
    }
    Ok(total)
}

/// Deletes the uploaded raw files (`archive/`) and their emptied directories; returns the bytes freed.
/// `pending/` (not yet uploaded) is never touched, nor are the upload markers that keep a file from uploading twice.
pub(crate) fn delete_uploaded_raw(root: &Path) -> Result<u64, CoreError> {
    let archive = root.join(crate::payload_identity::ARCHIVE_ROOT);
    let mut freed = 0;
    walk(&archive, &mut |path, len| {
        std::fs::remove_file(path).map_err(CoreError::from_io)?;
        freed += len;
        Ok(())
    })?;
    prune(&archive, true)?;
    Ok(freed)
}

/// Visits every regular file below `dir` with its length; symlinks are neither followed nor visited.
fn walk(
    dir: &Path,
    f: &mut dyn FnMut(&Path, u64) -> Result<(), CoreError>,
) -> Result<(), CoreError> {
    let entries = match std::fs::read_dir(dir) {
        Ok(entries) => entries,
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => return Ok(()),
        Err(e) => return Err(CoreError::from_io(e)),
    };
    for entry in entries {
        let entry = entry.map_err(CoreError::from_io)?;
        let kind = entry.file_type().map_err(CoreError::from_io)?;
        let path = entry.path();
        if kind.is_dir() {
            walk(&path, f)?;
        } else if kind.is_file() {
            let len = entry.metadata().map_err(CoreError::from_io)?.len();
            f(&path, len)?;
        }
    }
    Ok(())
}

/// Removes the empty directories below `dir` (and `dir` itself unless `keep_root`).
fn prune(dir: &Path, keep_root: bool) -> Result<(), CoreError> {
    let entries = match std::fs::read_dir(dir) {
        Ok(entries) => entries,
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => return Ok(()),
        Err(e) => return Err(CoreError::from_io(e)),
    };
    for entry in entries {
        let entry = entry.map_err(CoreError::from_io)?;
        if entry.file_type().map_err(CoreError::from_io)?.is_dir() {
            prune(&entry.path(), false)?;
        }
    }
    if !keep_root {
        // not empty (a file arrived, or a symlink) is fine
        let _ = std::fs::remove_dir(dir);
    }
    Ok(())
}

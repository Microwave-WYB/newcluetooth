use std::ffi::{OsStr, OsString};
use std::fs::File;
use std::os::unix::fs::MetadataExt;
use std::path::{Component, Path, PathBuf};

use rustix::fs::{
    AtFlags, Mode, OFlags, RenameFlags, fsync, mkdirat, open, openat, renameat_with, statat,
    unlinkat,
};
use rustix::io::Errno;

use crate::CoreError;

const DIRECTORY_MODE: Mode = Mode::RUSR.union(Mode::WUSR).union(Mode::XUSR);
const FILE_MODE: Mode = Mode::RUSR.union(Mode::WUSR);

/// A generated directory below the core root, held open so publication does not
/// need to resolve its ancestors again.
pub(crate) struct OwnedDirectory {
    file: File,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub(crate) enum OwnedSyncStep {
    CreatedDirectory(PathBuf),
    CreatedParent(PathBuf),
    PublishedFile(PathBuf),
    PublishedDirectory(PathBuf),
}

pub(crate) type BeforeOwnedSync<'a> = dyn Fn(OwnedSyncStep) -> Result<(), CoreError> + 'a;

impl OwnedDirectory {
    pub(crate) fn open_regular(&self, name: &OsStr) -> Result<Option<File>, CoreError> {
        validate_component(name)?;
        match openat(
            &self.file,
            name,
            OFlags::RDONLY | OFlags::CLOEXEC | OFlags::NOFOLLOW,
            Mode::empty(),
        ) {
            Ok(fd) => {
                let stat = rustix::fs::fstat(&fd).map_err(CoreError::io)?;
                if !rustix::fs::FileType::from_raw_mode(stat.st_mode).is_file() {
                    return Err(CoreError::invalid(
                        "owned payload target must be a regular file",
                    ));
                }
                Ok(Some(File::from(fd)))
            }
            Err(Errno::NOENT) => Ok(None),
            Err(error) => Err(CoreError::io(error)),
        }
    }

    pub(crate) fn create_temporary(&self) -> Result<(OsString, File), CoreError> {
        for _ in 0..32 {
            let mut random = [0_u8; 16];
            getrandom::fill(&mut random).map_err(CoreError::clock)?;
            let name = OsString::from(format!(".cluetooth-payload-{}.tmp", hex::encode(random)));
            match openat(
                &self.file,
                &name,
                OFlags::RDWR | OFlags::CREATE | OFlags::EXCL | OFlags::CLOEXEC | OFlags::NOFOLLOW,
                FILE_MODE,
            ) {
                Ok(fd) => return Ok((name, File::from(fd))),
                Err(Errno::EXIST) => continue,
                Err(error) => return Err(CoreError::io(error)),
            }
        }
        Err(CoreError::io(
            "could not allocate a unique owned payload temporary",
        ))
    }

    pub(crate) fn publish_noreplace(
        &self,
        temporary: &OsStr,
        target: &OsStr,
    ) -> Result<(), CoreError> {
        validate_component(temporary)?;
        validate_component(target)?;
        reject_non_regular_entry(&self.file, target)?;
        renameat_with(
            &self.file,
            temporary,
            &self.file,
            target,
            RenameFlags::NOREPLACE,
        )
        .map_err(CoreError::io)
    }

    pub(crate) fn remove_temporary(&self, name: &OsStr) -> Result<(), CoreError> {
        match unlinkat(&self.file, name, AtFlags::empty()) {
            Ok(()) => fsync(&self.file).map_err(CoreError::io),
            Err(Errno::NOENT) => Ok(()),
            Err(error) => Err(CoreError::io(error)),
        }
    }
}

/// Proves that an exact published file and its complete owned hierarchy are
/// durable before the active WAL can be removed. Every lookup below the root is
/// directory-relative and no-follow. The leaf is synced first, then each
/// directory through `pending`, and finally the data root that owns `pending`.
pub(crate) fn prove_owned_publication_durable(
    root: &Path,
    relative_directory: &Path,
    target: &OsStr,
    expected: &File,
    before_sync: &BeforeOwnedSync<'_>,
) -> Result<(), CoreError> {
    let root_file = open_directory(root)?;
    let root_metadata = root_file.metadata().map_err(CoreError::io)?;
    let mut directories = vec![(PathBuf::new(), root_file)];
    let mut relative = PathBuf::new();
    for component in relative_directory.components() {
        let Component::Normal(name) = component else {
            return Err(CoreError::invalid(
                "owned directory contains a non-normal component",
            ));
        };
        validate_component(name)?;
        relative.push(name);
        let parent = &directories.last().expect("root directory exists").1;
        let child = open_directory_at(parent, name)?;
        directories.push((relative.clone(), child));
    }

    let leaf = &directories.last().expect("root directory exists").1;
    let published = open_regular_at(leaf, target)?.ok_or_else(|| {
        CoreError::invalid("published payload disappeared before staging cleanup")
    })?;
    ensure_same_inode(expected, &published)?;
    let published_path = relative_directory.join(target);
    before_sync(OwnedSyncStep::PublishedFile(published_path))?;
    fsync(&published).map_err(CoreError::io)?;
    let reopened = open_regular_at(leaf, target)?.ok_or_else(|| {
        CoreError::invalid("published payload disappeared during durability proof")
    })?;
    ensure_same_inode(expected, &reopened)?;

    for index in (0..directories.len()).rev() {
        let (path, directory) = &directories[index];
        if index == 0 {
            let reopened_root = open_directory(root)?;
            ensure_metadata_matches(
                &root_metadata,
                &reopened_root.metadata().map_err(CoreError::io)?,
            )?;
        } else {
            let parent = &directories[index - 1].1;
            let name = path.file_name().expect("non-root path has a name");
            let reopened_directory = open_directory_at(parent, name)?;
            ensure_same_inode(directory, &reopened_directory)?;
        }
        before_sync(OwnedSyncStep::PublishedDirectory(path.clone()))?;
        fsync(directory).map_err(CoreError::io)?;
        if index == 0 {
            let reopened_root = open_directory(root)?;
            ensure_metadata_matches(
                &root_metadata,
                &reopened_root.metadata().map_err(CoreError::io)?,
            )?;
        } else {
            let parent = &directories[index - 1].1;
            let name = path.file_name().expect("non-root path has a name");
            let reopened_directory = open_directory_at(parent, name)?;
            ensure_same_inode(directory, &reopened_directory)?;
        }
    }
    let final_published = open_regular_at(leaf, target)?.ok_or_else(|| {
        CoreError::invalid("published payload disappeared after durability proof")
    })?;
    ensure_same_inode(expected, &final_published)
}

/// Opens the root and every generated component without following symlinks.
/// New levels are created one at a time; the new directory and then its parent
/// are synced before the next level is considered durable.
pub(crate) fn ensure_owned_directory(
    root: &Path,
    relative_directory: &Path,
    before_sync: &BeforeOwnedSync<'_>,
) -> Result<OwnedDirectory, CoreError> {
    if relative_directory.is_absolute() {
        return Err(CoreError::invalid("owned directory must be relative"));
    }
    let mut current = open_directory(root)?;
    let mut relative = PathBuf::new();
    for component in relative_directory.components() {
        let Component::Normal(name) = component else {
            return Err(CoreError::invalid(
                "owned directory contains a non-normal component",
            ));
        };
        validate_component(name)?;
        relative.push(name);
        let created = match mkdirat(&current, name, DIRECTORY_MODE) {
            Ok(()) => true,
            Err(Errno::EXIST) => false,
            Err(error) => return Err(CoreError::io(error)),
        };
        let child = open_directory_at(&current, name)?;
        if created {
            before_sync(OwnedSyncStep::CreatedDirectory(relative.clone()))?;
            fsync(&child).map_err(CoreError::io)?;
            let parent = relative
                .parent()
                .unwrap_or_else(|| Path::new(""))
                .to_owned();
            before_sync(OwnedSyncStep::CreatedParent(parent))?;
            fsync(&current).map_err(CoreError::io)?;
        }
        current = child;
    }
    Ok(OwnedDirectory { file: current })
}

fn open_directory(path: &Path) -> Result<File, CoreError> {
    open(
        path,
        OFlags::RDONLY | OFlags::DIRECTORY | OFlags::CLOEXEC | OFlags::NOFOLLOW,
        Mode::empty(),
    )
    .map(File::from)
    .map_err(CoreError::io)
}

fn open_directory_at(directory: &File, name: &OsStr) -> Result<File, CoreError> {
    openat(
        directory,
        name,
        OFlags::RDONLY | OFlags::DIRECTORY | OFlags::CLOEXEC | OFlags::NOFOLLOW,
        Mode::empty(),
    )
    .map(File::from)
    .map_err(CoreError::io)
}

fn open_regular_at(directory: &File, name: &OsStr) -> Result<Option<File>, CoreError> {
    validate_component(name)?;
    match openat(
        directory,
        name,
        OFlags::RDONLY | OFlags::CLOEXEC | OFlags::NOFOLLOW,
        Mode::empty(),
    ) {
        Ok(fd) => {
            let file = File::from(fd);
            let stat = rustix::fs::fstat(&file).map_err(CoreError::io)?;
            if !rustix::fs::FileType::from_raw_mode(stat.st_mode).is_file() {
                return Err(CoreError::invalid(
                    "owned payload target must be a regular file",
                ));
            }
            Ok(Some(file))
        }
        Err(Errno::NOENT) => Ok(None),
        Err(error) => Err(CoreError::io(error)),
    }
}

fn ensure_same_inode(expected: &File, reopened: &File) -> Result<(), CoreError> {
    ensure_metadata_matches(
        &expected.metadata().map_err(CoreError::io)?,
        &reopened.metadata().map_err(CoreError::io)?,
    )
}

fn ensure_metadata_matches(
    expected: &std::fs::Metadata,
    reopened: &std::fs::Metadata,
) -> Result<(), CoreError> {
    if expected.dev() != reopened.dev() || expected.ino() != reopened.ino() {
        return Err(CoreError::invalid(
            "published payload hierarchy changed during durability proof",
        ));
    }
    Ok(())
}

fn reject_non_regular_entry(directory: &File, name: &OsStr) -> Result<(), CoreError> {
    match statat(directory, name, AtFlags::SYMLINK_NOFOLLOW) {
        Ok(stat) => {
            if rustix::fs::FileType::from_raw_mode(stat.st_mode).is_file() {
                Err(CoreError::io(
                    "refusing to replace an existing pending payload",
                ))
            } else {
                Err(CoreError::invalid(
                    "owned payload target must not be a symlink or non-regular entry",
                ))
            }
        }
        Err(Errno::NOENT) => Ok(()),
        Err(error) => Err(CoreError::io(error)),
    }
}

fn validate_component(name: &OsStr) -> Result<(), CoreError> {
    if name.is_empty() || name == OsStr::new(".") || name == OsStr::new("..") {
        return Err(CoreError::invalid("owned path component is invalid"));
    }
    Ok(())
}

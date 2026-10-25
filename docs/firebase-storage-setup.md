# Firebase Storage setup for uploads (issue #14)

Findings (read-only, from the Firebase admin side): Storage rules releases exist only for
`cluetooth-1da02.firebasestorage.app`. The debug bucket `cluetooth-1da02-debug` has no rules
release, i.e. it is not registered with Firebase Storage, so SDK uploads to it are denied.
The production rules allow `create` anywhere but deny `update`, so overwriting an object fails;
the app now treats a failed retry as success when the object already exists.

## Register the debug bucket

1. Firebase console, project `cluetooth-1da02`, Build > Storage.
2. "Add bucket" > "Import existing bucket" and choose `gs://cluetooth-1da02-debug`
   (needs the Firebase service agent role on the bucket; the console offers to grant it).
3. Open its Rules tab and publish the rules below.

## Rules (create and overwrite under `scans/`, size cap, no read/delete)

```
rules_version = '2';
service firebase.storage {
  match /b/{bucket}/o {
    match /scans/{allPaths=**} {
      allow create, update: if request.resource.size < 64 * 1024 * 1024;
      allow read, delete: if false;
    }
  }
}
```

Note: the app's already-uploaded check reads object metadata. With `read` denied that check
fails and the original upload error is reported; if that safety net is wanted in production,
additionally `allow get: if true;` under `scans/` (metadata read of a known path only).
Allowing `update` makes the overwrite case a non-issue.

The Scan Sessions screen now shows the underlying error, e.g.
`StorageException code=-13021 http=403` (not authorized) or `code=-13011` (bucket not found).

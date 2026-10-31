# Firebase Storage setup for uploads (issue #14)

Findings (read-only, from the Firebase admin side): Storage rules releases exist only for
`cluetooth-1da02.firebasestorage.app`. The debug bucket `cluetooth-1da02-debug` has no rules
release, i.e. it is not registered with Firebase Storage, so SDK uploads to it are denied.
The production rules (now deployed, below) allow `create` and `get` but deny `update`.

## Register the debug bucket

1. Firebase console, project `cluetooth-1da02`, Build > Storage.
2. "Add bucket" > "Import existing bucket" and choose `gs://cluetooth-1da02-debug`
   (needs the Firebase service agent role on the bucket; the console offers to grant it).
3. Open its Rules tab and publish the rules below.

## Rules (deployed on production; use the same text for the debug bucket)

```
rules_version = '2';
service firebase.storage {
  match /b/{bucket}/o {
    match /scans/{path=**} {
      allow create: if request.resource.size < 50 * 1024 * 1024;
      allow get: if true;
      allow list, update, delete: if false;
    }
    // Manual GATT discoveries (docs/payload-schema-v3.md) use the same upload path and
    // need the same rule; without it the app records `StorageException code=-13021
    // http=403` and the object stays pending on the phone. Verified on a phone against
    // the debug bucket (issue #24); not yet deployed anywhere by this change.
    match /gatt/{path=**} {
      allow create: if request.resource.size < 50 * 1024 * 1024;
      allow get: if true;
      allow list, update, delete: if false;
    }
  }
}
```

Overwrite (`update`) is denied, so a retry of an object that was accepted but not acknowledged
locally fails. The app handles this: after a failed upload it reads the object's metadata
(`get`), and if the object exists it records the payload as uploaded. This relies on `get`.

The Scan Sessions screen shows the underlying error, e.g.
`StorageException code=-13021 http=403` (not authorized) or `code=-13011` (bucket not found).

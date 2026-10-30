package edu.ucsd.sysnet.cluetoothscanner.designsystem

/**
 * G-8: every UI string, for every lane (`:chrome`, `:sheets`, `:map`). English only; "scan", never "heard"; no
 * check or cross symbols; a device without an advertised name is "No name" and never falls back to the product or
 * wiki title; the address type is never shown. Numbers come in already formatted by [Format].
 */
object Strings {
    // ---- Plurals ----
    fun plural(n: Int, one: String, many: String = "${one}s"): String = "$n ${if (n == 1) one else many}"
    fun devices(n: Int) = plural(n, "device")
    fun sessions(n: Int) = plural(n, "session")
    fun results(n: Int) = plural(n, "result")
    fun services(n: Int) = plural(n, "service")

    // ---- G-8 vocabulary ----
    const val NoName = "No name"
    const val ScannedNow = "Scanned now"
    /** "Last scanned 2 h ago" ([ago] from [Format.ago]). */
    fun lastScanned(ago: String) = "Last scanned $ago"

    // ---- Top row and float buttons (P-2, C-1, L-1) ----
    const val SessionsAndSettings = "Sessions and settings"
    const val CloseSidebar = "Close sidebar"
    const val CloseSessions = "Close sessions"
    const val SearchDevices = "Search devices"
    const val Back = "Back"
    const val Close = "Close"
    const val ShowMyLocation = "Show my location"

    // ---- Location (P-5) ----
    const val LocationProblem = "Location problem"
    const val Location = "Location"
    const val LocationPermissionOff = "Location permission is off"
    const val LocationTurnedOff = "Location is turned off"
    const val WaitingForLocation = "Waiting for location…"
    fun locationAge(minutes: Int) = "Location is $minutes min old"
    const val Allow = "Allow"
    const val TurnOn = "Turn on"

    // ---- Recorder (R-*) ----
    const val Record = "Record"
    const val Recording = "Recording"
    const val Pause = "Pause"
    const val Resume = "Resume"
    const val Stop = "Stop"
    const val ShowRecordingDetails = "Show recording details"
    const val FitRoute = "Show the route on the map"
    /** R-4a: the discovery-rate chart's one label, the current value at the line's end. */
    fun newPerMinute(n: Int) = "$n new/min"

    // ---- Search (Q-3 … Q-7) ----
    const val SearchPlaceholder = "Search name or MAC"
    const val SearchHint = "Search devices by name or MAC address"
    const val NoResultsInArea = "No results in this area"
    const val NoDevicesMatch = "No devices match"
    const val SearchEverywhere = "Search everywhere"
    const val SearchThisArea = "Search this area"
    const val ExitSearch = "Exit search"
    /** Q-5 header: "3 results in this area" or "3 results". */
    fun resultsHeader(n: Int, inArea: Boolean) = results(n) + if (inArea) " in this area" else ""

    // ---- Discover (D-1 … D-5) ----
    const val Discover = "Discover"
    const val ShowDeviceList = "Show the device list"
    const val ShowCategories = "Show categories"
    const val Nearby = "Nearby"
    const val All = "All"
    const val ThisArea = "This area"
    const val Filter = "Filter"
    fun filterActive(n: Int) = if (n > 0) "Filter, $n active" else Filter
    /** D-1c: the re-sort button's label. */
    const val Resort = "Re-sort by signal"
    const val NoProductsMatch = "No products match"
    const val NoMatchingDevices = "No matching devices"
    const val Unidentified = "Unidentified"
    const val UnidentifiedDevices = "Unidentified devices"
    const val UnidentifiedDescription = "Devices no wiki family matches yet."
    const val ExpandPanel = "Expand the panel"

    // ---- Filter sheet (D-3a) ----
    const val Readable = "Readable"
    const val Trackers = "Trackers"
    const val Vulnerable = "Vulnerable"
    const val Identification = "Identification"
    const val Any = "Any"
    const val Identified = "Identified"
    const val Categories = "Categories"
    const val Clear = "Clear"

    // ---- Product card and sheet (C-7, D-6, D-7) ----
    /** C-7 status line: history "N devices"; live in range "In range · N devices"; live not in range "Last scanned …". */
    fun productStatus(live: Boolean, count: Int, inRange: Int, lastAgo: String) = when {
        !live -> devices(count)
        inRange > 0 -> "In range · ${devices(inRange)}"
        else -> lastScanned(lastAgo)
    }
    /** D-7 "Seen at N places · last X". */
    fun seenAt(places: Int, lastAgo: String) = "Seen at ${plural(places, "place")} · last $lastAgo"
    const val OpenInWiki = "Open in Wiki"
    const val Devices = "Devices"

    // ---- Device sheet (D-9 … D-13) ----
    fun dbm(rssi: String) = "$rssi dBm"
    const val ChartMinusOneMin = "−1 min"
    const val ChartNow = "now"
    const val Advertising = "Advertising"
    const val Gatt = "GATT"
    const val Details = "Details"
    const val RawData = "Raw data"
    const val Connectable = "Connectable"
    const val Yes = "Yes"
    const val No = "No"
    const val FormatKey = "Format"
    const val Legacy = "Legacy"
    const val Extended = "Extended"
    const val Company = "Company"
    const val Service = "Service"
    const val TxPower = "TX power"
    const val Len = "Len"
    const val Type = "Type"
    const val Data = "Data"
    const val GattNote = "Only connect to devices you own or have permission to test."
    const val DiscoverGatt = "Discover GATT"
    /** D-13.7: a device already read. */
    const val DiscoverAgain = "Discover again"
    const val Cancel = "Cancel"
    const val IAmAuthorized = "I am authorized"
    const val TryAgain = "Try again"
    const val Connecting = "Connecting…"
    const val DiscoveringServices = "Discovering services…"
    const val ReadingCharacteristics = "Reading characteristics"
    const val Disconnected = "Disconnected"
    const val TimedOut = "Timed out"
    const val DisconnectedUnexpectedly = "Disconnected unexpectedly"
    const val ConnectionFailed = "Connection failed"
    const val Cancelled = "Cancelled"
    fun gattFailedStatus(status: Int) = "Failed (status $status)"
    const val Running = "Running"
    const val Done = "Done"
    const val Failed = "Failed"
    /** D-13 "m/n" progress and "n/n" when done. */
    fun ofCount(m: Int, n: Int) = "$m/$n"

    // ---- Map (M-8) ----
    const val Start = "Start"
    const val End = "End"

    // ---- Sidebar (L-2) ----
    const val CurrentSession = "Current session"
    const val AllSessions = "All sessions"
    const val Sessions = "Sessions"
    const val Today = "Today"
    const val Yesterday = "Yesterday"
    /** "Started 7:12 PM · 26 devices". */
    fun startedAt(time: String, devices: Int) = "Started $time · ${devices(devices)}"
    /** "N sessions · M devices". */
    fun allSessionsSub(sessions: Int, devices: Int) = "${sessions(sessions)} · ${devices(devices)}"

    // ---- Settings (O-1) ----
    const val Settings = "Settings"
    const val UserId = "User ID"
    const val RecordOnOpen = "Record when the app opens"
    const val RecordOnOpenSub = "Starts a recording each time Cluetooth opens"
    const val WifiOnly = "Upload over Wi-Fi only"
    const val CodedPhy = "Scan LE Coded PHY"
    const val CodedPhySub = "Also scan long-range devices; applies within a minute"
    const val PendingUploads = "Pending uploads"
    const val UploadNow = "Upload now"
    const val NothingPending = "Nothing to upload"
    /** "3 files · 4.2 MB". */
    fun pendingSub(count: Int, size: String) = "${plural(count, "file")} · $size"
    fun uploadFailed(count: Int) = "${plural(count, "upload")} failed; retrying"
    const val Filters = "Filters"
    const val HideUnnamedApple = "Hide unnamed Apple devices"
    const val HideUnnamedAppleSub = "Only what you see; scanning and recording keep everything"
    const val Storage = "Storage"
    const val StorageUsed = "Storage used"
    /** "Database 12 MB · Raw files 340 MB · Map cache 48 MB". */
    fun storageBreakdown(database: String, rawFiles: String, mapCache: String?) =
        listOfNotNull("Database $database", "Raw files $rawFiles", mapCache?.let { "Map cache $it" }).joinToString(" · ")
    const val ExportData = "Export data"
    const val ExportSub = "Choose the sessions, then a format"
    const val DeleteLocalData = "Delete local data"
    const val DeleteSub = "Choose the sessions, then confirm"
    const val Scope = "Sessions"
    const val ExportFormatLabel = "Format"
    const val FormatParquet = "Raw (Parquet)"
    const val FormatParquetSub = "The recorded payloads (Parquet v3) and GATT results (JSON), as stored"
    const val FormatCsv = "Table (CSV)"
    const val FormatCsvSub = "The same rows as a table, with session_id and source_file columns"
    const val FormatSqlite = "Database (SQLite)"
    const val FormatSqliteSub = "A snapshot of the aggregated tables, aligned with the research database"
    const val Share = "Share"
    const val SaveToDownloads = "Save to Downloads"
    const val Preparing = "Preparing…"
    /** "2 files · 12 MB". */
    fun exportReady(files: Int, size: String) = "${plural(files, "file")} · $size"
    const val SavedToDownloads = "Saved to Downloads"
    const val Delete = "Delete"
    fun deleteConfirmTitle(scope: String) = "Delete $scope?"
    const val DeleteConfirmBody = "This removes the recordings from this phone. Data that has not uploaded yet stays until it has uploaded."
    fun deleteLoseTitle(pending: Int) = "${plural(pending, "file")} not uploaded yet"
    /** O-1 item 11 when the storage read failed: the pending count is unknown, so the warning still shows. */
    const val DeleteLoseUnknownTitle = "Some files may not be uploaded yet"
    const val DeleteLoseBody = "They will upload first and then be deleted. Delete them now instead, and they are lost for good."
    const val KeepUntilUploaded = "Keep until uploaded"
    const val DeleteNow = "Delete now"
    fun deletedSessions(n: Int) = "Deleted ${sessions(n)}"
    fun deletedPendingKept(n: Int) = "Deleted; ${plural(n, "file")} will be removed after upload"
    fun deleteRefused(reason: String) = "Not deleted: $reason"
    const val StopRecordingFirst = "Stop the recording first"
    const val AppVersion = "App version"

    // ---- Onboarding (O-2) ----
    fun stepOf(step: Int, of: Int) = "$step of $of"
    const val Next = "Next"
    const val Permissions = "Permissions"
    const val PermissionsBody = "Cluetooth listens for Bluetooth devices around you. It needs:"
    const val NearbyDevices = "Nearby devices"
    const val NearbyDevicesWhy = "To scan Bluetooth advertisements and read a device's services."
    const val PreciseLocation = "Precise location"
    const val PreciseLocationWhy = "To put what you scan on the map. Android also needs it for Bluetooth scanning."
    const val Notifications = "Notifications"
    const val NotificationsWhy = "To show that a recording is running. Optional."
    const val Allowed = "Allowed"
    const val OpenSettings = "Open settings"
    const val Bluetooth = "Bluetooth"
    const val BluetoothOn = "Bluetooth is on."
    const val BluetoothOff = "Bluetooth is off. Cluetooth needs it to scan."
    const val TurnOnBluetooth = "Turn on Bluetooth"
    const val YourUserId = "Your user ID"
    const val UserIdBody = "It marks your uploads. You can change it later in Settings."
    const val UserIdHint = "Lowercase letters and numbers only, max 9 characters."
    const val RecordAutomatically = "Record automatically?"
    const val RecordAutomaticallyBody = "A recording scans the devices around you, saves them with your route, and uploads them. Cluetooth only scans while it records."
    const val RecordAutomaticallySwitch = "Start recording automatically when the app opens"
    const val RecordAutomaticallySub = "You can turn this off in Settings."
    const val MissingTitle = "Cluetooth needs one more thing"

    // ---- R-10 Storage full (and O-1 item 11) ----
    const val StorageFullTitle = "Storage is full"
    const val StorageFullBody = "Recording is paused because the phone is almost out of space. Free up some space to continue."
    /** "1.3 GB free". */
    fun freeSpace(bytes: Long) = "${Format.bytes(bytes)} free"
    const val DeleteUploadedRaw = "Delete uploaded raw files"
    const val ClearLocalDatabase = "Clear local database"
    /** What a cleanup frees: "Frees 340 MB". */
    fun frees(bytes: Long) = "Frees ${Format.bytes(bytes)}"
    /** While the core is asked what a cleanup frees. */
    const val Checking = "Checking…"
    const val FreeUpSpace = "Free up space"
    const val NotNow = "Not now"
    const val Freeing = "Freeing up space…"
    /** After a cleanup: "Freed 340 MB". */
    fun freed(bytes: Long) = "Freed ${Format.bytes(bytes)}"
    /** A cleanup that failed, with the core's reason. */
    fun deleteUploadedRawFailed(reason: String) = "Couldn't delete uploaded raw files: $reason"
    fun clearLocalDatabaseFailed(reason: String) = "Couldn't clear the local database: $reason"
    /** #79: the recording paused because storing kept failing (not for space); Resume makes the core try again. */
    fun storageFailedPaused(reason: String) = "Recording paused: saving keeps failing ($reason)"
    /** The free space and the cleanups' sizes couldn't be read. */
    fun reclaimableFailed(reason: String) = "Couldn't read the free space: $reason"
    /** The reason while the core does not offer the cleanups yet. */
    const val CleanupUnavailable = "not available in this version"
    const val DeleteUploadedRawConfirmTitle = "Delete uploaded raw files?"
    const val DeleteUploadedRawConfirmBody = "Raw files that have already uploaded are deleted. Files not yet uploaded are never touched."
    const val ClearLocalDatabaseConfirmTitle = "Clear local database?"
    const val ClearLocalDatabaseConfirmBody =
        "The stored history of every session except the current one is deleted. Raw files not yet uploaded are kept, so they still upload."

    // ---- App (integration) ----
    /** §1.2.2: the core could not open its data (the error class and message follow, then Try again). */
    const val CoreOpenFailed = "Cluetooth could not open its data"
    /** §1.2.2: the main screen failed after the data opened (the error class and message follow, then Try again). */
    const val ScreenFailed = "Cluetooth ran into a problem"

    // ---- Notifications (B) ----
    /** "Recording · 12:34 · 26 devices". */
    fun recordingNotification(elapsed: String, devices: Int) = "Recording · $elapsed · ${devices(devices)}"
    const val RecordingPaused = "Recording paused"
    const val RecordingInterrupted = "Recording interrupted"
}

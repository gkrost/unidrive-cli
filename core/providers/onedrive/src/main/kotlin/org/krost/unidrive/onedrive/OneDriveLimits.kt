package org.krost.unidrive.onedrive

/**
 * gkrost/unidrive-cli#508: the largest file OneDrive (personal and business) and SharePoint take, 250 GB (Microsoft,
 * "Restrictions and limitations in OneDrive and SharePoint", "File size": "The maximum file size is 250 GB",
 * https://support.microsoft.com/office/restrictions-and-limitations-in-onedrive-and-sharepoint-64883a5d-228e-48f5-b3d2-eb39e07630fa;
 * raised from 100 GB in 2021). Taken as decimal gigabytes, the lower reading, so no file the service refuses passes the
 * preflight. Kept out of OneDriveProvider.kt, which has no sizeRatchet headroom.
 */
internal const val ONEDRIVE_MAX_FILE_SIZE_BYTES: Long = 250L * 1000 * 1000 * 1000

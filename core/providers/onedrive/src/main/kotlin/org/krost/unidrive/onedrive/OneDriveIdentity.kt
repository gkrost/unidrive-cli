package org.krost.unidrive.onedrive

import org.krost.unidrive.AccountChangedException
import org.krost.unidrive.AccountOwner
import org.krost.unidrive.AccountUnverifiedException
import org.krost.unidrive.CompleteAuthResult
import org.krost.unidrive.onedrive.model.Drive
import org.krost.unidrive.onedrive.model.Token

/**
 * The id of the user who owns the drive: `owner.user.id` of `GET /me/drive` (Microsoft Graph drive resource, `owner`
 * identitySet, whose identity carries `id` and `displayName`). It needs only the file scopes the app holds, where
 * `GET /me` wants User.Read and answers 403. A drive without an owner user gives null: nothing is recorded, and a
 * stored id cannot be compared. Personal data: never logged.
 */
internal fun Drive.accountId(): String? = owner?.user?.id?.trim()?.takeIf { it.isNotEmpty() }

/** The owner's display name (`owner.user.displayName` of `GET /me/drive`); null when the drive gives none. Never logged. */
internal fun Drive.accountName(): String? = owner?.user?.displayName?.trim()?.takeIf { it.isNotEmpty() }

/**
 * The owner id of the drive of [token] (see [accountId]): the stable id a profile's state is kept against
 * ([org.krost.unidrive.AccountOwner]). Null when it cannot be read. Never logged.
 */
@Suppress("TooGenericExceptionCaught")
internal suspend fun graphUserId(
    config: OneDriveConfig,
    token: Token,
): String? =
    try {
        GraphApiService(config) { token.accessToken }.use { it.getDrive().accountId() }
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

/** The refusal a device-code sign-in gets for [verdict] ([AccountOwner]), or null when it may store its token. */
internal fun ownerRefusal(verdict: AccountOwner.Verdict): CompleteAuthResult.Failure? =
    when (verdict) {
        AccountOwner.Verdict.SAME, AccountOwner.Verdict.ADOPT -> null
        AccountOwner.Verdict.CHANGED ->
            CompleteAuthResult.Failure(AccountChangedException().message.orEmpty(), CompleteAuthResult.Reason.ACCOUNT_CHANGED)
        AccountOwner.Verdict.UNVERIFIED ->
            CompleteAuthResult.Failure(AccountUnverifiedException().message.orEmpty(), CompleteAuthResult.Reason.ACCOUNT_UNVERIFIED)
    }

package org.krost.unidrive.internxt

// gkrost/unidrive-cli#793: the route of a failed call for its error text, so a failure can be attributed to a call from
// the text alone. A segment that is an id (a uuid, a bucket or object id: 16 or more hex digits) is masked as {id}, the
// query is never part of it: the text names the route, not the item.
internal fun maskedRoute(encodedPath: String): String =
    encodedPath.split('/').joinToString("/") { segment -> if (ROUTE_ID.matches(segment)) "{id}" else segment }

// A route segment that names an item (see maskedRoute): a uuid, or 16 or more hex digits.
private val ROUTE_ID = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}|[0-9a-fA-F]{16,}")

package io.github.mangi.eta.data.model

import okhttp3.HttpUrl

/** Removes every decoded-name match while preserving the other encoded query pairs. */
internal fun HttpUrl.withoutDecodedQueryParameter(name: String): HttpUrl {
    val query = encodedQuery ?: return this
    val pairs = query.split('&')
    val retained = pairs.filterIndexed { index, _ -> queryParameterName(index) != name }
    if (retained.size == pairs.size) return this
    return newBuilder()
        .encodedQuery(retained.takeIf { it.isNotEmpty() }?.joinToString("&"))
        .build()
}

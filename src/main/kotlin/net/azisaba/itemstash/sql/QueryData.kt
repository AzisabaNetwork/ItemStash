package net.azisaba.itemstash.sql

import java.sql.ResultSet
import java.util.concurrent.CompletableFuture

class QueryData<R>(
    val sql: String,
    val args: List<*>,
    val resultExtractor: (ResultSet) -> R,
) {
    val future = CompletableFuture<R>()
}

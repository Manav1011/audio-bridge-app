package com.example.util

data class HostPort(
    val host: String,
    val port: Int
) {
    override fun toString(): String = "$host:$port"
}

object EndpointParser {
    fun parse(endpointStr: String): Result<HostPort> {
        val trimmed = endpointStr.trim()
        if (trimmed.isEmpty()) {
            return Result.failure(IllegalArgumentException("Endpoint cannot be empty"))
        }

        val lastColon = trimmed.lastIndexOf(':')
        if (lastColon <= 0 || lastColon == trimmed.length - 1) {
            return Result.failure(IllegalArgumentException("Invalid format. Use 'host:port' (e.g., 192.168.1.100:5000)"))
        }

        val host = trimmed.substring(0, lastColon).trim()
        val portStr = trimmed.substring(lastColon + 1).trim()

        if (host.isEmpty()) {
            return Result.failure(IllegalArgumentException("Host address is missing"))
        }

        val port = portStr.toIntOrNull()
            ?: return Result.failure(IllegalArgumentException("Invalid port number: '$portStr'"))

        if (port !in 1..65535) {
            return Result.failure(IllegalArgumentException("Port must be between 1 and 65535"))
        }

        return Result.success(HostPort(host, port))
    }
}

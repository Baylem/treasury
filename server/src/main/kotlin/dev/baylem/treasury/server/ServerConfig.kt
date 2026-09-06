package dev.baylem.treasury.server

import java.net.URI

data class ServerConfig(
    val databaseUrl: String,
    val databaseUser: String,
    val databasePassword: String,
    val publicUrl: String,
    val development: Boolean = false,
    val port: Int = 8080,
    val corsOrigins: Set<String> = emptySet(),
    val oauthProviders: Map<String, OAuthProviderConfig> = emptyMap(),
    val mail: MailConfig? = null,
    val trustedProxyAddresses: Set<String> = emptySet(),
) {
    override fun toString() = "ServerConfig(publicUrl=$publicUrl, development=$development, credentials=REDACTED)"
    init {
        require(port in 1..65535) { "PORT must be between 1 and 65535" }
        require(databaseUrl.startsWith("jdbc:postgresql:")) { "DATABASE_URL must be a PostgreSQL JDBC URL" }
        validateOrigin(publicUrl, development)
        corsOrigins.forEach { validateOrigin(it, development) }
        trustedProxyAddresses.forEach { require(parseIpAddress(it) != null) { "TRUSTED_PROXY_ADDRESSES must contain literal IP addresses" } }
        require(databaseUser.isNotBlank() && databasePassword.isNotBlank()) { "Database credentials are required" }
    }

    companion object {
        fun fromEnvironment(environment: Map<String, String> = System.getenv()): ServerConfig {
            fun required(name: String) = requireNotNull(environment[name]?.takeIf { it.isNotBlank() }) { "$name is required" }
            val development = environment["TREASURY_ENV"] == "development"
            val publicUrl = required("PUBLIC_URL").trimEnd('/')
            val providers = listOf("google", "github", "discord").mapNotNull { provider ->
                val prefix = provider.uppercase()
                val clientId = environment["${prefix}_CLIENT_ID"]?.takeIf { it.isNotBlank() }
                val secret = environment["${prefix}_CLIENT_SECRET"]?.takeIf { it.isNotBlank() }
                require((clientId == null) == (secret == null)) { "Both $prefix OAuth credentials are required" }
                clientId?.let { provider to OAuthProviderConfig(provider, it, secret!!, "$publicUrl/v1/auth/oauth/$provider/callback") }
            }.toMap()
            return ServerConfig(
                required("DATABASE_URL"), required("DATABASE_USER"), required("DATABASE_PASSWORD"),
                publicUrl, development, environment["PORT"]?.toIntOrNull() ?: 8080,
                environment["CORS_ORIGINS"].orEmpty().split(',').map(String::trim).filter(String::isNotEmpty).toSet(), providers,
                environment["SMTP_HOST"]?.takeIf(String::isNotBlank)?.let { host -> MailConfig(host, environment["SMTP_PORT"]?.toIntOrNull() ?: 587, required("SMTP_USER"), required("SMTP_PASSWORD"), required("SMTP_FROM"), environment["SMTP_TLS"] ?: "starttls") },
                environment["TRUSTED_PROXY_ADDRESSES"].orEmpty().split(',').map(String::trim).filter(String::isNotEmpty).toSet(),
            )
        }

        private fun validateOrigin(value: String, development: Boolean) {
            val uri = URI(value)
            require(uri.host != null && uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null && uri.path.orEmpty().isEmpty()) { "URLs must be origins without paths, credentials, query, or fragment" }
            require(uri.scheme == "https" || (development && uri.scheme == "http" && uri.host in setOf("localhost", "127.0.0.1", "[::1]"))) { "HTTPS is required outside local development" }
        }
    }
}

data class OAuthProviderConfig(val name: String, val clientId: String, val clientSecret: String, val callbackUrl: String) {
    override fun toString() = "OAuthProviderConfig(name=$name, credentials=REDACTED)"
}

data class MailConfig(val host: String, val port: Int, val username: String, val password: String, val from: String, val tls: String = "starttls") {
    init {
        require(host.isNotBlank() && port in 1..65535 && username.isNotBlank() && password.isNotBlank()) { "SMTP settings are incomplete" }
        require(tls in setOf("starttls", "tls")) { "SMTP_TLS must be starttls or tls" }
        require(from.length in 3..254 && from.all { it.code in 33..126 } && '@' in from) { "SMTP_FROM must be an email address" }
    }
    override fun toString() = "MailConfig(credentials=REDACTED)"
}

package dev.baylem.treasury

import dev.baylem.treasury.server.*
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.v1.jdbc.Database
import java.util.UUID

/** Real JDBC + Exposed transactions and the production Flyway SQL, without Docker. */
fun testStore(): PostgresStore {
    System.getenv("TREASURY_TEST_DATABASE_URL")?.takeIf { it.isNotBlank() }?.let { baseUrl ->
        require(baseUrl.startsWith("jdbc:postgresql:"))
        val schema = "treasury_test_" + UUID.randomUUID().toString().replace("-", "")
        val username = requireNotNull(System.getenv("TREASURY_TEST_DATABASE_USER"))
        val password = requireNotNull(System.getenv("TREASURY_TEST_DATABASE_PASSWORD"))
        val url = baseUrl + (if ('?' in baseUrl) "&" else "?") + "currentSchema=$schema"
        Flyway.configure().dataSource(url, username, password).schemas(schema).defaultSchema(schema).locations("classpath:db/migration").load().migrate()
        return PostgresStore(Database.connect(url, driver = "org.postgresql.Driver", user = username, password = password))
    }
    val url = "jdbc:h2:mem:${UUID.randomUUID()};MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
    Flyway.configure().dataSource(url, "sa", "").locations("classpath:db/migration").load().migrate()
    return PostgresStore(Database.connect(url, driver = "org.h2.Driver", user = "sa", password = ""))
}

class FastPasswords : PasswordHasher {
    override suspend fun hash(password: String): String = "test:${Secrets.hash(password)}"
    override suspend fun verify(hash: String, password: String): Boolean = hash == hash(password)
}

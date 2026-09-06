package dev.baylem.treasury.server

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import dev.baylem.treasury.domain.TreasurySnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.util.UUID

internal object Users : Table("treasury_users") {
    val id = varchar("id", 36)
    val email = varchar("email", 254).nullable().uniqueIndex()
    val passwordHash = varchar("password_hash", 512).nullable()
    val createdAt = long("created_at")
    val lastChange = long("last_change").default(0)
    val emailVerified = bool("email_verified").default(false)
    override val primaryKey = PrimaryKey(id)
}
internal object Sessions : Table("treasury_sessions") {
    val hash = varchar("token_hash", 64)
    val ownerId = varchar("owner_id", 36).references(Users.id, onDelete = ReferenceOption.CASCADE)
    val createdAt = long("created_at")
    val expiresAt = long("expires_at")
    override val primaryKey = PrimaryKey(hash)
}
internal object Identities : Table("treasury_oauth_identities") {
    val provider = varchar("provider", 16)
    val subject = varchar("subject", 255)
    val ownerId = varchar("owner_id", 36).references(Users.id, onDelete = ReferenceOption.CASCADE)
    override val primaryKey = PrimaryKey(provider, subject)
}
internal object Flows : Table("treasury_oauth_flows") {
    val hash = varchar("state_hash", 64)
    val provider = varchar("provider", 16)
    val verifier = varchar("verifier", 128)
    val expiresAt = long("expires_at")
    val deviceAttemptId = varchar("device_attempt_id", 36).nullable()
    override val primaryKey = PrimaryKey(hash)
}
internal object Records : Table("treasury_records") {
    val ownerId = varchar("owner_id", 36).references(Users.id, onDelete = ReferenceOption.CASCADE)
    val id = varchar("id", 36)
    val kind = varchar("kind", 16)
    val payload = text("payload")
    val serverUpdatedAt = long("server_updated_at")
    override val primaryKey = PrimaryKey(ownerId, id)
}

internal object Challenges : Table("treasury_auth_challenges") {
    val hash = varchar("token_hash", 64)
    val ownerId = varchar("owner_id", 36).references(Users.id, onDelete = ReferenceOption.CASCADE)
    val purpose = varchar("purpose", 16)
    val expiresAt = long("expires_at")
    override val primaryKey = PrimaryKey(hash)
}
internal object Devices : Table("treasury_oauth_devices") {
    val id = varchar("id", 36)
    val secretHash = varchar("secret_hash", 64)
    val codeHash = varchar("code_hash", 64)
    val provider = varchar("provider", 16)
    val expiresAt = long("expires_at")
    val ownerId = varchar("owner_id", 36).references(Users.id, onDelete = ReferenceOption.CASCADE).nullable()
    val approvalHash = varchar("approval_hash", 64).nullable()
    val approved = bool("approved").default(false)
    val failedAttempts = integer("failed_attempts").default(0)
    override val primaryKey = PrimaryKey(id)
}
internal object MailOutbox : Table("treasury_mail_outbox") {
    val hash = varchar("token_hash", 64).references(Challenges.hash, onDelete = ReferenceOption.CASCADE)
    val recipient = varchar("recipient", 254)
    val subject = varchar("subject", 200)
    val body = text("body")
    val attempts = integer("attempts").default(0)
    val nextAttemptAt = long("next_attempt_at")
    override val primaryKey = PrimaryKey(hash)
}

class PostgresStore(private val database: Database, private val pool: AutoCloseable? = null) : ServerStore {
    private val dispatcher = Dispatchers.IO.limitedParallelism(4)
    companion object {
        fun open(config: ServerConfig): PostgresStore {
            val pool = HikariDataSource(HikariConfig().apply {
                jdbcUrl = config.databaseUrl
                username = config.databaseUser
                password = config.databasePassword
                maximumPoolSize = 10
                minimumIdle = 1
                connectionTimeout = 10_000
                validationTimeout = 5_000
                maxLifetime = 1_800_000
                isAutoCommit = false
                poolName = "treasury"
            })
            try {
                Flyway.configure().dataSource(pool).locations("classpath:db/migration").cleanDisabled(true).load().migrate()
                return PostgresStore(Database.connect(pool), pool)
            } catch (error: Exception) { pool.close(); throw error }
        }
    }

    private suspend fun <T> query(block: JdbcTransaction.() -> T): T = withContext(dispatcher) {
        transaction(database) { queryTimeout = 15; block() }
    }
    override suspend fun ready(): Boolean = try { query { exec("SELECT 1") { it.next() } == true } } catch (_: Exception) { false }
    private fun ResultRow.user() = UserRecord(this[Users.id], this[Users.email], this[Users.passwordHash], this[Users.createdAt])
    override suspend fun findUserByEmail(email: String): UserRecord? = query { Users.selectAll().where { Users.email eq email }.singleOrNull()?.user() }
    override suspend fun findUser(id: String): UserRecord? = query { Users.selectAll().where { Users.id eq id }.singleOrNull()?.user() }
    override suspend fun createUser(user: UserRecord): Boolean = query {
        Users.insertIgnore { it[id] = user.id; it[email] = user.email; it[passwordHash] = user.passwordHash; it[createdAt] = user.createdAt }.insertedCount == 1
    }
    override suspend fun oauthUser(provider: String, subject: String, now: Long): UserRecord = query {
        // Serialize provider identity creation; collisions must never attach by email.
        if (database.dialect is org.jetbrains.exposed.v1.core.vendors.PostgreSQLDialect) exec("SELECT pg_advisory_xact_lock(${(provider + ":" + subject).hashCode()})")
        val existing = Identities.selectAll().where { (Identities.provider eq provider) and (Identities.subject eq subject) }.singleOrNull()
        if (existing != null) return@query Users.selectAll().where { Users.id eq existing[Identities.ownerId] }.single().user()
        val user = UserRecord(UUID.randomUUID().toString(), null, null, now)
        Users.insert { it[id] = user.id; it[email] = null; it[passwordHash] = null; it[createdAt] = now }
        Identities.insert { it[Identities.provider] = provider; it[Identities.subject] = subject; it[ownerId] = user.id }
        user
    }
    private fun lockOwner(ownerId: String): ResultRow = Users.selectAll().where { Users.id eq ownerId }.forUpdate().singleOrNull() ?: unauthorized()
    override suspend fun addSession(session: SessionRecord, expectedPasswordHash: String?) = query {
        val owner = lockOwner(session.ownerId)
        if (expectedPasswordHash != null && owner[Users.passwordHash] != expectedPasswordHash) unauthorized()
        Sessions.deleteWhere { expiresAt lessEq session.createdAt }
        val old = Sessions.selectAll().where { Sessions.ownerId eq session.ownerId }.orderBy(Sessions.createdAt to SortOrder.DESC).drop(4).map { it[Sessions.hash] }
        if (old.isNotEmpty()) Sessions.deleteWhere { hash inList old }
        Sessions.insert { it[hash] = session.hash; it[ownerId] = session.ownerId; it[createdAt] = session.createdAt; it[expiresAt] = session.expiresAt }
        Unit
    }
    override suspend fun session(hash: String, now: Long): SessionRecord? = query {
        Sessions.selectAll().where { (Sessions.hash eq hash) and (Sessions.expiresAt greater now) }.singleOrNull()?.let {
            SessionRecord(it[Sessions.hash], it[Sessions.ownerId], it[Sessions.createdAt], it[Sessions.expiresAt])
        }
    }
    override suspend fun revokeSession(hash: String) = query { Sessions.deleteWhere { Sessions.hash eq hash }; Unit }
    override suspend fun revokeAllSessions(ownerId: String) = query { Sessions.deleteWhere { Sessions.ownerId eq ownerId }; Unit }
    override suspend fun addOAuthFlow(flow: OAuthFlow) = query {
        Flows.deleteWhere { expiresAt lessEq System.currentTimeMillis() }
        Flows.insert { it[hash] = flow.stateHash; it[provider] = flow.provider; it[verifier] = flow.verifier; it[expiresAt] = flow.expiresAt; it[deviceAttemptId] = flow.deviceAttemptId }
        Unit
    }
    override suspend fun consumeOAuthFlow(hash: String, provider: String, now: Long): OAuthFlow? = query {
        val row = Flows.selectAll().where { (Flows.hash eq hash) and (Flows.provider eq provider) and (Flows.expiresAt greater now) }.forUpdate().singleOrNull()
        if (row != null) Flows.deleteWhere { Flows.hash eq hash }
        row?.let { OAuthFlow(it[Flows.hash], it[Flows.provider], it[Flows.verifier], it[Flows.expiresAt], it[Flows.deviceAttemptId]) }
    }
    private fun ResultRow.record() = EncodedRecord(this[Records.id], this[Records.kind], this[Records.payload])
    override suspend fun queueChallenge(challenge: AuthChallenge, mail: OutgoingMail, now: Long) = query {
        lockOwner(challenge.ownerId)
        Challenges.deleteWhere { (ownerId eq challenge.ownerId) and (purpose eq challenge.purpose) }
        Challenges.insert { it[hash] = challenge.tokenHash; it[ownerId] = challenge.ownerId; it[purpose] = challenge.purpose; it[expiresAt] = challenge.expiresAt }
        MailOutbox.insert { it[hash] = mail.tokenHash; it[recipient] = mail.recipient; it[subject] = mail.subject; it[body] = mail.body; it[nextAttemptAt] = now }
        Unit
    }
    override suspend fun challengeExists(hash: String, purpose: String, now: Long): Boolean = query {
        Challenges.selectAll().where { (Challenges.hash eq hash) and (Challenges.purpose eq purpose) and (Challenges.expiresAt greater now) }.any()
    }
    override suspend fun redeemChallenge(hash: String, purpose: String, passwordHash: String?, now: Long): Boolean = query {
        val preliminary = Challenges.selectAll().where { (Challenges.hash eq hash) and (Challenges.purpose eq purpose) and (Challenges.expiresAt greater now) }.singleOrNull() ?: return@query false
        val ownerId = preliminary[Challenges.ownerId]
        lockOwner(ownerId)
        val challenge = Challenges.selectAll().where { (Challenges.hash eq hash) and (Challenges.expiresAt greater now) }.singleOrNull() ?: return@query false
        if (purpose == "reset") {
            requireNotNull(passwordHash)
            Users.update({ Users.id eq ownerId }) { it[Users.passwordHash] = passwordHash; it[emailVerified] = true }
            Sessions.deleteWhere { Sessions.ownerId eq ownerId }
            Challenges.deleteWhere { Challenges.ownerId eq ownerId }
        } else {
            Users.update({ Users.id eq ownerId }) { it[emailVerified] = true }
            Challenges.deleteWhere { Challenges.hash eq challenge[Challenges.hash] }
        }
        true
    }
    override suspend fun emailVerified(ownerId: String): Boolean = query {
        Users.selectAll().where { Users.id eq ownerId }.singleOrNull()?.get(Users.emailVerified) ?: false
    }
    override suspend fun claimMail(now: Long): List<OutgoingMail> = query {
        Challenges.deleteWhere { expiresAt lessEq now }
        val rows = MailOutbox.selectAll().where { (MailOutbox.nextAttemptAt lessEq now) and (MailOutbox.attempts less 6) }.orderBy(MailOutbox.nextAttemptAt to SortOrder.ASC).limit(5).forUpdate().toList()
        rows.map { row ->
            MailOutbox.update({ MailOutbox.hash eq row[MailOutbox.hash] }) { it[nextAttemptAt] = now + 180_000; it[attempts] = row[MailOutbox.attempts] + 1 }
            OutgoingMail(row[MailOutbox.hash], row[MailOutbox.recipient], row[MailOutbox.subject], row[MailOutbox.body])
        }
    }
    override suspend fun acknowledgeMail(tokenHash: String) = query { MailOutbox.deleteWhere { hash eq tokenHash }; Unit }
    override suspend fun pruneExpired(now: Long) = query {
        Sessions.deleteWhere { expiresAt lessEq now }
        Flows.deleteWhere { expiresAt lessEq now }
        Devices.deleteWhere { expiresAt lessEq now }
        Challenges.deleteWhere { expiresAt lessEq now }
        Unit
    }
    override suspend fun createDeviceAttempt(device: OAuthDevice, now: Long) = query {
        Devices.deleteWhere { expiresAt lessEq now }
        Devices.insert { it[id] = device.id; it[secretHash] = device.secretHash; it[codeHash] = device.codeHash; it[provider] = device.provider; it[expiresAt] = device.expiresAt }
        Unit
    }
    override suspend fun deviceAttempt(id: String, now: Long): OAuthDevice? = query {
        Devices.selectAll().where { (Devices.id eq id) and (Devices.expiresAt greater now) }.singleOrNull()?.let {
            OAuthDevice(it[Devices.id], it[Devices.secretHash], it[Devices.codeHash], it[Devices.provider], it[Devices.expiresAt])
        }
    }
    override suspend fun stageDeviceAttempt(id: String, ownerId: String, approvalHash: String, now: Long): Boolean = query {
        Devices.update({ (Devices.id eq id) and (Devices.expiresAt greater now) and Devices.ownerId.isNull() }) {
            it[Devices.ownerId] = ownerId; it[Devices.approvalHash] = approvalHash
        } == 1
    }
    override suspend fun approveDeviceAttempt(id: String, approvalHash: String, codeHash: String, now: Long): Boolean = query {
        val row = Devices.selectAll().where { (Devices.id eq id) and (Devices.expiresAt greater now) and (Devices.failedAttempts less 5) and (Devices.approvalHash eq approvalHash) }.forUpdate().singleOrNull() ?: return@query false
        if (row[Devices.ownerId] == null || row[Devices.approved]) return@query false
        if (row[Devices.codeHash] != codeHash) {
            Devices.update({ Devices.id eq id }) { it[failedAttempts] = row[Devices.failedAttempts] + 1 }
            return@query false
        }
        Devices.update({ Devices.id eq id }) { it[approved] = true; it[Devices.approvalHash] = null }
        true
    }
    override suspend fun consumeDeviceAttempt(id: String, secretHash: String, now: Long): DevicePoll = query {
        val row = Devices.selectAll().where { (Devices.id eq id) and (Devices.secretHash eq secretHash) }.forUpdate().singleOrNull() ?: return@query DevicePoll.Expired
        if (row[Devices.expiresAt] <= now || row[Devices.failedAttempts] >= 5) {
            Devices.deleteWhere { Devices.id eq id }
            return@query DevicePoll.Expired
        }
        if (!row[Devices.approved]) return@query DevicePoll.Pending
        val ownerId = row[Devices.ownerId] ?: return@query DevicePoll.Pending
        Devices.deleteWhere { Devices.id eq id }
        DevicePoll.Approved(ownerId)
    }
    override suspend fun export(ownerId: String): TreasurySnapshot = query { Records.selectAll().where { Records.ownerId eq ownerId }.map { it.record() }.decodeSnapshot() }
    override suspend fun pull(ownerId: String, after: Long, limit: Int): SyncPage = query {
        val rows = Records.selectAll().where { (Records.ownerId eq ownerId) and (Records.serverUpdatedAt greater after) }.orderBy(Records.serverUpdatedAt to SortOrder.ASC).limit(limit + 1).toList()
        val page = rows.take(limit)
        SyncPage(page.map { it.record() }.decodeSnapshot(), cursorString(page.lastOrNull()?.get(Records.serverUpdatedAt) ?: after), rows.size > limit)
    }
    override suspend fun push(ownerId: String, incoming: TreasurySnapshot, now: Long): PushResult = query {
        val owner = lockOwner(ownerId)
        val old = Records.selectAll().where { Records.ownerId eq ownerId }.map { it.record() }.associateBy { it.id }
        val merged = mergeForServer(old.values.toList().decodeSnapshot(), incoming, ownerId, now)
        var stamp = maxOf(now, owner[Users.lastChange])
        val mergedRecords = merged.encodeRecords()
        if (mergedRecords.sumOf { it.payload.toByteArray(Charsets.UTF_8).size.toLong() } > 16_777_216)
            invalid("Account data exceeds the 16 MiB storage limit")
        val changes = mergedRecords.filter { old[it.id] != it }
        changes.forEach { record ->
            stamp = Math.addExact(stamp, 1)
            if (record.id in old) {
                Records.update({ (Records.ownerId eq ownerId) and (Records.id eq record.id) }) {
                    it[payload] = record.payload; it[serverUpdatedAt] = stamp
                }
            } else {
                Records.insert { it[Records.ownerId] = ownerId; it[id] = record.id; it[kind] = record.kind; it[payload] = record.payload; it[serverUpdatedAt] = stamp }
            }
        }
        if (changes.isNotEmpty()) Users.update({ Users.id eq ownerId }) { it[lastChange] = stamp }
        // Return authoritative versions of all submitted IDs, including records that lost LWW.
        val requestedIds = incoming.encodeRecords().map { it.id }.toSet()
        PushResult(mergedRecords.filter { it.id in requestedIds }.decodeSnapshot())
    }
    override suspend fun eraseAccount(ownerId: String) = query {
        lockOwner(ownerId)
        Sessions.deleteWhere { Sessions.ownerId eq ownerId }
        Records.deleteWhere { Records.ownerId eq ownerId }
        Identities.deleteWhere { Identities.ownerId eq ownerId }
        Users.deleteWhere { Users.id eq ownerId }
        Unit
    }
    override fun close() { pool?.close() }
}

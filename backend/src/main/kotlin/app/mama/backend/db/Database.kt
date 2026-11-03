package app.mama.backend.db

import java.sql.Connection
import java.sql.DriverManager

/**
 * Minimal durable-storage seam over JDBC. The Ktor backend owns its OWN schema
 * (`trusted_exit`) and must NOT touch FastAPI's identity/session tables. A real
 * deployment points this at a dedicated Ktor DB role; tests point it at an
 * ephemeral PostgreSQL in CI. No connection pool dependency is pulled in here —
 * each operation opens its own connection, which is enough for the test-only
 * increment; production should front this with a pool (documented).
 */
interface Db {
    /** Opens a new connection (caller closes). */
    fun open(): Connection

    /** Runs [block] in a single transaction (commit on success, rollback on throw). */
    fun <T> tx(block: (Connection) -> T): T {
        open().use { c ->
            val prevAuto = c.autoCommit
            c.autoCommit = false
            try {
                val r = block(c)
                c.commit()
                return r
            } catch (e: Throwable) {
                runCatching { c.rollback() }
                throw e
            } finally {
                runCatching { c.autoCommit = prevAuto }
            }
        }
    }
}

/** DriverManager-backed [Db]. [url] is a JDBC URL (e.g. jdbc:postgresql://host:5432/db). */
class JdbcDb(
    private val url: String,
    private val user: String?,
    private val password: String?,
) : Db {
    override fun open(): Connection =
        if (user != null) DriverManager.getConnection(url, user, password)
        else DriverManager.getConnection(url)

    /** Creates the schema/tables if absent. Idempotent; safe to run on every boot. */
    fun migrate() {
        open().use { c ->
            c.createStatement().use { st ->
                SCHEMA_SQL.split(";").map { it.trim() }.filter { it.isNotEmpty() }.forEach { st.execute(it) }
            }
        }
    }

    companion object {
        val SCHEMA_SQL = """
            CREATE SCHEMA IF NOT EXISTS trusted_exit;

            CREATE TABLE IF NOT EXISTS trusted_exit.challenge (
              id             TEXT PRIMARY KEY,
              jti            TEXT NOT NULL UNIQUE,
              user_id        TEXT NOT NULL,
              series_id      TEXT NOT NULL,
              period_id      TEXT NOT NULL,
              device_id      TEXT NOT NULL,
              contact_id     TEXT NOT NULL,
              salt           TEXT NOT NULL,
              code_hash      TEXT NOT NULL,
              attempts_left  INT  NOT NULL,
              status         TEXT NOT NULL,
              issued_at      BIGINT NOT NULL,
              expires_at     BIGINT NOT NULL,
              cooldown_until BIGINT,
              sms_ref        TEXT,
              send_attempts  INT NOT NULL DEFAULT 0
            );

            CREATE TABLE IF NOT EXISTS trusted_exit.jti_ledger (
              jti         TEXT PRIMARY KEY,
              redeemed_at BIGINT NOT NULL
            );

            CREATE TABLE IF NOT EXISTS trusted_exit.rate_counter (
              scope        TEXT   NOT NULL,
              window_start BIGINT NOT NULL,
              count        INT    NOT NULL,
              PRIMARY KEY (scope, window_start)
            );
        """.trimIndent()
    }
}

package mlokis.tws

import de.mkammerer.argon2.Argon2Factory
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import arc.util.CommandHandler
import de.mkammerer.argon2.Argon2
import mindustry.game.EventType
import mindustry.gen.Player
import mindustry.mod.Plugin
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.Statement
import java.sql.ResultSet
import java.sql.SQLException
import kotlin.random.Random
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

const val BUG_MSG = "bug.msg"

private class Queryes(val connection: Connection) {
    val getLogin = initStmt("SELECT * FROM login WHERE uuid = ?")
    val addLogin = initStmt("INSERT INTO login (uuid, name) VALUES (?, ?)")
    val removeLogin = initStmt("DELETE FROM login WHERE uuid = ?")
    val getUser = initStmt("SELECT * FROM user WHERE name = ?")
    val getUserByUuid = initStmt(
        "SELECT * FROM user JOIN login ON" +
                " user.name = login.name WHERE login.uuid = ?"
    )
    val getRank = initStmt(
        "SELECT rank FROM user JOIN login ON" +
                " user.name = login.name WHERE login.uuid = ?"
    )
    val setRank = initStmt("UPDATE user SET rank = ? WHERE name = ?")
    val createUser = initStmt("INSERT INTO user (name, password_hash) VALUES (?, ?)")
    val deleteUser = initStmt("DELETE FROM user WHERE name = ?")
    val getPasswordHash = initStmt("SELECT password_hash FROM user WHERE name = ?")
    val getPlayerNameByDiscordId = initStmt("SELECT name FROM user WHERE discord_id = ?")
    val getUserDiscordId = initStmt("SELECT discord_id FROM user WHERE name = ?")
    val setDiscordId = initStmt("UPDATE user SET discord_id = ? WHERE name = ?")
    val isGriefer = initStmt("SELECT * FROM griefer WHERE ban_key = ? OR ban_key = ?")
    val markGriefer = initStmt("INSERT INTO griefer (ban_key) VALUES (?)")
    val unmarkGriefer = initStmt("DELETE FROM griefer WHERE ban_key = ? OR ban_key = ?")
    val addFailedTestSession = initStmt("INSERT INTO failed_test_sessions (name) VALUES (?)")
    val hasFailedTestSession =
        initStmt("SELECT * FROM failed_test_sessions WHERE name = ? and unixepoch() - happened_at < ? * 1000 * 60 * 60")

    fun initStmt(str: String): PreparedStatement = connection.prepareStatement(str)
}

class DbReactor(val config: Config) {
    private val qs: Queryes
    private val connection: Connection
    private val hasher: Argon2 = Argon2Factory.create()

    init {
        Class.forName("org.sqlite.JDBC")
        connection = DriverManager.getConnection("jdbc:sqlite:sample.db")!!
        val statement = connection.createStatement()!!

        val sql = object {}.javaClass
            .getResource("/schema.sql")!!
            .readText()
            .split(";")
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        for (stmt in sql) {
            statement.executeUpdate(stmt)
        }

        qs = Queryes(connection)
    }

    fun setDiscordId(name: String, id: String) {
        qs.setDiscordId.setString(1, id)
        qs.setDiscordId.setString(2, name)
        qs.setDiscordId.executeUpdate()
    }

    fun getUserDiscordId(name: String): String? {
        qs.getUserDiscordId.setString(1, name)
        val resultSet = qs.getUserDiscordId.executeQuery()
        if (resultSet.next()) return resultSet.getString("discord_id")
        return null
    }

    fun getPlayerNameByDiscordId(id: String): String? {
        qs.getPlayerNameByDiscordId.setString(1, id)
        val resultSet = qs.getPlayerNameByDiscordId.executeQuery()
        if (resultSet.next()) return resultSet.getString("name")
        return null
    }

    fun addFailedTestSession(name: String) {
        qs.addFailedTestSession.setString(1, name)
        qs.addFailedTestSession.executeUpdate()
    }

    fun hasFailedTestSession(name: String, timeout: Int): Long? {
        qs.hasFailedTestSession.setString(1, name)
        qs.hasFailedTestSession.setInt(2, timeout)
        val resultSet = qs.hasFailedTestSession.executeQuery()
        if (resultSet.next()) {
            return resultSet.getLong("happened_at") * 1000
        }
        return null
    }

    fun isGriefer(player: Player): Boolean {
        qs.isGriefer.setString(1, player.uuid())
        qs.isGriefer.setString(1, player.ip())
        val resultSet = qs.isGriefer.executeQuery()
        return resultSet.next()
    }

    fun markGriefer(player: Player) {
        qs.markGriefer.setString(1, player.uuid())
        try {
            qs.markGriefer.executeUpdate()
        } catch (e: SQLException) {
            println("error marking griefer, (could be duplicate entry)")
            e.printStackTrace()
        }
        qs.markGriefer.setString(1, player.ip())
        try {
            qs.markGriefer.executeUpdate()
        } catch (e: SQLException) {
            println("error marking griefer, (could be duplicate entry)")
            e.printStackTrace()
        }
        player.stateKick("marked-griefer")
        println("marked ${player.name} as griefer")
    }

    fun unmarkGriefer(player: Player) {
        qs.unmarkGriefer.setString(1, player.uuid())
        qs.unmarkGriefer.setString(1, player.ip())
        qs.unmarkGriefer.executeUpdate()
        println("pardoned ${player.name}")
    }

    fun getRank(player: Player): String {
        qs.getRank.setString(1, player.uuid())
        val resultSet = qs.getRank.executeQuery()
        if (!resultSet.next()) return Rank.GUEST
        return resultSet.getString("rank") ?: Rank.GUEST
    }

    fun getPlayerNameByUuid(uuid: String): String? {
        qs.getLogin.setString(1, uuid)
        val resultSet = qs.getLogin.executeQuery()
        if (!resultSet.next()) return null
        return resultSet.getString("name")
    }

    fun playerExists(name: String): Boolean {
        qs.getUser.setString(1, name)
        val resultSet = qs.getUser.executeQuery()
        return resultSet.next()
    }

    fun setRank(name: String, rank: String) {
        qs.setRank.setString(1, rank)
        qs.setRank.setString(2, name)
        qs.setRank.executeUpdate()
    }

    private val recentIps = mutableSetOf<String>()

    fun tryBanIp(player: Player) {
        val key = System.getenv("VPNAPI_KEY") ?: return

        // don't waste api calls on the same ip
        if (player.ip() in recentIps) return
        recentIps.add(player.ip())

        @Serializable
        class Security(val vpn: Boolean, val tor: Boolean, val proxy: Boolean, val relay: Boolean)

        @Serializable
        data class Response(val security: Security)


        java.net.http.HttpClient.newHttpClient()
            .sendAsync(
                java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create("https://vpnapi.io/api/${player.ip()}?key=$key"))
                    .GET().build(), java.net.http.HttpResponse.BodyHandlers.ofString()
            )
            .thenApply { it.body() }
            .thenAccept { body ->
                try {
                    val response = Json {
                        ignoreUnknownKeys = true
                    }.decodeFromString<Response>(body)
                    if (response.security.vpn || response.security.tor ||
                        response.security.proxy || response.security.relay
                    ) {
                        arc.Core.app.post {
                            markGriefer(player)
                            player.markKick("using a VPN/TOR/proxy/relay")
                            println("banned ${player.name} for using a VPN/TOR/proxy/relay")
                        }
                    }
                } catch (e: Exception) {
                    println("error checking vpn status: ${body}")
                    e.printStackTrace()
                }
            }
    }

    fun loadPlayer(player: Player): String? {
        if (isGriefer(player)) {
            val griferRank = config.getRank(player, Rank.GRIEFER) ?: return BUG_MSG
            player.name = "${player.name}[${griferRank.color}]<griefer>[]"
            return "hello.griefer"
        }

        qs.getLogin.setString(1, player.uuid())
        var resultSet = qs.getLogin.executeQuery();

        if (!resultSet.next()) {
            tryBanIp(player)
            return "hello.guest"
        }

        val name = resultSet.getString("name")

        qs.getUser.setString(1, name)
        resultSet = qs.getUser.executeQuery();

        if (!resultSet.next()) {
            println("ERROR: player ${player.name} is not registered but he has a login")
            return BUG_MSG
        }

        val rank = resultSet.getString("rank")
        val rankObj = config.getRank(player, rank) ?: return BUG_MSG
        player.name = "$name${rankObj.display(rank)}"

        return null
    }

    fun loginPlayer(player: Player, name: String, password: String): String? {
        if (getPlayerNameByUuid(player.uuid()) != null) {
            return "login.already-logged-in"
        }

        qs.getPasswordHash.setString(1, name)
        val resultSet = qs.getPasswordHash.executeQuery()
        if (!resultSet.next()) {
            println("player ${player.name} is not registered but is trying to login")
            return "login.register-first"
        }

        val passwordHash = resultSet.getString("password_hash")
        if (passwordHash == null) {
            println("ERROR: player ${name} password hash is null")
            return BUG_MSG
        }

        @Suppress("DEPRECATION")
        if (!hasher.verify(passwordHash, password)) {
            println("player ${name} tried to login with wrong password")
            return "login.wrong-password"
        }

        qs.addLogin.setString(1, player.uuid())
        qs.addLogin.setString(2, name)
        qs.addLogin.executeUpdate()

        println("player ${name} logged in")
        player.stateKick("login.success")

        return null
    }

    fun logoutPlayer(player: Player): String? {
        qs.removeLogin.setString(1, player.uuid())
        val count = qs.removeLogin.executeUpdate()
        if (count == 0) return "logout.not-logged-in"
        return null
    }

    fun registerPlayer(name: String, password: String): String? {
        val passwordHash = computePasswordHash(password)

        try {
            qs.createUser.setString(1, name)
            qs.createUser.setString(2, passwordHash)
            qs.createUser.executeUpdate()
        } catch (e: SQLException) {
            println("error registering player ${name}")
            e.printStackTrace()
            return "register.already-registered"
        }

        println("player ${name} registered")

        return null
    }

    fun status(player: Player): String {
        qs.getLogin.setString(1, player.uuid())
        var resultSet = qs.getLogin.executeQuery()
        if (!resultSet.next()) {
            return player.fmt("status.not-logged-in")
        }

        qs.getUserByUuid.setString(1, player.uuid())
        resultSet = qs.getUser.executeQuery()

        if (!resultSet.next()) return BUG_MSG

        val rank = resultSet.getString("rank")

        val joinedAt = resultSet.getLong("joined_at")
        val fmtJoinedAt = displayTime(Instant.ofEpochSecond(joinedAt))

        return player.fmt("status.listing", "name" to player.name, "rank" to rank, "joined-at" to fmtJoinedAt)
    }

    fun displayTime(ins: Instant): String {
        val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.systemDefault())
        return formatter.format(ins)
    }

    fun computePasswordHash(password: String): String {
        @Suppress("DEPRECATION")
        return hasher.hash(10, 65546, 1, password)
    }
}


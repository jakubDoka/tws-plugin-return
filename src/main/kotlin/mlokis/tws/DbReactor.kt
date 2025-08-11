package mlokis.tws

import de.mkammerer.argon2.Argon2Factory
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


private class Queryes(val connection: Connection) {
    val getLogin = initStmt("SELECT * FROM login WHERE uuid = ?")
    val addLogin = initStmt("INSERT INTO login (uuid, name) VALUES (?, ?)")
    val removeLogin = initStmt("DELETE FROM login WHERE uuid = ?")
    val getUser = initStmt("SELECT * FROM user WHERE name = ?")
    val createUser = initStmt("INSERT INTO user (name, password_hash) VALUES (?, ?)")
    val deleteUser = initStmt("DELETE FROM user WHERE name = ?")
    val getPasswordHash = initStmt("SELECT password_hash FROM user WHERE name = ?")

    fun initStmt(str: String): PreparedStatement = connection.prepareStatement(str)
}

class DbReactor() {
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

    fun loadPlayer(player: Player): String? {
        qs.getLogin.setString(1, player.uuid())
        var resultSet = qs.getLogin.executeQuery();

        if (!resultSet.next()) return "[yellow]You are not logged-in/registered yet," +
                " consider doing so with /login or /register command."

        val name = resultSet.getString("name")

        qs.getUser.setString(1, name)
        resultSet = qs.getUser.executeQuery();

        if (!resultSet.next()) {
            println("ERROR: player ${player.name} is not registered but he has a login")
            return "[red]Bug occured, please report this to the devs."
        }

        player.name = name
        return null
    }

    fun loginPlayer(player: Player, name: String, password: String): String? {
        qs.getPasswordHash.setString(1, name)
        val resultSet = qs.getPasswordHash.executeQuery()
        if (!resultSet.next()) {
            println("player ${player.name} is not registered but is trying to login")
            return "You need to register first."
        }

        val passwordHash = resultSet.getString("password_hash")
        if (passwordHash == null) {
            println("ERROR: player ${name} password hash is null")
            return "Bug occured, please report this to the devs."
        }

        @Suppress("DEPRECATION")
        if (!hasher.verify(passwordHash, password)) {
            println("player ${name} tried to login with wrong password")
            return "Wrong password."
        }

        qs.addLogin.setString(1, player.uuid())
        qs.addLogin.setString(2, name)
        qs.addLogin.executeUpdate()

        println("player ${name} logged in")
        player.name = name

        return null
    }

    fun logoutPlayer(player: Player): String? {
        qs.removeLogin.setString(1, player.uuid())
        val count = qs.removeLogin.executeUpdate()
        if (count == 0) return "[red]You are not logged in so this does nothing!"
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
            return "Your name is already used by another player."
        }

        println("player ${name} registered")

        return null
    }

    fun status(player: Player): String {
        qs.getLogin.setString(1, player.uuid())
        var resultSet = qs.getLogin.executeQuery()
        if (!resultSet.next()) {
            return "[yellow]You are not logged-in/registered yet," +
                    " consider doing so with /login or /register command."
        }

        qs.getUser.setString(1, player.name)
        resultSet = qs.getUser.executeQuery()

        var joinedAt = resultSet.getLong("joined_at")

        val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.systemDefault())

        val fmtJoinedAt = formatter.format(Instant.ofEpochSecond(joinedAt))

        return "[green]You are logged in as ${player.name}!\n" +
                "[white]Your joined at ${fmtJoinedAt}!"
    }

    fun computePasswordHash(password: String): String {
        @Suppress("DEPRECATION")
        return hasher.hash(10, 65546, 1, password)
    }
}

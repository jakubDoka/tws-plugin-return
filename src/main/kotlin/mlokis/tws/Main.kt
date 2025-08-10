package mlokis.tws

import de.mkammerer.argon2.Argon2Factory
import arc.util.CommandHandler
import de.mkammerer.argon2.Argon2
import mindustry.game.EventType
import mindustry.gen.Player
import mindustry.mod.Plugin
import mlokis.tws.commands.client.RegisterClientCommands
import mlokis.tws.commands.server.RegisterServerCommands
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.Statement
import java.sql.ResultSet

@Suppress("unused")
class Main : Plugin() {
    var db = DbReactor()

    override fun init() {
        arc.Events.on(EventType.PlayerLeave::class.java) { event ->
            db.unloadPlayer(event.player)
        }

        arc.Events.on(EventType.PlayerConnect::class.java) { event ->
            val playerWrapper = db.loadPlayer(event.player)
            if (playerWrapper.passwordHash == null) {
                event.player.sendMessage("[yellow] You are not registered/logged in yet, consider doing so.")
            }
        }
    }

    override fun registerServerCommands(handler: CommandHandler) {
        RegisterServerCommands.loadAll(handler)
    }

    override fun registerClientCommands(handler: CommandHandler) {
        RegisterClientCommands.loadAll(handler)
    }
}

class DbReactor() {
    val connection: Connection
    val statement: Statement

    val getUser: PreparedStatement
    val createUser: PreparedStatement
    val deleteUser: PreparedStatement
    val saveUser: PreparedStatement

    val playerCache = mutableMapOf<Player, PlayerWrapper>()

    val hasher: Argon2 = Argon2Factory.create()

    init {
        Class.forName("org.sqlite.JDBC")
        connection = DriverManager.getConnection("jdbc:sqlite:sample.db")!!
        statement = connection.createStatement()!!
        getUser = connection.prepareStatement("SELECT * FROM user WHERE name = ? AND uuid = ?")
        createUser = connection.prepareStatement("INSERT INTO user (name, uuid) VALUES (?, ?)")
        deleteUser = connection.prepareStatement("DELETE FROM user WHERE name = ?")
        saveUser = connection.prepareStatement("UPDATE user SET uuid = ?, password_hash = ? WHERE name = ?")

        val sql = object {}.javaClass
            .getResource("/schema.sql")!!
            .readText()
            .split(";")
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        for (stmt in sql) {
            statement.executeUpdate(stmt)
        }
    }

    fun unloadPlayer(player: Player) {
        val playerWrapper = playerCache.remove(player) ?: return
        if (playerWrapper.passwordHash == null) {
            println("deleted quest user ${player.name}")
            deleteUser.executeUpdate(player.name)
        } else {
            saveUser.setString(1, playerWrapper.uuids.joinToString(" "))
            saveUser.setString(2, playerWrapper.passwordHash)
            saveUser.setString(3, player.name)
            saveUser.executeUpdate()
        }
    }

    fun loadPlayer(player: Player): PlayerWrapper {
        val cached = playerCache[player]
        if (cached != null) return cached
        val playerWrapper = loadPlayerUncached(player)
        playerCache[player] = playerWrapper
        return playerWrapper
    }

    fun loadPlayerUncached(player: Player): PlayerWrapper {
        getUser.setString(1, player.name)
        getUser.setString(2, player.uuid())
        var resultSet = getUser.executeQuery();


        if (resultSet.next()) {
            return PlayerWrapper(player, resultSet)
        }

        val uuid = player.uuid() ?: return PlayerWrapper(player, null)
        createUser.setString(1, player.name)
        createUser.setString(2, uuid)
        createUser.executeUpdate()

        println("created new user ${player.name}")

        return PlayerWrapper(player, null)
    }

    fun loginPlayer(player: Player, password: String): String? {
        val playerWrapper = loadPlayer(player)
        if (playerWrapper.passwordHash == null) {
            println("player ${player.name} is not registered but is trying to login")
            return "you need to register first"
        }

        @Suppress("DEPRECATION")
        if (!hasher.verify(playerWrapper.passwordHash, password)) {
            println("player ${player.name} tried to login with wrong password")
            return "wrong password"
        }

        println("player ${player.name} logged in")

        return null
    }

    fun registerPlayer(player: Player, name: String, password: String): String? {
        val playerWrapper = loadPlayer(player)

        if (playerWrapper.passwordHash != null) {
            println("player ${player.name} tried to register but is already registered")
            return "you are already registered"
        }

        val hash = computePasswordHash(name, password)

        createUser.setString(1, name)
        createUser.setString(2, hash)
        createUser.executeUpdate()

        println("player ${player.name} registered")

        return null
    }

    fun computePasswordHash(name: String, password: String): String {
        @Suppress("DEPRECATION")
        return hasher.hash(400, 65546, 1, password)
    }
}

class PlayerWrapper(player: Player, resultSet: ResultSet?) {
    val uuids: HashSet<String> = resultSet?.getString("uuid")
        ?.split(",")?.toHashSet() ?: HashSet()
    val passwordHash: String? = resultSet?.getString("password_hash")

    init {
        player.name = resultSet?.getString("name") ?: player.name
    }
}


package mlokis.tws

import de.mkammerer.argon2.Argon2Factory
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import arc.util.CommandHandler
import de.mkammerer.argon2.Argon2
import mindustry.game.EventType
import mindustry.gen.Player
import mindustry.mod.Plugin
import arc.util.Log.*
import mindustry.Vars
import mindustry.gen.Groups
import mindustry.net.Administration
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
    // LOGIN
    val getLogin = initStmt("SELECT * FROM login WHERE uuid = ?")
    val addLogin = initStmt("INSERT INTO login (uuid, name) VALUES (?, ?)")
    val removeLogin = initStmt("DELETE FROM login WHERE uuid = ?")

    // USER
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
    val setPlayerName = initStmt("UPDATE user SET name = ? WHERE name = ?")
    val changePassword = initStmt("UPDATE user SET password_hash = ? WHERE name = ?")

    // PLAYER STATS
    val addBlockPlaced = initStatIncStmt("blocks_placed")
    val addBlockBroken = initStatIncStmt("blocks_broken")
    val addPlayTime = initStatAddStmt("play_time")
    val addMessagesSent = initStatIncStmt("messages_sent")
    val addCommandsExecuted = initStatIncStmt("commands_executed")
    val addEnemiesKilled = initStatAddStmt("enemies_killed")
    val addWavesSurvived = initStatIncStmt("waves_survived")
    val addAfkTime = initStatAddStmt("afk_time")
    val addBlocksDestroyed = initStatIncStmt("blocks_destroyed")
    val addDeaths = initStatIncStmt("deaths")


    // APPEAL
    val markAppealed = initStmt("INSERT INTO appeal (appeal_key) VALUES (?)")
    val hasAppealed = initStmt("SELECT * FROM appeal WHERE appeal_key = ?")


    // GRIEFER
    val isGriefer = initStmt("SELECT * FROM griefer WHERE ban_key = ?")
    val markGriefer = initStmt("INSERT INTO griefer (ban_key) VALUES (?)")
    val unmarkGriefer = initStmt("DELETE FROM griefer WHERE ban_key = ? OR ban_key = ?")

    // TEST
    val addFailedTestSession = initStmt("INSERT INTO failed_test_sessions (name) VALUES (?)")
    val hasFailedTestSession =
        initStmt("SELECT * FROM failed_test_sessions WHERE name = ? and unixepoch() - happened_at < ? * 1000 * 60 * 60")

    // MAP
    val ensureMapScore = initStmt("INSERT INTO map_score (name) VALUES (?) ON CONFLICT(name) DO NOTHING")
    val getMapScore = initStmt("SELECT * FROM aggregated_map_score WHERE name = ?")
    val incMapSwitches = initStmt("UPDATE map_score SET switches = switches + 1 WHERE name = ?")

    val assertLatestGame = "id = (SELECT MAX(id) FROM game)"

    var addGame = initStmt("INSERT INTO game (map) VALUES (?)")
    val getGameStartTime = initStmt("SELECT started_at FROM game WHERE $assertLatestGame")
    val deleteCorruptedLeftoverGame = initStmt("DELETE FROM game WHERE map != ? AND $assertLatestGame")
    val isGameInProgress =
        initStmt("SELECT EXISTS(SELECT 1 FROM game WHERE map = ? AND $assertLatestGame AND finished_at = 0)")
    val finishGame =
        initStmt("UPDATE game SET finished_at = unixepoch(), wave = ?, won = ? WHERE map = ? AND $assertLatestGame")
    val updateGamePeakPlayers =
        initStmt("UPDATE game SET peak_players = max(peak_players, ?) WHERE map = ? AND $assertLatestGame")

    val addTest = initStmt("INSERT INTO test (id) VALUES (?)")

    fun initStatIncStmt(propName: String): PreparedStatement =
        initStmt("UPDATE user SET $propName = $propName + 1 WHERE name = ?")

    fun initStatAddStmt(propName: String): PreparedStatement =
        initStmt("UPDATE user SET $propName = $propName + ? WHERE name = ?")

    fun initStmt(str: String): PreparedStatement = try {
        connection.prepareStatement(str)
    } catch (e: SQLException) {
        err("error preparing statement: $str")
        e.printStackTrace()
        connection.prepareStatement("SELECT 1")
    }
}

data class MapScore(
    val switches: Int,
    val maxGametime: Long,
    val minGametime: Long,
    val totalGametime: Long,
    val maxWave: Int,
    val maxPeakPlayers: Int,
    val totalGames: Int,
)

@Serializable
data class PlayerScore(
    val blocksBroken: Long = 0,
    val blocksPlaced: Long = 0,
    val playTime: Long = 0,
    val messagesSent: Long = 0,
    val commandsExecuted: Long = 0,
    val enemiesKilled: Long = 0,
    val wavesSurvived: Long = 0,
    val afkTime: Long = 0,
    val blocksDestroyed: Long = 0,
    val deaths: Long = 0,
) {
    fun display(player: Player, name: String): String {
        return player.fmt(
            "player-stats.table",
            "name" to name,
            "blocks-broken" to blocksBroken,
            "blocks-placed" to blocksPlaced,
            "play-time" to playTime.displayTime(),
            "messages-sent" to messagesSent,
            "commands-executed" to commandsExecuted,
            "enemies-killed" to enemiesKilled,
            "waves-survived" to wavesSurvived,
            "afk-time" to afkTime.displayTime(),
            "blocks-destroyed" to blocksDestroyed,
            "deaths" to deaths,
        )
    }

    fun displayReference(player: Player, name: String, score: PlayerScore): String {
        fun displayOutOf(score: Long, max: Long): String =
            "[${if (score >= max) "green" else "gray"}]${score}[]/${max}"

        fun displayTimeOutOf(score: Long, max: Long): String =
            "[${if (score >= max) "green" else "gray"}]${score.displayTime()}[]/${max.displayTime()}"

        return player.fmt(
            "player-stats.table",
            "name" to name,
            "blocks-broken" to displayOutOf(score.blocksBroken, blocksBroken),
            "blocks-placed" to displayOutOf(score.blocksPlaced, blocksPlaced),
            "play-time" to displayTimeOutOf(score.playTime, playTime),
            "messages-sent" to displayOutOf(score.messagesSent, messagesSent),
            "commands-executed" to displayOutOf(score.commandsExecuted, commandsExecuted),
            "enemies-killed" to displayOutOf(score.enemiesKilled, enemiesKilled),
            "waves-survived" to displayOutOf(score.wavesSurvived, wavesSurvived),
            "afk-time" to displayTimeOutOf(score.afkTime, afkTime),
            "blocks-destroyed" to displayOutOf(score.blocksDestroyed, blocksDestroyed),
            "deaths" to displayOutOf(score.deaths, deaths),
        )
    }

    fun isObtained(playerStats: PlayerScore): Boolean {
        return blocksBroken <= playerStats.blocksBroken &&
                blocksPlaced <= playerStats.blocksPlaced &&
                playTime <= playerStats.playTime &&
                messagesSent <= playerStats.messagesSent &&
                commandsExecuted <= playerStats.commandsExecuted &&
                enemiesKilled <= playerStats.enemiesKilled &&
                wavesSurvived <= playerStats.wavesSurvived &&
                afkTime <= playerStats.afkTime &&
                blocksDestroyed <= playerStats.blocksDestroyed &&
                deaths <= playerStats.deaths
    }
}

class DbReactor(config: Config) {
    private val qs: Queryes
    private val connection: Connection
    private val hasher: Argon2 = Argon2Factory.create()

    init {
        Class.forName("org.sqlite.JDBC")
        connection = DriverManager
            .getConnection("jdbc:sqlite:${config.globalConfig.dbPath}")!!
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

    fun markAppealed(info: Administration.PlayerInfo) {
        qs.markAppealed.setString(1, info.id)
        qs.markAppealed.executeUpdate()

        for (ip in info.ips) {
            qs.markAppealed.setString(1, ip)
            qs.markAppealed.executeUpdate()
        }
    }

    fun hasAppealedKey(key: String): Boolean {
        qs.hasAppealed.setString(1, key)
        return qs.hasAppealed.executeQuery().use { it.next() }
    }

    fun hasAppealed(info: Administration.PlayerInfo): Boolean {
        qs.hasAppealed.setString(1, info.id)
        if (qs.hasAppealed.executeQuery().use { it.next() }) return true

        for (ip in info.ips) {
            qs.hasAppealed.setString(1, ip)
            if (qs.hasAppealed.executeQuery().use { it.next() }) return true
        }

        return false
    }

    fun addBlockPlaced(name: String) {
        qs.addBlockPlaced.setString(1, name)
        qs.addBlockPlaced.executeUpdate()
    }

    fun addBlockBroken(name: String) {
        qs.addBlockBroken.setString(1, name)
        qs.addBlockBroken.executeUpdate()
    }

    fun addPlayTime(name: String, time: Long) {
        qs.addPlayTime.setLong(1, time)
        qs.addPlayTime.setString(2, name)
        qs.addPlayTime.executeUpdate()
    }

    fun addMessagesSent(name: String) {
        qs.addMessagesSent.setString(1, name)
        qs.addMessagesSent.executeUpdate()
    }

    fun addCommandsExecuted(name: String) {
        qs.addCommandsExecuted.setString(1, name)
        qs.addCommandsExecuted.executeUpdate()
    }

    fun addEnemiesKilled(name: String, count: Int) {
        qs.addEnemiesKilled.setInt(1, count)
        qs.addEnemiesKilled.setString(2, name)
        qs.addEnemiesKilled.executeUpdate()
    }

    fun addWavesSurvived(name: String) {
        qs.addWavesSurvived.setString(1, name)
        qs.addWavesSurvived.executeUpdate()
    }

    fun addAfkTime(name: String, time: Long) {
        qs.addAfkTime.setLong(1, time)
        qs.addAfkTime.setString(2, name)
        qs.addAfkTime.executeUpdate()
    }

    fun addBlocksDestroyed(name: String) {
        qs.addBlocksDestroyed.setString(1, name)
        qs.addBlocksDestroyed.executeUpdate()
    }

    fun addDeaths(name: String) {
        qs.addDeaths.setString(1, name)
        qs.addDeaths.executeUpdate()
    }

    fun getPlayerScore(name: String): PlayerScore? {
        qs.getUser.setString(1, name)
        // TODO: we might be able to reduce the boilerplate
        return qs.getUser.executeQuery().use { rs ->
            if (!rs.next()) return null
            PlayerScore(
                blocksBroken = rs.getLong("blocks_broken"),
                blocksPlaced = rs.getLong("blocks_placed"),
                playTime = rs.getLong("play_time"),
                messagesSent = rs.getLong("messages_sent"),
                commandsExecuted = rs.getLong("commands_executed"),
                enemiesKilled = rs.getLong("enemies_killed"),
                wavesSurvived = rs.getLong("waves_survived"),
                afkTime = rs.getLong("afk_time"),
                blocksDestroyed = rs.getLong("blocks_destroyed"),
                deaths = rs.getLong("deaths"),
            )
        }
    }

    fun ensureMapScore(map: String) {
        qs.ensureMapScore.setString(1, map)
        qs.ensureMapScore.executeUpdate()
    }

    fun incMapSwitches(map: String) {
        ensureMapScore(map)

        qs.incMapSwitches.setString(1, map)
        qs.incMapSwitches.executeUpdate()
    }

    fun getMapScore(map: String): MapScore? {
        qs.getMapScore.setString(1, map)
        return qs.getMapScore.executeQuery().use { rs ->
            if (!rs.next()) return null
            MapScore(
                switches = rs.getInt("switches"),
                maxGametime = rs.getLong("max_gametime"),
                minGametime = rs.getLong("min_gametime"),
                totalGametime = rs.getLong("total_gametime"),
                maxWave = rs.getInt("max_wave"),
                maxPeakPlayers = rs.getInt("max_peak_players"),
                totalGames = rs.getInt("total_games"),
            )
        }
    }

    fun ensureGame(map: String) {
        ensureMapScore(map)

        qs.isGameInProgress.setString(1, map)
        qs.isGameInProgress.executeQuery().use { rs ->
            assert(rs.next())

            if (rs.getInt(1) == 1) return

            qs.addGame.setString(1, map)
            qs.addGame.executeUpdate()
        }
    }

    fun getGameStartTime(): Long {
        qs.getGameStartTime.executeQuery().use { rs ->
            if (!rs.next()) return System.currentTimeMillis()
            return rs.getLong("started_at")
        }
    }

    fun deleteCorruptedLeftoverGame(map: String) {
        qs.deleteCorruptedLeftoverGame.setString(1, map)
        qs.deleteCorruptedLeftoverGame.executeUpdate()
    }

    fun finishGame(map: String, wave: Int, won: Boolean) {
        qs.finishGame.setInt(1, wave)
        qs.finishGame.setInt(2, if (won) 1 else 0)
        qs.finishGame.setString(3, map)
        qs.finishGame.executeUpdate()
    }

    fun updateGamePeakPlayers(map: String, peakPlayers: Int) {
        ensureGame(map)

        qs.updateGamePeakPlayers.setInt(1, peakPlayers)
        qs.updateGamePeakPlayers.setString(2, map)
        qs.updateGamePeakPlayers.executeUpdate()
    }

    fun migrate(sql: String) {
        val statement = connection.createStatement()
        for (stmt in sql.split(";").map { it.trim() }.filter { it.isNotEmpty() }) {
            statement.executeUpdate(stmt)
        }
    }

    fun setDiscordId(name: String, id: String) {
        qs.setDiscordId.setString(1, id)
        qs.setDiscordId.setString(2, name)
        qs.setDiscordId.executeUpdate()
    }

    fun getUserDiscordId(name: String): String? {
        qs.getUserDiscordId.setString(1, name)
        return qs.getUserDiscordId.executeQuery().use { rs ->
            if (rs.next()) return rs.getString("discord_id")
            null
        }
    }

    fun getPlayerNameByDiscordId(id: String): String? {
        qs.getPlayerNameByDiscordId.setString(1, id)
        return qs.getPlayerNameByDiscordId.executeQuery().use { rs ->
            if (rs.next()) return rs.getString("name")
            null
        }
    }

    fun addFailedTestSession(name: String) {
        qs.addFailedTestSession.setString(1, name)
        qs.addFailedTestSession.executeUpdate()
    }

    fun hasFailedTestSession(name: String, timeout: Int): Long? {
        qs.hasFailedTestSession.setString(1, name)
        qs.hasFailedTestSession.setInt(2, timeout)
        return qs.hasFailedTestSession.executeQuery().use { rs ->
            if (rs.next()) {
                return rs.getLong("happened_at") * 1000
            }
            null
        }
    }

    fun isGriefer(player: Administration.PlayerInfo): Boolean {
        for (ip in player.ips) {
            qs.isGriefer.setString(1, ip)
            qs.isGriefer.executeQuery().use { rs ->
                if (rs.next()) return true
            }
        }

        qs.isGriefer.setString(1, player.id)
        qs.isGriefer.executeQuery().use { rs ->
            return rs.next()
        }
    }

    fun markGriefer(player: Administration.PlayerInfo, reason: String) {
        // TODO: we could batch
        qs.markGriefer.setString(1, player.id)
        try {
            qs.markGriefer.executeUpdate()
        } catch (e: SQLException) {
            err("error marking griefer, (could be duplicate entry)")
            e.printStackTrace()
        }

        for (ip in player.ips) {
            qs.markGriefer.setString(1, ip)
            try {
                qs.markGriefer.executeUpdate()
            } catch (e: SQLException) {
                err("error marking griefer, (could be duplicate entry)")
                e.printStackTrace()
            }
        }


        Vars.netServer.admins.unbanPlayerID(player.id)
        for (ip in player.ips)
            Vars.netServer.admins.unbanPlayerIP(ip)

        Groups.player.find { it.uuid() == player.id }?.stateKick("marked-griefer")
        info("marked ${player.id} as griefer: $reason")
    }

    fun unmarkGriefer(player: Administration.PlayerInfo) {
        qs.unmarkGriefer.setString(1, player.id)
        qs.unmarkGriefer.executeUpdate()

        for (ip in player.ips) {
            qs.unmarkGriefer.setString(1, ip)
            qs.unmarkGriefer.executeUpdate()
        }

        Groups.player.find { it.uuid() == player.id }
            ?.stateKick("unmarked-griefer")
        err("unmarked ${player.id}")
    }

    fun setPlayerName(oldName: String, newName: String): String? {
        return try {
            qs.setPlayerName.setString(1, newName)
            qs.setPlayerName.setString(2, oldName)
            qs.setPlayerName.executeUpdate()
            null
        } catch (e: SQLException) {
            err("error changing name")
            e.printStackTrace()
            "change-name.failed"
        }
    }

    fun changePassword(name: String, oldPassword: String, newPassword: String): String? {
        val err = validatePassword(name, oldPassword)
        if (err != null) return err

        val passwordHash = computePasswordHash(newPassword)
        qs.changePassword.setString(1, passwordHash)
        qs.changePassword.setString(2, name)
        qs.changePassword.executeUpdate()

        return null
    }

    fun getRank(player: Player): String {
        if (isGriefer(player.info)) return Rank.GRIEFER
        qs.getRank.setString(1, player.uuid())
        qs.getRank.executeQuery().use { rs ->
            if (!rs.next()) return Rank.GUEST
            return rs.getString("rank") ?: Rank.GUEST
        }
    }

    fun clearCachesFor(player: Player) {
        playerUuidToNameCache.remove(player.uuid())
    }

    val playerUuidToNameCache = mutableMapOf<String, String?>()

    fun getPlayerNameByUuid(uuid: String): String? =
        playerUuidToNameCache.getOrPut(uuid) {
            qs.getLogin.setString(1, uuid)
            qs.getLogin.executeQuery().use { rs ->
                if (!rs.next()) return null


                return rs.getString("name")
            }
        }

    fun loginPlayer(player: Player, name: String, password: String): String? {
        if (getPlayerNameByUuid(player.uuid()) != null) {
            return "login.already-logged-in"
        }

        val err = validatePassword(name, password)
        if (err != null) return err

        qs.addLogin.setString(1, player.uuid())
        qs.addLogin.setString(2, name)
        qs.addLogin.executeUpdate()

        playerUuidToNameCache[player.uuid()] = name

        info("player $name logged in")
        player.stateKick("login.success")

        return null
    }

    fun logoutPlayer(player: Player): String? {
        playerUuidToNameCache.remove(player.uuid())

        qs.removeLogin.setString(1, player.uuid())
        val count = qs.removeLogin.executeUpdate()
        if (count == 0) return "logout.not-logged-in"
        return null
    }


    fun playerExists(name: String): Boolean {
        qs.getUser.setString(1, name)
        qs.getUser.executeQuery().use { rs ->
            return rs.next()
        }
    }

    fun setRank(name: String, rank: String) {
        qs.setRank.setString(1, rank)
        qs.setRank.setString(2, name)
        qs.setRank.executeUpdate()
    }

    fun tryBanIp(player: Player) {
        val key = System.getenv("VPNAPI_KEY") ?: return

        @Serializable
        class Security(val vpn: Boolean, val tor: Boolean, val proxy: Boolean, val relay: Boolean)

        @Serializable
        data class Response(val security: Security)

        var format = Json { ignoreUnknownKeys = true }

        java.net.http.HttpClient.newHttpClient()
            .sendAsync(
                java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create("https://vpnapi.io/api/${player.ip()}?key=$key"))
                    .GET().build(), java.net.http.HttpResponse.BodyHandlers.ofString()
            )
            .thenApply { it.body() }
            .thenAccept { body ->
                try {
                    val response = format.decodeFromString<Response>(body)

                    val mod = if (response.security.vpn) "VPN"
                    else if (response.security.tor) "TOR Connection"
                    else if (response.security.proxy) "Proxy"
                    else if (response.security.relay) "Relay"
                    else null

                    if (mod != null) {
                        arc.Core.app.post {
                            mindustry.Vars.netServer.admins.bannedIPs.add(player.ip())
                            mindustry.Vars.netServer.admins.save()
                            player.kick("You have been banned for using $mod, your IP is banned but you can still play if you disable the $mod.")
                            info("banned ${player.name} for using a VPN/TOR/proxy/relay")
                        }
                    }
                } catch (e: Exception) {
                    err("error checking vpn status: $body")
                    e.printStackTrace()
                }
            }
    }

    fun loadPlayer(player: Player, config: Config): String? {
        if (isGriefer(player.info)) {
            val griferRank = config.getRank(player, Rank.GRIEFER) ?: return BUG_MSG
            player.name = "${player.name}[${griferRank.color}]<griefer>[]"
            return "hello.griefer"
        }

        qs.getLogin.setString(1, player.uuid())
        val name = qs.getLogin.executeQuery().use { rs ->
            if (!rs.next()) {
                tryBanIp(player)
                return "hello.guest"
            }

            rs.getString("name")
        }

        qs.getUser.setString(1, name)
        qs.getUser.executeQuery().use { rs ->
            if (!rs.next()) {
                err("player ${player.name} is not registered but he has a login")
                return BUG_MSG
            }

            val rank = rs.getString("rank")
            val rankObj = config.getRank(player, rank) ?: return BUG_MSG
            player.name = "$name${rankObj.display(rank)}"

            return null
        }
    }

    fun validatePassword(name: String, password: String): String? {
        qs.getPasswordHash.setString(1, name)
        qs.getPasswordHash.executeQuery().use { rs ->
            if (!rs.next()) {
                err("player $name is not registered but is trying to login")
                return "login.register-first"
            }

            val passwordHash = rs.getString("password_hash")
            if (passwordHash == null) {
                err("ERROR: player $name password hash is null")
                return BUG_MSG
            }

            @Suppress("DEPRECATION")
            if (!hasher.verify(passwordHash, password)) {
                info("player $name tried to login with wrong password")
                return "login.wrong-password"
            }

            return null
        }
    }


    fun registerPlayer(name: String, password: String): String? {
        val passwordHash = computePasswordHash(password)

        try {
            qs.createUser.setString(1, name)
            qs.createUser.setString(2, passwordHash)
            qs.createUser.executeUpdate()
        } catch (e: SQLException) {
            info("error registering player ${name}, this should be mostly fine")
            e.printStackTrace()
            return "register.already-registered"
        }

        info("player $name registered")

        return null
    }

    fun status(player: Player): String {
        qs.getLogin.setString(1, player.uuid())
        qs.getLogin.executeQuery().use { rs ->
            if (!rs.next()) {
                return player.fmt("status.not-logged-in")
            }
        }


        qs.getUserByUuid.setString(1, player.uuid())
        qs.getUser.executeQuery().use { rs ->
            if (!rs.next()) return BUG_MSG

            val rank = rs.getString("rank")

            val joinedAt = rs.getLong("joined_at")
            val fmtJoinedAt = displayTime(Instant.ofEpochSecond(joinedAt))

            return player.fmt(
                "status.listing",
                "name" to player.name, "rank" to rank, "joined-at" to fmtJoinedAt
            )
        }
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


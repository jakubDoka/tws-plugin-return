package mlokis.tws

import arc.util.CommandHandler
import kotlinx.serialization.*
import kotlinx.serialization.json.*
import mindustry.game.EventType
import mindustry.gen.Groups
import mindustry.gen.Player
import mindustry.mod.Plugin
import mindustry.world.Tile
import kotlin.math.max


fun Player.sendMissingArgsMessage(args: Array<String>, expected: Int): Boolean {
    if (args.size >= expected) {
        return false
    }
    sendMessage("[red]expected at least $expected arguments, got ${args.size}")
    return true
}

@Suppress("unused")
class Main : Plugin() {
    var db = DbReactor()
    var config = Config.load()
    val grieferSessions = mutableListOf<MarkGrieferSession>()
    var commandHandler: CommandHandler? = null

    class MarkGrieferSession(
        val target: Player,
        val neededVotes: Int,
        val initiator: Player,
        val reason: String,
    ) {
        var yea = mutableMapOf<Player, Int>()
        var nay = mutableMapOf<Player, Int>()
        var timeRemining = 60 * 2

        val yeaVotes: Int
            get() = yea.values.fold(0) { acc, weight -> acc + weight }

        val nayVotes: Int
            get() = nay.values.fold(0) { acc, weight -> acc + weight }

        fun display(idx: Int): String {
            return "${initiator.name}[] wants to mark [pink]${target.name}[] name a griefer," +
                    " [yellow]${neededVotes}[] votes needed" +
                    " (#$idx [green]${yeaVotes}[]y [red]${nayVotes}[]n [yellow]${timeRemining}[]s)"
        }
    }


    override fun init() {
        val permissionTable = mutableMapOf<Tile, BlockProtectionRank>()



        arc.util.Timer.schedule({
            val toRemove = mutableListOf<MarkGrieferSession>()
            val text = StringBuilder()
            for ((i, session) in grieferSessions.withIndex()) {
                text.append(session.display(i))
                text.append("\n")

                session.timeRemining -= 1
                if (session.timeRemining <= 0) {
                    toRemove.add(session)
                }
            }
            grieferSessions.removeAll(toRemove)

            if (text.isEmpty()) {
                mindustry.gen.Call.hideHudText()
            } else {
                mindustry.gen.Call.setHudText(text.toString())
            }
        }, 0f, 1f)

        mindustry.Vars.netServer.admins.addActionFilter {
            // TODO: add temporal caching

            if (it.player == null) return@addActionFilter true
            if (db.isGriefer(it.player)) {
                return@addActionFilter false
            }

            val playerRankName = db.getRank(it.player)
            val playerRank = config.getRank(it.player, playerRankName)
                ?: return@addActionFilter false

            val tile = it.tile ?: return@addActionFilter true
            val blockProtectionRank = permissionTable[tile] ?: BlockProtectionRank.Guest

            if (blockProtectionRank.ordinal < playerRank.blockProtectionRank.ordinal) {
                it.player.sendMessage(
                    "[red]You do not have permission to do that!" +
                            " (required rank: ${playerRank.blockProtectionRank})"
                )
                return@addActionFilter false
            }

            return@addActionFilter true
        }

        arc.Events.on(EventType.BlockDestroyEvent::class.java) { event ->
            permissionTable.remove(event.tile)
        }

        arc.Events.on(EventType.BlockBuildBeginEvent::class.java) { event ->
            if (event.unit.player == null) return@on
            val playerRankName = db.getRank(event.unit.player)
            val playerRank = config.getRank(event.unit.player, playerRankName) ?: return@on
            permissionTable[event.tile] = playerRank.blockProtectionRank
        }

        arc.Events.on(EventType.PlayerConnect::class.java) { event ->
            val err = db.loadPlayer(event.player)
            if (err != null) {
                event.player.sendMessage(err)
            } else {
                event.player.sendMessage("[green]You are logged in as ${event.player.name}!")
            }
        }
    }

    override fun registerServerCommands(handler: CommandHandler) {

        handler.register("tws-reload-config", "reload config file at ${Config.PATH}") {
            try {
                config = Config.load()
            } catch (e: Exception) {
                print("Error reloading config file at ${Config.PATH}")
                e.printStackTrace()
            }
        }

        handler.register("tws-set-rank", "<@name/#uuid> <rank>", "set rank of a player") { args ->
            if (args.size < 2) {
                print("expected at least 2 arguments, got ${args.size}")
                return@register
            }

            val rank = args[1]

            if (rank == Rank.GRIEFER) {
                print("to set a rank to griefer, use /tws-mark-griefer")
                return@register
            }

            val rankObj = config.ranks[rank] ?: run {
                print("rank $rank does not exist")
                print("available ranks: ${config.ranks.keys}")
                return@register
            }

            val nameOrId = args[0]
            var name: String? = null

            if (nameOrId.startsWith("#")) {
                val id = nameOrId.substring(1)
                name = db.getPlayerNameByUuid(id)
            } else if (nameOrId.startsWith("@")) {
                name = nameOrId.substring(1)
                if (!db.playerExists(name)) name = null
            } else {
                print("expected name starting with @ or uuid starting with #")
                return@register
            }

            if (name == null) {
                print("player not found")
                return@register
            }

            db.setRank(name, rank)

            for (player in Groups.player) {
                if (db.getPlayerNameByUuid(player.uuid()) == name) {
                    player.kick("your rank changed, you can reconnect immediatelly", 0)
                }
            }
        }
    }


    override fun registerClientCommands(handler: CommandHandler) {
        fun register(name: String, signature: String, description: String, callbalck: (Array<String>, Player) -> Unit) {
            handler.register(name, signature, description) { args, player: Player ->
                if (db.isGriefer(player)) {
                    player.sendMessage("[red]you are griefer, you can not use this command")
                    return@register
                }

                callbalck(args, player)
            }
        }

        register(
            "votekick",
            "<name/#id> <reason>",
            "mark a player as griefer, this can be only undone by admin"
        ) { args, player: Player ->

            if (Groups.player.size() < 3) {
                player.sendMessage("[red] need at least 3 players to votekick")
                return@register
            }

            var neededVotes = 0
            for (player in Groups.player) {
                val rank = db.getRank(player)
                val rankObj = config.getRank(player, rank) ?: continue
                neededVotes += rankObj.voteWeight
            }
            if (neededVotes % 2 == 1) neededVotes += 1
            neededVotes /= 2

            // this is useless
            if (player.sendMissingArgsMessage(args, 2)) return@register

            val name = args[0]
            val reason = args[1]

            val maxReasonLength = 64

            if (reason.length > maxReasonLength) {
                player.sendMessage("[red]keep the reason short, for reference: ${reason.take(maxReasonLength)}...")
                return@register
            }

            val target = Groups.player.find { "#" + it.id == name || name in it.name }

            if (target == null) {
                player.sendMessage("[red]player $name not found")
                return@register
            }

            if (db.isGriefer(target)) {
                player.sendMessage("[red]player $name is already marked")
                return@register
            }

            val session = MarkGrieferSession(target, neededVotes, player, reason)
            grieferSessions.add(session)

            handler.handleMessage("/vote y #${grieferSessions.size}", player)
        }

        register(
            "vote",
            "<y/n/c> [#id]",
            "vote for marking a griefer, admins can cancel with 'c'," +
                    " CAUTION: if players vote this down, you will be marked instead"
        ) { args, player: Player ->
            if (player.sendMissingArgsMessage(args, 1)) return@register

            val vote = args[0]

            if (grieferSessions.isEmpty()) {
                player.sendMessage("[red]no griefers to vote for")
                return@register
            }

            var index = 1
            if (grieferSessions.size > 1) {
                if (args.size < 2) {
                    player.sendMessage("[red]expected #id as well since there are multiple griefers")
                    return@register
                }

                try {
                    index = max(args[1].toInt(), 1)
                } catch (e: NumberFormatException) {
                    player.sendMessage("[red]expected #id as well since there are multiple griefers")
                    return@register
                }
            }

            val playerRankName = db.getRank(player)
            val playerRank = config.getRank(player, playerRankName) ?: return@register

            val session = grieferSessions[index - 1]
            when (vote) {
                "y" -> {
                    session.yea[player] = playerRank.voteWeight
                    mindustry.gen.Call.sendMessage("${player.name} voted for ${session.target.name} to be marked as griefer!")
                }

                "n" -> {
                    session.nay[player] = playerRank.voteWeight
                    mindustry.gen.Call.sendMessage("${player.name} voted against ${session.target.name} to be marked as griefer!")
                }

                "c" -> {
                    if (!player.admin) {
                        player.sendMessage("[red]only admins can cancel votes")
                        return@register
                    }

                    grieferSessions.remove(session)
                }

                else -> {
                    player.sendMessage("[red]expected y/n/c, got $vote")
                }
            }

            if (session.yeaVotes >= session.neededVotes) {
                db.markGriefer(session.target)
                grieferSessions.remove(session)
                mindustry.gen.Call.sendMessage("${session.target.name} was marked as griefer!")
            } else if (session.nayVotes >= session.neededVotes) {
                db.markGriefer(session.initiator)
                grieferSessions.remove(session)
                mindustry.gen.Call.sendMessage("Vote canceled!")
            }

        }

        register("login", "<username> <password>", "login to your account") { args, player: Player ->
            if (player.sendMissingArgsMessage(args, 2)) return@register

            val username = args[0]
            val password = args[1]
            val err = db.loginPlayer(player, username, password)
            if (err != null) {
                player.sendMessage("[red]$err")
            } else {
                player.sendMessage("[green]Logged in as ${player.name}!")
            }
        }

        register("logout", "", "logout from your account") { args, player: Player ->
            val err = db.logoutPlayer(player)
            if (err != null) {
                player.sendMessage("[red]$err")
            } else {
                player.sendMessage("[green]You are now logged out!")
            }
        }

        data class RegisterSession(val name: String, val password: String)

        val loginSessions = mutableMapOf<Player, RegisterSession>()
        register("register", "<name> <password>", "register to your account") { args, player: Player ->
            if (player.sendMissingArgsMessage(args, 2)) return@register

            val name = args[0]
            val password = args[1]

            val session = loginSessions.remove(player)
            if (session != null) {
                if (session.name != name) {
                    player.sendMessage("[red] The name you reentered does not match.")
                    return@register
                }

                if (session.password != password) {
                    player.sendMessage("[red] The password you reentered does not match.")
                    return@register
                }

                val err = db.registerPlayer(name, session.password)
                if (err != null) {
                    player.sendMessage("[red]$err")
                } else {
                    player.sendMessage("[green]Registered as ${name}! You can now login with /login!")
                }
            } else {
                loginSessions[player] = RegisterSession(name, password)
                player.sendMessage("[yellow]Please enter your name and password again to confirm.")
            }
        }

        register("status", "", "check your account status") { args, player: Player ->
            player.sendMessage(db.status(player))
        }
    }
}

@Serializable
data class Config(
    val ranks: HashMap<String, Rank>
) {
    fun getRank(player: Player, name: String): Rank? {
        return ranks[name] ?: run {
            player.sendMessage("[red]BUG: your rank is corrupted, contact an admin!")
            return null
        }
    }

    companion object {
        const val PATH = "config/tws-config.json"

        fun load(): Config {
            val file = java.io.File(PATH)

            if (!file.exists()) {
                val json = Json {
                    prettyPrint = true
                }
                val new = Config(
                    hashMapOf(
                        Rank.GRIEFER to Rank(BlockProtectionRank.Griefer, 0, true),
                        Rank.GUEST to Rank(BlockProtectionRank.Guest, 1, false),
                        Rank.NEWCOMER to Rank(BlockProtectionRank.Unverified, 1, false),
                        Rank.VERIFIED to Rank(BlockProtectionRank.Member, 2, false),
                        "dev" to Rank(BlockProtectionRank.Member, 100, true),
                        "owner" to Rank(BlockProtectionRank.Member, 100, true),
                    )
                )
                file.createNewFile()
                file.writeText(json.encodeToString(new))
                return new
            }

            return Json.decodeFromString<Config>(file.readText())
        }
    }
}

@Serializable
data class Rank(
    val blockProtectionRank: BlockProtectionRank,
    val voteWeight: Int,
    val visible: Boolean,
) {
    companion object {
        const val GUEST = "guest"
        const val NEWCOMER = "newcomer"
        const val VERIFIED = "verified"
        const val GRIEFER = "griefer"
    }
}

@Serializable
enum class BlockProtectionRank {
    Griefer,
    Guest,
    Unverified,
    Member,
}

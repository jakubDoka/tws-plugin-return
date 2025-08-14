package mlokis.tws

import arc.util.CommandHandler
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import mindustry.game.EventType
import mindustry.gen.Groups
import mindustry.gen.Player
import mindustry.mod.Plugin
import mindustry.world.Tile
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.JDABuilder
import net.dv8tion.jda.api.entities.Activity
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel
import net.dv8tion.jda.api.events.message.MessageReceivedEvent
import net.dv8tion.jda.api.hooks.ListenerAdapter
import net.dv8tion.jda.api.requests.GatewayIntent
import java.util.EnumSet
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random
import kotlin.time.Instant

object Translations {
    const val defultLocale = "en_US"

    private var maps = run {
        val url = object {}.javaClass.getResource("/translations/")
            ?: error("translations directory not found in resources")

        val conn = url.openConnection() as java.net.JarURLConnection
        val jar = conn.jarFile
        jar.entries().asSequence()
            .filter { it.name.startsWith("translations/") && !it.isDirectory }
            .associate { it ->
                val filename = it.name.removePrefix("translations/").removeSuffix(".ini")
                filename to jar.getInputStream(it).bufferedReader().readText()
                    .lines().map { it.split("=", limit = 2) }
                    .filter { it.size == 2 }
                    .associate { it[0].trim() to it[1].trim() }
            }
    }

    fun t(locale: String, key: String, vararg args: Pair<String, Any>): String {
        val template = (maps[locale] ?: (maps[defultLocale] ?: error("")))[key] ?: key
        return args.fold(template.replace("\\n", "\n")) { acc, (k, v) ->
            acc.replace("{$k}", v.toString())
        }
    }

    fun exists(locale: String, key: String): Boolean =
        (maps[locale] ?: (maps[defultLocale] ?: error("")))[key] != null
}

fun sendToAll(message: String, vararg args: Pair<String, Any>) {
    for (player in Groups.player) {
        player.send(message, *args)
    }
}

fun Player.markKick(reason: String) = kick(
    fmt("mark-kick", "reason" to reason), 0
)

fun Player.stateKick(reason: String) = kick(
    fmt("state-kick", "reason" to fmt("state-kick.$reason")), 0
)

fun Player.fmt(message: String, vararg args: Pair<String, Any>): String =
    Translations.t(locale, message, *args)

fun Player.fmtOrDefault(message: String, default: String): String {
    return if (Translations.exists(locale, message)) {
        fmt(message)
    } else {
        default
    }
}

fun Player.send(message: String, vararg args: Pair<String, Any>) {
    sendMessage(fmt(message, *args))
}

fun Player.selectLocale(options: Map<String, String>): String =
    options[locale] ?: options[Translations.defultLocale] ?: "missing translation"

fun Long.displayTime(): String {
    val days = this / 1000 / 60 / 60 / 24
    val hours = (this / 1000 / 60 / 60) % 24
    val minutes = (this / 1000 / 60) % 60
    val seconds = (this / 1000) % 60

    val sb = StringBuilder()
    if (days > 0) sb.append("${days}d")
    if (hours > 0) sb.append("${hours}h")
    if (minutes > 0) sb.append("${minutes}m")
    if (seconds > 0) sb.append("${seconds}s")

    return sb.toString()
}

class Main : Plugin() {
    var config = Config.load()
    val db = DbReactor(config)
    val grieferSessions = mutableListOf<MarkGrieferSession>()
    val bot: JDA? = run {
        JDABuilder
            .createLight(
                System.getenv("DISCORD_BOT_TOKEN") ?: return@run null,
                EnumSet.of(GatewayIntent.GUILD_MESSAGES, GatewayIntent.MESSAGE_CONTENT)
            )
            .addEventListeners(object : ListenerAdapter() {
                override fun onMessageReceived(event: MessageReceivedEvent) {
                    if (event.message.channel.id == config.discordBridgeChannelId?.toString() && !event.author.isBot) {
                        val message = DiscordMessage(event.author.id, event.author.name, event.message.contentRaw)
                        arc.Core.app.post { forwardDiscordMessage(message) }
                    }
                }
            })
            .setActivity(Activity.playing("tws-plugin"))
            .build()
            .awaitReady()
    }

    // pin:name -> userId
    val discordConnectionSessions = mutableMapOf<String, String>()

    data class DiscordMessage(val senderId: String, val fallbackName: String, val message: String)

    data class ChatMessage(val sender: String, val message: String)

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
            return "${initiator.name}[] wants to mark [pink]${target.name}[] a griefer" +
                    " because [yellow]${reason}[], [yellow]${neededVotes}[] votes needed" +
                    " (#$idx [green]${yeaVotes}[]y [red]${nayVotes}[]n [yellow]${timeRemining}[]s)"
        }
    }

    fun forwardDiscordMessage(message: DiscordMessage) {
        val inGameName = db.getPlayerNameByDiscordId(message.senderId)
        if (inGameName != null) {
            mindustry.gen.Call.sendMessage("[grey]<[][green]D[] $inGameName[grey]>:[] ${message.message}")
        } else {
            mindustry.gen.Call.sendMessage("[grey]<[][blue]D[] ${message.fallbackName}[grey]>:[] ${message.message}")
        }
    }

    override fun init() {

        // HUD
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

        // AFK/PERMS tracking
        val minAfkPeriod = 1000 * 60 * 2
        val afkMarker = "[red](afk)[]"

        class PlayerActivityTracker {
            var lastActive = System.currentTimeMillis()

            val isAfk: Boolean
                get() = System.currentTimeMillis() - lastActive > minAfkPeriod

            fun onAction(player: Player) {
                lastActive = System.currentTimeMillis()
                player.name = player.name.replace(afkMarker, "")
            }
        }

        class TilePermission(var protectionRank: BlockProtectionRank, var issuer: PlayerActivityTracker)

        val defaultPermission = TilePermission(BlockProtectionRank.Guest, PlayerActivityTracker())
        val playerActivityByUuid = mutableMapOf<String, PlayerActivityTracker>()
        val permissionTable = mutableMapOf<Tile, TilePermission>()

        arc.util.Timer.schedule({
            val toRemove = mutableListOf<String>()
            for ((uuid, tracker) in playerActivityByUuid) {
                if (tracker.isAfk) {
                    val player = Groups.player.find { it.uuid() == uuid } ?: run {
                        toRemove.add(uuid)
                        continue
                    }
                    if (afkMarker !in player.name) {
                        player.name += afkMarker
                    }
                }
            }
        }, 0f, minAfkPeriod.toFloat() / 1000)

        val channel = if (bot != null && config.discordBridgeChannelId != null) bot.getChannelById(
            MessageChannel::class.java,
            config.discordBridgeChannelId.toString()
        ) else null
        mindustry.Vars.netServer.admins.addChatFilter { player, message ->
            if (player == null) return@addChatFilter message
            playerActivityByUuid.getOrPut(player.uuid())
            { PlayerActivityTracker() }.onAction(player)

            if (channel != null) {
                val name = db.getPlayerNameByUuid(player.uuid())
                val userId = if (name != null) db.getUserDiscordId(name) else null
                val username = if (userId != null) {
                    bot!!.retrieveUserById(userId).queue {
                        channel.sendMessage("[**${it.name}**]: $message").queue()
                    }
                } else {
                    channel.sendMessage("[$name]: $message").queue()
                }
            }

            message
        }

        mindustry.Vars.netServer.admins.addActionFilter {
            if (it.player == null) return@addActionFilter true

            playerActivityByUuid.getOrPut(it.player.uuid())
            { PlayerActivityTracker() }.onAction(it.player)

            if (db.isGriefer(it.player)) {
                it.player.send("perm.griefer")
                return@addActionFilter false
            }

            val playerRankName = db.getRank(it.player)
            val playerRank = config.getRank(it.player, playerRankName)
                ?: return@addActionFilter false

            val tile = it.tile ?: return@addActionFilter true
            val bp = permissionTable[tile] ?: defaultPermission

            if (bp.protectionRank.ordinal > playerRank.blockProtectionRank.ordinal && !bp.issuer.isAfk) {
                it.player.send(
                    "perm.none",
                    "required" to bp.protectionRank.name,
                    "yours" to playerRank.blockProtectionRank.name
                )
                return@addActionFilter false
            }

            if (bp.issuer.isAfk) {
                permissionTable.remove(tile)
            }

            return@addActionFilter true
        }

        arc.Events.on(EventType.BlockDestroyEvent::class.java) { event ->
            permissionTable.remove(event.tile)
        }

        arc.Events.on(EventType.BlockBuildEndEvent::class.java) { event ->
            if (event.breaking) {
                permissionTable.remove(event.tile)
            }
        }

        arc.Events.on(EventType.PlayEvent::class.java) { event ->
            permissionTable.clear()
            playerActivityByUuid.clear()
        }

        arc.Events.on(EventType.BlockBuildBeginEvent::class.java) { event ->
            if (event.unit.player == null) return@on
            val playerRankName = db.getRank(event.unit.player)
            val playerRank = config.getRank(event.unit.player, playerRankName) ?: return@on
            val tp = permissionTable[event.tile] ?: defaultPermission
            if (tp.protectionRank.ordinal < playerRank.blockProtectionRank.ordinal || tp.issuer.isAfk) {
                permissionTable[event.tile] = TilePermission(
                    playerRank.blockProtectionRank,
                    playerActivityByUuid.getOrPut(event.unit.player.uuid()) { PlayerActivityTracker() }
                )
            }
        }

        arc.Events.on(EventType.PlayerLeave::class.java) { event ->
            for (session in grieferSessions) {
                session.nay.remove(event.player)
                session.yea.remove(event.player)
            }
        }

        arc.Events.on(EventType.PlayerConnect::class.java) { event ->
            val err = db.loadPlayer(event.player)
            if (err != null) {
                event.player.send(err)
            } else {
                event.player.send("hello.user", "name" to event.player.name)
            }
        }
    }

    override fun registerServerCommands(handler: CommandHandler) {
        handler.register("tws-pardon", "<online-name>", "reliefe a player from griefer status") { args ->
            val name = args[0]
            val player = Groups.player.find { name in it.name && db.isGriefer(it) }
            if (player == null) {
                print("player $name that is also a griefer is not online")
                return@register
            }

            db.unmarkGriefer(player)
            print("pardoned $name")
        }

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
                    player.stateKick("your rank changed")
                }
            }
        }
    }


    override fun registerClientCommands(handler: CommandHandler) {
        fun register(
            name: String,
            signature: String,
            description: String,
            callbalck: (Array<String>, Player) -> Unit
        ) {
            handler.register(name, signature, description) { args, player: Player ->
                if (db.isGriefer(player)) {
                    player.send("command.griefer")
                    return@register
                }

                callbalck(args, player)
            }
        }

        register("help", "[page]", "show help") { args, player: Player ->
            if (args.isNotEmpty() && args[0].toIntOrNull() == null) {
                player.send("help.page-nan");
                return@register;
            }

            val commandsPerPage = 6;
            var page = if (args.isNotEmpty()) args[0].toInt() else 1;
            val pages = ceil(handler.commandList.size.toFloat() / commandsPerPage).toInt();

            page--;

            if (page >= pages || page < 0) {
                player.send("help.page-oob", "max-pages" to pages);
                return@register;
            }

            val result = StringBuilder();
            result.append(player.fmt("help.header", "current-page" to page + 1, "total-pages" to pages))
            result.append("\n\n")
            for (i in commandsPerPage * page..<min(commandsPerPage * (page + 1), handler.commandList.size)) {
                val command = handler.commandList[i];
                result
                    .append("[orange] /")
                    .append(command.text)
                    .append("[white] ")
                    .append(player.fmtOrDefault("${command.text}.args", command.paramText))
                    .append("[lightgray] - ")
                    .append(player.fmtOrDefault("${command.text}.desc", command.description))
                    .append("\n")
            }
            player.sendMessage(result.toString());
        }

        class TestSession {
            var questionIndex = 0
            var failedQuestions = 0
            var answerMatrix = mutableListOf<Int>()

            fun generateAnswerMatrix() {
                answerMatrix = config.testQuestions[questionIndex].answers.indices.toMutableList()
                answerMatrix.shuffle()
            }
        }

        register("connect-discord", "<discord-user-id>", "connect with your discord account") { args, player ->
            if (bot == null) {
                player.send("discord bot is not running")
                return@register
            }

            val id = args[0]

            val name = db.getPlayerNameByUuid(player.uuid()) ?: run {
                player.send("you are not logged in")
                return@register
            }

            val pin = Random.nextInt(1000, 9999).toString()

            bot.retrieveUserById(id).queue { user ->
                user.openPrivateChannel().queue {
                    it.sendMessage(
                        "this is your pin: $pin, if you are not trying" +
                                " to connect you TWS account to discord, ignore this message"
                    ).queue()
                }
            }

            discordConnectionSessions["$pin:$name"] = id
            player.send("check your DMs for the pin and call /connect-discord-confirm")
        }

        register("connect-discord-confirm", "<pin>", "confirm your discord account") { args, player ->
            val pin = args[0]

            val name = db.getPlayerNameByUuid(player.uuid()) ?: run {
                player.send("you are not logged in")
                return@register
            }

            val id = discordConnectionSessions.remove("$pin:$name") ?: run {
                player.send("invalid pin")
                return@register
            }

            db.setDiscordId(name, id)
            player.send("[green]discord account connected")
        }

        // player name -> TestSession
        val testSessions = mutableMapOf<String, TestSession>()
        register("tws-test-start", "", "start a test session to get verified") { args, player ->
            val name = db.getPlayerNameByUuid(player.uuid()) ?: run {
                player.send("tws-test.no-login")
                return@register
            }

            val lastFailed = db.hasFailedTestSession(name, config.testTimeout)
            if (lastFailed != null) {
                player.send(
                    "tws-test.start.recently-failed",
                    "time" to ((lastFailed + config.testTimeout * 1000 * 60 * 60) -
                            System.currentTimeMillis()).displayTime()
                )
                return@register
            }

            if (testSessions[name] != null) {
                player.send("tws-test.start.already-in-session")
                return@register
            }

            testSessions[name] = TestSession()

            player.send("tws-test.start.start")
            handler.handleMessage("/tws-test-show", player)
        }

        register("tws-test-show", "", "show the current test question") { args, player ->
            val name = db.getPlayerNameByUuid(player.uuid()) ?: run {
                player.send("tws-test.no-login")
                return@register
            }

            val session = testSessions[name] ?: run {
                player.send("tws-test.no-session")
                return@register
            }

            if (session.answerMatrix.isEmpty()) {
                session.generateAnswerMatrix()
            }

            val question = config.testQuestions[session.questionIndex]
            player.sendMessage("${player.selectLocale(question.question)}:")
            for ((i, j) in session.answerMatrix.withIndex()) {
                player.sendMessage("  ${i + 1}. ${player.selectLocale(question.answers[j])}")
            }
        }

        register("tws-test-answer", "<answer>", "answer a test question") { args, player ->
            val name = db.getPlayerNameByUuid(player.uuid()) ?: run {
                player.send("tws-test.no-login")
                return@register
            }

            val session = testSessions[name] ?: run {
                player.send("tws-test.no-session")
                return@register
            }

            val answer = min(max(args[0].toIntOrNull() ?: run {
                player.send("tws-test.answer.nan")
                return@register
            }, 1), session.answerMatrix.size) - 1

            if (session.answerMatrix[answer] != 0) {
                session.failedQuestions++
            }

            session.questionIndex++

            if (session.questionIndex < config.testQuestions.size) {
                handler.handleMessage("/tws-test-show", player)
                return@register
            }

            player.send("tws-test.answer.finished")
            testSessions.remove(name)

            if (session.failedQuestions != 0) {
                player.send(
                    "tws-test.answer.failed",
                    "failed-questions" to session.failedQuestions,
                    "timeout" to config.testTimeout
                )

                db.addFailedTestSession(name)
                return@register
            }

            db.setRank(name, Rank.VERIFIED)
            player.stateKick("verified")
        }

        register(
            "votekick",
            "<name/#id> <reason...>",
            "mark a player as griefer, this can be only undone by admin"
        ) { args, player ->

            if (Groups.player.size() < 3) {
                player.send("votekick.not-enough-players")
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

            val name = args[0]
            val reason = args[1]

            val maxReasonLength = 64

            if (reason.length > maxReasonLength) {
                player.send("tws-test.start.reason-too-long", "reference" to reason.take(maxReasonLength))
                return@register
            }

            val target = Groups.player.find { "#" + it.id == name || name in it.name }

            if (target == null) {
                player.send("votekick.not-found", "name" to name)
                return@register
            }

            if (target == player) {
                player.send("votekick.self")
                return@register
            }

            if (target.admin) {
                player.send("votekick.admin")
                return@register
            }

            if (db.isGriefer(target)) {
                player.send("votekick.already-marked")
                return@register
            }

            if (player.admin) {
                db.markGriefer(target)
                player.send("votekick.marked")
                return@register
            }

            val session = MarkGrieferSession(target, neededVotes, player, reason)
            grieferSessions.add(session)

            handler.handleMessage("/vote y #${grieferSessions.size}", player)
        }

        register(
            "vote",
            "<y/n/c> [#id]",
            "vote for marking a griefer, admins can cancel with 'c'"
        ) { args, player ->
            val vote = args[0]

            if (grieferSessions.isEmpty()) {
                player.send("votekick.no-griefers")
                return@register
            }

            var index = 1
            if (grieferSessions.size > 1) {
                if (args.size < 2) {
                    player.send("votekick.expected-id")
                    return@register
                }

                try {
                    index = min(max(args[1].toInt(), 1), grieferSessions.size)
                } catch (e: NumberFormatException) {
                    player.send("votekick.expected-id")
                    return@register
                }
            }

            val playerRankName = db.getRank(player)
            val playerRank = config.getRank(player, playerRankName) ?: return@register

            val session = grieferSessions[index - 1]
            when (vote) {
                "y" -> {
                    session.nay.remove(player)
                    session.yea[player] = playerRank.voteWeight
                    sendToAll("vote.voted-for", "voter" to player.name, "for" to session.target.name)
                }

                "n" -> {
                    session.yea.remove(player)
                    session.nay[player] = playerRank.voteWeight
                    sendToAll("vote.voted-against", "voter" to player.name, "against" to session.target.name)
                }

                "c" -> {
                    if (!player.admin) {
                        player.send("vote.only-admins-cancel")
                        return@register
                    }

                    grieferSessions.remove(session)
                }

                else -> {
                    player.send("vote.expected-y-n-c")
                }
            }

            if (session.yeaVotes >= session.neededVotes) {
                db.markGriefer(session.target)
                grieferSessions.remove(session)
                sendToAll("vote.vote-passed", "for" to session.target.name)
            } else if (session.nayVotes >= session.neededVotes) {
                grieferSessions.remove(session)
                sendToAll("vote.vote-canceled")
            }
        }

        register("login", "<username> <password>", "login to your account") { args, player ->
            val username = args[0]
            val password = args[1]
            val err = db.loginPlayer(player, username, password)
            if (err != null) player.send(err)
        }

        register("logout", "", "logout from your account") { args, player ->
            val err = db.logoutPlayer(player)
            if (err != null) {
                player.send(err)
            } else {
                player.send("logout.success")
            }
        }

        data class RegisterSession(val name: String, val password: String)

        val registerSessions = mutableMapOf<Player, RegisterSession>()
        register("register", "<name> <password>", "register to your account") { args, player ->
            val name = args[0]
            val password = args[1]

            val session = registerSessions.remove(player)
            if (session != null) {
                if (session.name != name) {
                    player.send("register.name-mismatch")
                    return@register
                }

                if (session.password != password) {
                    player.send("register.password-mismatch")
                    return@register
                }

                val err = db.registerPlayer(name, session.password)
                if (err != null) {
                    player.send(err)
                } else {
                    player.send("register.success", "name" to name)
                }
            } else {
                registerSessions[player] = RegisterSession(name, password)
                player.send("register.repeat")
            }
        }

        register("status", "", "check your account status") { args, player ->
            player.sendMessage(db.status(player))
        }

        register("show-locale", "", "show the current locale") { args, player ->
            player.sendMessage(player.locale)
        }
    }
}

@Serializable
data class TestQuestion(val question: Map<String, String>, val answers: List<Map<String, String>>)

@Serializable
data class Config(
    val ranks: HashMap<String, Rank>,
    val testQuestions: List<TestQuestion>,
    val testTimeout: Int,
    val discordBridgeChannelId: ULong?,
) {
    fun getRank(player: Player, name: String): Rank? {
        return ranks[name] ?: run {
            player.send("rank.corrupted")
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
                        Rank.GRIEFER to Rank("pink", BlockProtectionRank.Griefer, 0, true),
                        Rank.GUEST to Rank("", BlockProtectionRank.Guest, 1, false),
                        Rank.NEWCOMER to Rank("", BlockProtectionRank.Unverified, 1, false),
                        Rank.VERIFIED to Rank("", BlockProtectionRank.Member, 1, false),
                        "dev" to Rank("purple", BlockProtectionRank.Member, 100, true),
                        "owner" to Rank("gold", BlockProtectionRank.Member, 100, true),
                    ),
                    listOf(
                        TestQuestion(
                            mapOf("en_US" to "What is the capital of France?"),
                            listOf(mapOf("en_Us" to "Paris"), mapOf("en_US" to "London"), mapOf("en_US" to "Berlin"))
                        ),
                        TestQuestion(
                            mapOf("en_US" to "Which question is this?"),
                            listOf(mapOf("en_US" to "2"), mapOf("en_US" to "3"), mapOf("en_US" to "1"))
                        ),
                        TestQuestion(
                            mapOf("en_US" to "What is the capital of Italy?"),
                            listOf(mapOf("en_US" to "Rome"), mapOf("en_US" to "London"), mapOf("en_US" to "Paris"))
                        ),
                    ),
                    1,
                    null
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
    val color: String,
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

    fun display(name: String): String = if (visible) "[$color]<$name>[]" else ""
}

@Serializable
enum class BlockProtectionRank {
    Griefer,
    Guest,
    Unverified,
    Member,
}


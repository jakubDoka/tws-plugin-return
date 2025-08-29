@file:Suppress("RedundantLabelWarning")

package mlokis.tws

import kotlin.reflect.full.*
import arc.util.CommandHandler
import arc.util.Log
import arc.util.Log.err
import arc.util.Log.info
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import mindustry.Vars
import mindustry.content.Blocks
import mindustry.game.EventType
import mindustry.game.Gamemode
import mindustry.game.Team
import mindustry.gen.Call
import mindustry.gen.Groups
import mindustry.gen.Player
import mindustry.mod.Plugin
import mindustry.net.Administration
import mindustry.net.WorldReloader
import mindustry.world.Block
import mindustry.world.Tile
import mindustry.world.modules.ItemModule
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.JDABuilder
import net.dv8tion.jda.api.entities.Activity
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel
import net.dv8tion.jda.api.events.message.MessageReceivedEvent
import net.dv8tion.jda.api.hooks.ListenerAdapter
import net.dv8tion.jda.api.requests.GatewayIntent
import java.util.*
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.absoluteValue
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

object Translations {
    const val DEFAULT_LOCALE = "en_US"

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

    fun t(locale: String, key: String, vararg args: Pair<String, Any?>): String {
        val template = (maps[locale] ?: (maps[DEFAULT_LOCALE] ?: error("")))[key] ?: key
        return args.fold(template.replace("\\n", "\n")) { acc, (k, v) ->
            acc.replace("{$k}", v.toString())
        }
    }

    fun exists(locale: String, key: String): Boolean =
        (maps[locale] ?: (maps[DEFAULT_LOCALE] ?: error("")))[key] != null
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

fun Player.fmt(message: String, vararg args: Pair<String, Any?>): String =
    Translations.t(locale, message, *args)

fun Player.fmtOrDefault(message: String, default: String): String {
    return if (Translations.exists(locale, message)) {
        fmt(message)
    } else {
        default
    }
}

fun Player.send(message: String, vararg args: Pair<String, Any?>) {
    sendMessage(fmt(message, *args))
}

fun Player.selectLocale(options: Map<String, String>): String =
    options[locale] ?: options[Translations.DEFAULT_LOCALE] ?: "missing translation"

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
    val pewPew = PewPew()
    val db = DbReactor(config)
    val voteSessions = mutableListOf<VoteSession>()
    val discordCommands = CommandHandler(config.discord.prefix)
    var gameStartTime = 0L
    var grieferMarkTime = 0L
    var serverCommandHandler: CommandHandler? = null
    var rewindSaverTask: arc.util.Timer.Task? = null
    val bot: JDA? = run {
        JDABuilder
            .createLight(
                System.getenv("DISCORD_BOT_TOKEN") ?: return@run null,
                EnumSet.of(GatewayIntent.GUILD_MESSAGES, GatewayIntent.MESSAGE_CONTENT)
            )
            .addEventListeners(object : ListenerAdapter() {
                override fun onMessageReceived(event: MessageReceivedEvent) {
                    if (event.message.channel.id == config.discord.bridgeChannelId?.toString() && !event.author.isBot) {
                        val message = DiscordMessage(event.author.id, event.author.name, event.message.contentRaw)
                        arc.Core.app.post { forwardDiscordMessage(message) }
                    }

                    tryHandleDiscordCommand(event)
                }
            })
            .setActivity(Activity.playing("${discordCommands.prefix}help"))
            .build()
            .awaitReady()
    }

    init {
        registerDiscordCommands(discordCommands)
    }

    // pin:name -> userId
    val discordConnectionSessions = mutableMapOf<String, String>()

    data class DiscordMessage(val senderId: String, val fallbackName: String, val message: String)

    data class ChatMessage(val sender: String, val message: String)

    inner abstract class VoteSession(val initiator: Player) {
        val yea = mutableMapOf<Player, Int>()
        val nay = mutableMapOf<Player, Int>()
        var timeRemining = 60 * 2
        val neededVotes: Int
            get() = run {
                var needed = 0
                for (player in Groups.player) {
                    val rank = db.getRank(player)
                    val rankObj = config.getRank(player, rank) ?: continue
                    needed += rankObj.voteWeight
                }
                if (needed % 2 == 1) needed += 1
                needed /= 2

                needed
            }

        val yeaVotes: Int
            get() = yea.values.fold(0) { acc, weight -> acc + weight }

        val nayVotes: Int
            get() = nay.values.fold(0) { acc, weight -> acc + weight }

        abstract val translationKey: String

        abstract fun onDisplay(): String

        abstract fun onPass()

        fun display(idx: Int): String {
            return "${initiator.plainName()}[] wants to ${onDisplay()}, [yellow]${neededVotes}[] votes needed" +
                    " ([green]${yeaVotes}[]y [red]${nayVotes}[]n [yellow]${timeRemining}[]s) (use /vote y #$idx)"
        }
    }

    fun listSaves(): List<Long> {
        return java.io.File("config/saves/")
            .listFiles()
            .map { it.name.replace(".msav", "").toLongOrNull() }
            .filterNotNull()
            .sortedByDescending { it }
    }

    fun applyConfig() {
        pewPew.reload(config.pewPew)

        rewindSaverTask?.cancel()
        rewindSaverTask = arc.util.Timer.schedule({
            val saves = listSaves()
            for (i in saves.drop(config.rewind.maxSaves)) {
                java.io.File("config/saves/${i}.msav").delete()
            }

            serverCommandHandler?.handleMessage("save ${System.currentTimeMillis()}")
        }, 10f, config.rewind.saveSpacingMin.toFloat() * 60f)
    }

    fun forwardDiscordMessage(message: DiscordMessage) {
        val inGameName = db.getPlayerNameByDiscordId(message.senderId)
        if (inGameName != null) {
            mindustry.gen.Call.sendMessage("[grey]<[][green]D[] $inGameName[grey]>:[] ${message.message}")
        } else {
            mindustry.gen.Call.sendMessage("[grey]<[][blue]D[] ${message.fallbackName}[grey]>:[] ${message.message}")
        }
    }

    fun tryHandleDiscordCommand(event: MessageReceivedEvent) {
        if (event.author.isBot) return

        if (!event.message.contentRaw.startsWith(config.discord.prefix)) return

        if (config.discord.commandsChannelId != null &&
            event.message.channel.id != config.discord.commandsChannelId.toString()
        ) {
            event.message.channel.sendMessage("wrong channel for commands, use <#${config.discord.commandsChannelId}>")
            return
        }


        arc.Core.app.post {
            val res = discordCommands.handleMessage(event.message.contentRaw, event)

            when (res.type) {
                CommandHandler.ResponseType.fewArguments -> {
                    event.message.channel.sendMessage("too few arguments: **${discordCommands.prefix}${res.command.text}** *${res.command.paramText}*")
                        .queue()
                }

                CommandHandler.ResponseType.manyArguments -> {
                    event.message.channel.sendMessage("too many arguments: **${discordCommands.prefix}${res.command.text}** *${res.command.paramText}*")
                        .queue()
                }

                CommandHandler.ResponseType.unknownCommand -> {
                    event.message.channel.sendMessage("unknown command, use **${discordCommands.prefix}help**").queue()
                }

                CommandHandler.ResponseType.noCommand -> error("should not happen")
                CommandHandler.ResponseType.valid -> {}
            }
        }
    }

    fun displayDiscordInvite(player: Player) {
        if (config.discord.invite == null) return

        mindustry.gen.Call.openURI(player.con, config.discord.invite)
    }

    override fun init() {
        Log.useColors = false
        Log.logger = object : Log.LogHandler {
            val prevLogger = Log.logger
            val adminChannel =
                if (config.discord.adminChannelId != null && bot != null)
                    bot.getChannelById(MessageChannel::class.java, config.discord.adminChannelId.toString())
                else null

            val logList = ConcurrentLinkedQueue<String>()
            var debuouceTask: arc.util.Timer.Task? = null
            val maxMessageLength = 2000

            override fun log(level: Log.LogLevel, message: String) {
                prevLogger.log(level, message)

                if (adminChannel != null) {
                    logList.add("[$level] $message")
                    debounceFlush(adminChannel)
                }
            }

            fun debounceFlush(channel: MessageChannel) {
                if (debuouceTask != null) return

                debuouceTask = arc.util.Timer.schedule({
                    debuouceTask = null

                    val batch = StringBuilder()
                    while (true) {
                        val msg = logList.poll() ?: break
                        if (batch.length + msg.length + 1 > maxMessageLength) {
                            // flush current batch
                            channel.sendMessage(batch).queue()
                            batch.clear()
                        }
                        batch.appendLine(msg)
                    }

                    if (batch.isNotEmpty()) {
                        channel.sendMessage(batch).queue()
                    }

                }, 0.1f)
            }
        }

        applyConfig()

        // HUD
        arc.util.Timer.schedule({
            val toRemove = mutableListOf<VoteSession>()
            val text = StringBuilder()
            for ((i, session) in voteSessions.withIndex()) {
                text.append(session.display(i))
                text.append("\n")

                session.timeRemining -= 1
                if (session.timeRemining <= 0) {
                    toRemove.add(session)
                }
            }
            voteSessions.removeAll(toRemove)

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

        class Interaction(val playerName: String, val action: Administration.ActionType) {
            fun display(tile: Tile, player: Player): String =
                "[yellow]${playerName}[] was last to perform [orange]${action.name}[] here"
        }

        val defaultPermission = TilePermission(BlockProtectionRank.Guest, PlayerActivityTracker())
        val playerActivityByUuid = mutableMapOf<String, PlayerActivityTracker>()
        val permissionTable = mutableMapOf<Tile, TilePermission>()
        val interactions = mutableMapOf<Tile, Interaction>()

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

        val channel = if (bot != null && config.discord.bridgeChannelId != null) bot.getChannelById(
            MessageChannel::class.java,
            config.discord.bridgeChannelId.toString()
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
                        channel.sendMessage("[**${it.effectiveName}**]: $message").queue()
                    }
                } else if (name != null) {
                    channel.sendMessage("[$name]: $message").queue()
                } else {
                    channel.sendMessage("[*${player.plainName()}*]: $message").queue()
                }
            }

            message
        }

        mindustry.Vars.netServer.admins.addActionFilter {
            if (it.player == null) return@addActionFilter true

            playerActivityByUuid.getOrPut(it.player.uuid())
            { PlayerActivityTracker() }.onAction(it.player)

            if (db.isGriefer(it.player.info)) {
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

            interactions[tile] = Interaction(it.player.name, it.type)

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


        arc.Events.on(EventType.PlayerConnect::class.java) { event ->
            val err = db.loadPlayer(event.player)
            if (err != null) {
                event.player.send(err)
                displayDiscordInvite(event.player)
            } else {
                event.player.send("hello.user", "name" to event.player.plainName())
            }
        }

        class TapData(val tile: Tile, val timestamp: Long = System.currentTimeMillis())

        val doubleTaps = mutableMapOf<String, TapData>()
        arc.Events.on(EventType.TapEvent::class.java) {
            run inspect@{
                val last = doubleTaps[it.player.uuid()] ?: return@inspect

                if (last.tile != it.tile) return@inspect
                if (System.currentTimeMillis() - last.timestamp > 200) return@inspect

                val interaction = interactions[it.tile] ?: return@inspect

                mindustry.gen.Call.label(
                    it.player.con,
                    interaction.display(it.tile, it.player),
                    5f,
                    it.tile.worldx(),
                    it.tile.worldy(),
                )
            }

            doubleTaps[it.player.uuid()] = TapData(it.tile)
        }

        arc.Events.on(EventType.PlayEvent::class.java) { event ->
            permissionTable.clear()
            playerActivityByUuid.clear()
            interactions.clear()
            gameStartTime = System.currentTimeMillis()
            listSaves().forEach { java.io.File("config/saves/${it}.msav").delete() }
        }

        arc.Events.on(EventType.PlayerLeave::class.java) { event ->
            for (session in voteSessions) {
                session.nay.remove(event.player)
                session.yea.remove(event.player)
            }
            doubleTaps.remove(event.player.uuid())
        }
    }

    fun registerDiscordCommands(handler: CommandHandler) {
        handler.register("help", "show help") { args, event: MessageReceivedEvent ->
            val builder = StringBuilder("Commands:\n")
            for (command in handler.commandList) {
                builder.append("**${handler.prefix}${command.text}**")
                if (command.paramText.isNotEmpty()) {
                    builder.append(" *${command.paramText}*")
                }
                builder.append(" - ${command.description}\n")
            }
            event.channel.sendMessage(builder.toString()).queue()
        }

        handler.register("game-status", "check the status of the game") { args, event: MessageReceivedEvent ->
            event.channel.sendMessage(
                "" +
                        "playing: **${Vars.state.rules.mode().name}**\n" +
                        "map: **${Vars.state.map.name()}**\n" +
                        "players: **${Groups.player.size()}**\n" +
                        "time: **${
                            (System.currentTimeMillis() - gameStartTime)
                                .displayTime()
                        }**\n" +
                        if (Vars.state.rules.mode() == Gamemode.survival)
                            "wave: **${Vars.state.wave}**\n"
                        else
                            ""
            ).queue()
        }
    }

    override fun registerServerCommands(handler: CommandHandler) {
        serverCommandHandler = handler

        discordCommands.register(
            "server-cmd",
            "[args...]",
            "execute a server command"
        ) { args, event: MessageReceivedEvent ->
            if (event.channel.id != config.discord.adminChannelId.toString()) {
                event.channel.sendMessage("wrong channel for commands, use <#${config.discord.adminChannelId}>")
                    .queue()
                return@register
            }

            val res = handler.handleMessage(args[0])
            when (res.type) {
                CommandHandler.ResponseType.fewArguments -> {
                    event.channel.sendMessage("too few arguments: ${discordCommands.prefix}server-cmd **${res.command.text}** *${res.command.paramText}*")
                        .queue()
                }

                CommandHandler.ResponseType.manyArguments -> {
                    event.channel.sendMessage("too many arguments: ${discordCommands.prefix}server-cmd **${res.command.text}** *${res.command.paramText}*")
                        .queue()
                }

                CommandHandler.ResponseType.unknownCommand -> {
                    event.channel.sendMessage("unknown command, use ${discordCommands.prefix}server-cmd **help**")
                        .queue()
                }

                CommandHandler.ResponseType.noCommand -> error("should not happen")
                CommandHandler.ResponseType.valid -> {}
            }
        }

        handler.register("griefer", "<add/remove> <username/id>", "mark or unpark a griefer") { args ->
            val action = args[0]
            val nameOrId = args[1]

            val player = Groups.player.find { it.plainName().contentEquals(nameOrId, true) }
            val info = if (player != null) player.info else Vars.netServer.admins.getInfoOptional(nameOrId)

            if (info == null) {
                err("player $nameOrId not found")
                return@register
            }

            when (action) {
                "add" -> db.markGriefer(info)
                "remove" -> db.unmarkGriefer(info)
                else -> err("expected add or remove")
            }
        }

        handler.register("reload-config", "reload config files in ${Config.PATH}") {
            try {
                config = Config.load()
                applyConfig()
            } catch (e: Exception) {
                err("Error reloading config file at ${Config.PATH}: ${e.message}")
                e.printStackTrace()
            }
        }

        handler.register("set-rank", "<@name/#uuid> <rank>", "set rank of a player") { args ->
            val rank = args[1]

            if (rank == Rank.GRIEFER) {
                err("to set a rank to griefer, use /tws-mark-griefer")
                return@register
            }

            val rankObj = config.ranks[rank] ?: run {
                err("rank $rank does not exist")
                info("available ranks: ${config.ranks.keys}")
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
                err("expected name starting with @ or uuid starting with #")
                return@register
            }

            if (name == null) {
                err("player not found")
                return@register
            }

            db.setRank(name, rank)

            for (player in Groups.player) {
                if (db.getPlayerNameByUuid(player.uuid()) == name) {
                    player.stateKick("rank-change")
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
                if (db.isGriefer(player.info)) {
                    player.send("command.griefer")
                    return@register
                }

                callbalck(args, player)
            }
        }

        class RewindSession(initiator: Player, val minutes: Int) : VoteSession(initiator) {
            override val translationKey = "rewind"

            override fun onDisplay(): String = "rewind the game by [yellow]${minutes}[] minutes"

            override fun onPass() {
                val saveFileName = java.io.File("config/saves/")
                    .listFiles()
                    .map { it.name.replace(".msav", "").toLongOrNull() }
                    .filterNotNull()
                    .minBy {
                        (System.currentTimeMillis() - minutes * 1000 * 60 - it)
                            .absoluteValue
                    }

                sendToAll("rewind will temporarily stop the server in 5 seconds, you can join immediately")

                arc.util.Timer.schedule({
                    serverCommandHandler!!.handleMessage("stop")
                    serverCommandHandler!!.handleMessage("load $saveFileName")
                }, 5f)
            }
        }

        register("rewind", "<minutes>", "starts a vote to rewind the game by <minutes>") { args, player ->
            val minutes = args[0].toIntOrNull() ?: run {
                player.send("minutes must be a number")
                return@register
            }

            if (minutes < 0) {
                player.send("you can't rewind into the future")
                return@register
            }

            if (System.currentTimeMillis() - grieferMarkTime > config.rewind.gracePeriodMin * 60 * 1000) {
                player.send("rewind is only applicable ${config.rewind.gracePeriodMin} minutes after a griefer mark")
                return@register
            }

            voteSessions.add(RewindSession(player, minutes))
            handler.handleMessage("/vote y #${voteSessions.size}", player)
        }

        val buildCoreBlock = mapOf(
            Blocks.vault to Blocks.coreShard,
            Blocks.reinforcedVault to Blocks.coreBastion,
        )

        class BuildCoreSession(initiator: Player, val tile: Tile, val itemModule: ItemModule) : VoteSession(initiator) {
            override val translationKey = "build-core"

            override fun onDisplay(): String =
                "build a core at [yellow]${tile.centerX()}:${tile.centerY()}[]"

            override fun onPass() {
                val toBuild = buildCoreBlock[tile.build?.block] ?: run {
                    sendToAll("core build failed because the tile is no longer a ${buildCoreBlock.keys.joinToString(" or ")}")
                    return
                };

                if (!itemModule.has(toBuild.requirements)) {
                    sendToAll("core build failed because you are missing resources")
                    return
                }

                itemModule.remove(toBuild.requirements)

                mindustry.gen.Call.constructFinish(
                    tile.build.tile,
                    toBuild,
                    null,
                    0,
                    initiator.team(),
                    null
                );
            }
        }

        register("build-core", "", "build a core at your location") { args, player ->
            var tile = Vars.world.tile(player.tileX(), player.tileY())
            val core = Vars.state.teams[Team.sharded]?.core() ?: run {
                player.send("no core is present")
                return@register
            }

            val toBuild = buildCoreBlock[tile.build?.block] ?: run {
                player.send("core can only be built on a ${buildCoreBlock.keys.joinToString(" or ")}")
                return@register
            };


            if (!core.items.has(toBuild.requirements)) {
                val sb = StringBuilder("you are missing resources to build a core, the requirements are: ")
                var first = true
                for (req in Blocks.coreShard.requirements) {
                    if (!first) sb.append(", ")
                    first = false
                    sb.append(Util.itemIcons[req.item.name])
                    sb.append(" x ")
                    sb.append(req.amount)
                }
                player.send(sb.toString())
                return@register
            }

            voteSessions.add(BuildCoreSession(player, tile, core.items))
            handler.handleMessage("/vote y #${voteSessions.size}", player)
        }

        register("list-maps", "[page]", "list all maps you can switch to") { args, player ->
            val maps = Vars.maps.all().map { it.name() }

            val commandsPerPage = 10;
            var page = if (args.isNotEmpty()) args[0].toInt() else 1;
            val pages = ceil(maps.size.toFloat() / commandsPerPage).toInt();

            page--;

            if (page !in 0..<pages) {
                player.send("list-maps.page-oob", "max-pages" to pages);
                return@register;
            }

            val result = StringBuilder();
            result.append(player.fmt("list-maps.header", "current-page" to page + 1, "total-pages" to pages))
            result.append("\n\n")
            for (i in commandsPerPage * page..<min(commandsPerPage * (page + 1), handler.commandList.size)) {
                val command = handler.commandList[i];
                result.append("[yellow]#${i + 1}[]: ${maps[i]}\n")
            }

            player.sendMessage(result.toString());
        }

        class SwitchMapSession(
            initiator: Player,
            val map: mindustry.maps.Map,
        ) : VoteSession(initiator) {
            override val translationKey = "map"

            override fun onDisplay(): String = "change map to [yellow]${map.name()}[] (and end current game)"

            override fun onPass() {
                val reload = WorldReloader()

                reload.begin()


                Vars.world.loadMap(map, map.applyRules(Gamemode.survival))
                Vars.state.rules = Vars.state.map.applyRules(Gamemode.survival)
                Vars.logic.play()

                reload.end()
            }
        }

        register("switch-map", "<#map-id/map-name>", "start a vote to switch to a map") { args, player ->
            val mapId = args[0]
            val allMaps = Vars.maps.all()

            val map: mindustry.maps.Map
            if (mapId.startsWith("#")) {
                val id = mapId.substring(1).toIntOrNull() ?: run {
                    player.send("switch-map.id-nan")
                    return@register
                }

                if (id !in 1..allMaps.size) {
                    player.send("switch-map.id-oob", "max-id" to allMaps.size)
                    return@register
                }

                map = allMaps[id - 1]
            } else {
                map = Vars.maps.byName(args[0]) ?: run {
                    player.send("switch-map.map-not-found")
                    return@register
                }
            }

            voteSessions.add(SwitchMapSession(player, map))

            handler.handleMessage("/vote y #${voteSessions.size}", player)
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

            if (page !in 0..<pages) {
                player.send("help.page-oob", "max-pages" to pages);
                return@register;
            }

            val result = StringBuilder();
            result.append(player.fmt("help.header", "current-page" to page + 1, "total-pages" to pages))
            result.append("\n\n")
            for (i in commandsPerPage * page..<min(commandsPerPage * (page + 1), handler.commandList.size)) {
                val command = handler.commandList[i];
                result.append(
                    "[orange] /${command.text}[white] ${
                        player.fmtOrDefault("${command.text}.args", command.paramText)
                    } - ${player.fmtOrDefault("${command.text}.desc", command.description)}\n"
                )
            }
            player.sendMessage(result.toString());
        }

        register("discord-invite", "", "get the discord invite link") { args, player ->
            if (config.discord.invite == null) {
                player.send("discord invite link id not configured")
                return@register
            }

            displayDiscordInvite(player)
        }

        register("connect-discord", "<discord-user-id>", "connect with your discord account") { args, player ->
            if (bot == null) {
                player.send("connect-discord.not-running")
                return@register
            }

            val id = args[0]

            if (id.toULongOrNull() == null) {
                player.send("connect-discord.id-nan")
                return@register
            }

            val name = db.getPlayerNameByUuid(player.uuid()) ?: run {
                player.send("connect-discord.not-logged-in")
                return@register
            }

            if (discordConnectionSessions.keys.any { it.endsWith(":$name") }) {
                player.send("connect-discord.already-sent")
            }

            val pin = Random.nextInt(1000, 9999).toString()

            bot.retrieveUserById(id).queue({ user ->
                user.openPrivateChannel().queue {
                    it.sendMessage(
                        "this is your pin: $pin, if you are not trying" +
                                " to connect you TWS account to discord, ignore this message"
                    ).queue()

                    arc.Core.app.post {
                        discordConnectionSessions["$pin:$name"] = id
                        player.send("connect-discord.success")
                    }
                }
            }, { e ->
                arc.Core.app.post {
                    player.send("connect-discord.cant-find-user", "id" to id, "reason" to e.message)
                    e.printStackTrace()
                }
            })

        }

        register("connect-discord-confirm", "<pin>", "confirm your discord account") { args, player ->
            val pin = args[0]

            val name = db.getPlayerNameByUuid(player.uuid()) ?: run {
                player.send("connect-discord.not-logged-in")
                return@register
            }

            val id = discordConnectionSessions.remove("$pin:$name") ?: run {
                player.send("connect-discord-confirm.invalid-pin")

                val toRemove = discordConnectionSessions.keys.filter { it.endsWith(":$name") }.toList()
                for (key in toRemove) discordConnectionSessions.remove(key)

                return@register
            }

            db.setDiscordId(name, id)
            player.send("connect-discord-confirm.success")
        }

        class TestSession {
            var questionIndex = 0
            var failedQuestions = 0
            var answerMatrix = mutableListOf<Int>()

            fun generateAnswerMatrix() {
                answerMatrix = config.test.questions[questionIndex].answers.indices.toMutableList()
                answerMatrix.shuffle()
            }
        }

        // player name -> TestSession
        val testSessions = mutableMapOf<String, TestSession>()
        register("tws-test-start", "", "start a test session to get verified") { args, player ->
            val name = db.getPlayerNameByUuid(player.uuid()) ?: run {
                player.send("tws-test.no-login")
                return@register
            }

            val lastFailed = db.hasFailedTestSession(name, config.test.timeout)
            if (lastFailed != null) {
                val lastFailedTime = lastFailed + config.test.timeout * 1000 * 60 * 60
                if (System.currentTimeMillis() < lastFailedTime) {
                    player.send(
                        "tws-test.start.recently-failed",
                        "time" to ((lastFailedTime - System.currentTimeMillis())).displayTime()
                    )
                    return@register
                }
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

            val question = config.test.questions[session.questionIndex]
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

            if (session.questionIndex < config.test.questions.size) {
                handler.handleMessage("/tws-test-show", player)
                return@register
            }

            player.send("tws-test.answer.finished")
            testSessions.remove(name)

            if (session.failedQuestions != 0) {
                player.send(
                    "tws-test.answer.failed",
                    "failed-questions" to session.failedQuestions,
                    "timeout" to config.test.timeout
                )

                db.addFailedTestSession(name)
                return@register
            }

            db.setRank(name, Rank.VERIFIED)
            player.stateKick("verified")
        }

        class MarkGrieferSession(
            initiator: Player,
            val target: Player,
            val reason: String,
        ) : VoteSession(initiator) {
            override val translationKey = "griefer"

            override fun onDisplay(): String =
                "mark [pink]${target.plainName()}[] a griefer because [yellow]${reason}[]"

            override fun onPass() {
                grieferMarkTime = System.currentTimeMillis()
                db.markGriefer(target.info)
            }
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

            val name = args[0]
            val reason = args[1]

            val maxReasonLength = 64

            if (reason.length > maxReasonLength) {
                player.send("tws-test.start.reason-too-long", "reference" to reason.take(maxReasonLength))
                return@register
            }

            val target = Groups.player.find { "#" + it.id == name || it.plainName().contains(name, true) }

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

            if (db.isGriefer(target.info)) {
                player.send("votekick.already-marked")
                return@register
            }

            if (player.admin) {
                db.markGriefer(target.info)
                player.send("votekick.marked")
                return@register
            }

            voteSessions.add(MarkGrieferSession(player, target, reason))

            handler.handleMessage("/vote y #${voteSessions.size}", player)
        }

        register(
            "vote",
            "<y/n/c> [#id]",
            "vote for marking a griefer, admins can cancel with 'c'"
        ) { args, player ->
            val vote = args[0]

            if (voteSessions.isEmpty()) {
                player.send("votekick.no-sessions")
                return@register
            }

            var index = 1
            if (voteSessions.size > 1) {
                if (args.size < 2) {
                    player.send("votekick.expected-id")
                    return@register
                }

                try {
                    index = min(max(args[1].toInt(), 1), voteSessions.size)
                } catch (e: NumberFormatException) {
                    player.send("votekick.expected-id")
                    return@register
                }
            }

            val playerRankName = db.getRank(player)
            val playerRank = config.getRank(player, playerRankName) ?: return@register

            val session = voteSessions[index - 1]
            when (vote) {
                "y" -> {
                    session.nay.remove(player)
                    session.yea[player] = playerRank.voteWeight
                    sendToAll(
                        "vote.voted-for",
                        "voter" to player.plainName(),
                        "for" to session.onDisplay()
                    )
                }

                "n" -> {
                    session.yea.remove(player)
                    session.nay[player] = playerRank.voteWeight
                    sendToAll(
                        "vote.voted-against",
                        "voter" to player.plainName(),
                        "against" to session.onDisplay()
                    )
                }

                "c" -> {
                    if (!player.admin) {
                        player.send("vote.only-admins-cancel")
                        return@register
                    }

                    voteSessions.remove(session)
                }

                else -> {
                    player.send("vote.expected-y-n-c")
                }
            }

            if (session.yeaVotes >= session.neededVotes) {
                session.onPass()
                voteSessions.remove(session)
                sendToAll("vote.vote-passed", "for" to session.onDisplay())
            } else if (session.nayVotes >= session.neededVotes) {
                voteSessions.remove(session)
                sendToAll("vote.vote-canceled")
            }
        }


        var playerInputId = 0
        val passwordInputs = mutableMapOf<Int, (String) -> Unit>()
        arc.Events.on(EventType.TextInputEvent::class.java) {
            passwordInputs.remove(it.textInputId)?.invoke(it.text)
        }

        fun Player.prompt(title: String, message: String, callback: (String) -> Unit) {
            Call.textInput(con, playerInputId, title, message, 1024, "", false)
            passwordInputs[playerInputId] = callback
            playerInputId++
        }

        register("login", "<username>", "login to your account") { args, player ->
            val username = args[0]
            player.prompt("Login", "password") { password ->
                val err = db.loginPlayer(player, username, password)
                if (err != null) player.send(err)
            }
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
        register("register", "<username>", "register to your account") { args, player ->
            val name = args[0]

            val session = registerSessions.remove(player)

            if (session != null && session.name != name) {
                player.send("register.name-mismatch")
                return@register
            }

            player.prompt("Register", "password") { password ->
                if (session != null) {
                    if (session.password != password) {
                        player.send("register.password-mismatch")
                        return@prompt
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
data class PewPewConfig(val def: Map<String, PewPew.Stats>, val links: Map<String, Map<String, String>>)

@Serializable
data class RewindConfig(val gracePeriodMin: Int, val maxSaves: Int, val saveSpacingMin: Int)

@Serializable
data class DiscordConfig(
    val bridgeChannelId: ULong?,
    val commandsChannelId: ULong?,
    val adminChannelId: ULong?,
    val prefix: String,
    val invite: String?,
)

@Serializable
data class TestConfig(val questions: List<TestQuestion>, val timeout: Int)

@Serializable
data class Config(
    val ranks: HashMap<String, Rank>,
    val test: TestConfig,
    val discord: DiscordConfig,
    val pewPew: PewPewConfig,
    val rewind: RewindConfig,
) {
    fun getRank(player: Player, name: String): Rank? {
        return ranks[name] ?: run {
            player.send("rank.corrupted")
            return null
        }
    }

    companion object {
        const val PATH = "config/tws/"

        val default = Config(
            hashMapOf(
                Rank.GRIEFER to Rank("pink", BlockProtectionRank.Griefer, 0, true),
                Rank.GUEST to Rank("", BlockProtectionRank.Guest, 1, false),
                Rank.NEWCOMER to Rank("", BlockProtectionRank.Unverified, 1, false),
                Rank.VERIFIED to Rank("", BlockProtectionRank.Member, 1, false),
                "dev" to Rank("purple", BlockProtectionRank.Member, 1, true),
                "owner" to Rank("gold", BlockProtectionRank.Member, 1, true),
            ),
            TestConfig(
                listOf(
                    TestQuestion(
                        mapOf("en_US" to "What is the capital of France?"),
                        listOf(
                            mapOf("en_US" to "Paris"),
                            mapOf("en_US" to "London"),
                            mapOf("en_US" to "Berlin")
                        )
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
                1
            ),
            DiscordConfig(null, null, null, "!", null),
            PewPewConfig(
                mapOf(
                    "copper-gun" to PewPew.Stats.DEFAULT,
                ),
                mapOf(
                    "alpha" to mapOf("copper" to "copper-gun"),
                    "beta" to mapOf("copper" to "copper-gun"),
                    "gamma" to mapOf("copper" to "copper-gun"),
                )
            ),
            RewindConfig(5, 15, 1)
        )

        fun load(): Config {
            java.io.File(PATH).mkdirs()

            val fields = mutableListOf<Any>()

            for (prop in Config::class.primaryConstructor!!.parameters) {
                val file = java.io.File(PATH + prop.name + ".json")

                val serde = Json.serializersModule.serializer(prop.type);
                if (!file.exists()) {
                    file.createNewFile()
                    val default = Config::class.declaredMemberProperties
                        .find { it.name == prop.name }!!.get(default)
                    file.writeText(Json {
                        prettyPrint = true
                    }.encodeToString(serde, default));
                }

                fields.add(Json.decodeFromString(serde, file.readText())!!)
            }

            return Config::class.primaryConstructor!!
                .call(*fields.toTypedArray())
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



@file:Suppress("RedundantLabelWarning")

package mlokis.tws

import arc.math.Mathf
import java.nio.file.*
import kotlin.reflect.full.*
import arc.util.CommandHandler
import arc.util.Log
import arc.util.Log.err
import arc.util.Log.info
import arc.util.Tmp
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
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
import mindustry.io.MapIO
import mindustry.mod.Plugin
import mindustry.net.Administration
import mindustry.net.Packets
import mindustry.net.WorldReloader
import mindustry.type.Item
import mindustry.type.ItemStack
import mindustry.world.Tile
import mindustry.world.blocks.environment.OreBlock
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.JDABuilder
import net.dv8tion.jda.api.entities.Activity
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel
import net.dv8tion.jda.api.events.message.MessageReceivedEvent
import net.dv8tion.jda.api.hooks.ListenerAdapter
import net.dv8tion.jda.api.requests.GatewayIntent
import net.dv8tion.jda.api.utils.FileUpload
import net.dv8tion.jda.api.utils.messages.MessageCreateBuilder
import net.dv8tion.jda.api.utils.messages.MessageCreateData
import java.util.*
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.absoluteValue
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import javax.imageio.ImageIO

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

    fun assertDefault(key: String): String = maps.getValue(DEFAULT_LOCALE).getValue(key)

    fun t(locale: String, key: String, vararg args: Pair<String, Any?>): String {
        val default = maps[DEFAULT_LOCALE] ?: error("")
        val template = maps[locale]?.get(key) ?: default[key] ?: key
        return args.fold(template.replace("\\n", "\n").replace("\\t", "\t")) { acc, (k, v) ->
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

fun Boolean.isIsNot(): String = if (this) "is" else "is not"

fun Player.markKick(reason: String) = kick(
    fmt("mark-kick", "reason" to reason), 0
)

fun Player.stateKick(reason: String) = kick(Packets.KickReason.serverRestarting)

data class PlayerCapture(val fn: (Player) -> String)

fun Player.fmt(message: String, vararg args: Pair<String, Any?>): String {
    val args = args.toMutableList()
    for (i in args.indices) {
        if (args[i].second is PlayerCapture) {
            args[i] = args[i].first to (args[i].second as PlayerCapture).fn(this)
        }
    }

    return Translations.t(locale, message, *args.toTypedArray())
}

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
    val milliseconds = (this % 1000).toInt()

    val sb = StringBuilder()
    if (days > 0) sb.append("${days}d")
    if (hours > 0) sb.append("${hours}h")
    if (minutes > 0) sb.append("${minutes}m")
    if (seconds > 0) sb.append("${seconds}s")
    if (milliseconds > 0) sb.append("${milliseconds}ms")

    return sb.toString()
}

class CachedCounter {
    var stackedCount = 0
    var flushTask: arc.util.Timer.Task? = null

    fun inc(periond: Float, flush: (Int) -> Unit) {
        if (flushTask == null) {
            flushTask = arc.util.Timer.schedule({
                flushTask = null
                flush(stackedCount)
                stackedCount = 0
            }, 1f)
        }

        stackedCount++
    }
}

class Main : Plugin() {
    var config = Config.load()
    val pewPew = PewPew()
    val pets = Pets()
    val db = DbReactor()
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

    // AFK/PERMS tracking
    val minAfkPeriod = 1000 * 60 * 2
    val afkMarker = "[red](afk)[]"
    val playerActivityByUuid = mutableMapOf<String, PlayerActivityTracker>()

    inner class PlayerActivityTracker {
        var lastActive = System.currentTimeMillis()

        val isAfk: Boolean
            get() = System.currentTimeMillis() - lastActive > minAfkPeriod

        fun onAction(player: Player) {
            lastActive = System.currentTimeMillis()
            player.name = player.name.replace(afkMarker, "")
        }

        fun forceAfk(player: Player) {
            if (isAfk) return
            lastActive = System.currentTimeMillis() - (minAfkPeriod + 1)
            player.name += afkMarker
        }
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

                (needed / 2) + 1
            }

        val yeaVotes: Int
            get() = yea.values.fold(0) { acc, weight -> acc + weight }

        val nayVotes: Int
            get() = nay.values.fold(0) { acc, weight -> acc + weight }

        abstract fun onDisplay(player: Player): String

        abstract fun onPass()

        fun display(idx: Int, player: Player): String {
            return player.fmt(
                "vote.session",
                "initiator" to initiator.plainName(),
                "display" to onDisplay(player),
                "needed" to neededVotes,
                "yea" to yeaVotes,
                "nay" to nayVotes,
                "time" to timeRemining,
                "idx" to idx
            )
        }
    }

    fun listSaves(): List<Long> {
        if (!java.io.File("config/saves/").exists()) {
            return emptyList()
        }
        return java.io.File("config/saves/")
            .listFiles()
            .map { it.name.replace(".msav", "").toLongOrNull() }
            .filterNotNull()
            .sortedByDescending { it }
    }

    val noOpLogger = object : Log.LogHandler {
        override fun log(level: Log.LogLevel, message: String) {}
    }

    // this is not at all this generic
    fun noLog(callback: () -> Unit) {
        val prevLogger = Log.logger
        arc.Core.app.post {
            Log.logger = noOpLogger
        }

        callback()

        arc.Core.app.post {
            Log.logger = prevLogger
        }
    }

    fun applyConfig() {
        pewPew.reload(config.pewPew, config.pewPewAi)
        pets.reload(config, db)
        config.buildCore.init()

        rewindSaverTask?.cancel()
        rewindSaverTask = arc.util.Timer.schedule({
            val saves = listSaves()
            for (i in saves.drop(config.rewind.maxSaves)) {
                java.io.File("config/saves/${i}.msav").delete()
            }
            noLog {
                serverCommandHandler?.handleMessage("save ${System.currentTimeMillis()}")
            }
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
        try {
            val image = ImageIO.read(object {}.javaClass.getResourceAsStream("/block_colors.png"))

            for (block in Vars.content.blocks()) {
                block.mapColor.argb8888(image.getRGB(block.id.toInt(), 0));
                if (block is OreBlock) {
                    block.mapColor.set(block.itemDrop.color);
                }
            }
        } catch (e: Exception) {
            throw RuntimeException(e);
        }

        var debounceTask: arc.util.Timer.Task? = null
        Config.hotReload {
            debounceTask?.cancel()
            debounceTask = arc.util.Timer.schedule({
                try {
                    info("auto reloading config")
                    config = Config.load()
                    applyConfig()
                } catch (e: Exception) {
                    err("Error reloading config: ${e.message}")
                    e.printStackTrace()
                }
            }, 0.1f)
        }

        arc.Events.run(EventType.Trigger.update) {
            pewPew.update()
            pets.update()
        }

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
                    var message = "[$level] $message"
                    while (message.isNotEmpty()) {
                        logList.add(message.take(maxMessageLength))
                        message = message.drop(maxMessageLength)
                    }
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
            for (session in voteSessions) {
                session.timeRemining -= 1
                if (session.timeRemining <= 0) {
                    toRemove.add(session)
                }
            }

            mindustry.gen.Groups.player.forEach { player ->
                val text = buildString {
                    for ((i, session) in voteSessions.withIndex()) {
                        if (i > 0) append("\n")
                        append(session.display(i, player))
                    }
                }

                if (text.isEmpty()) {
                    mindustry.gen.Call.hideHudText(player.con)
                } else {
                    mindustry.gen.Call.setHudText(player.con, text.toString())
                }
            }

            voteSessions.removeAll(toRemove)
        }, 0f, 1f)


        class TilePermission(var protectionRank: BlockProtectionRank, var issuer: PlayerActivityTracker)

        class Interaction(val playerName: String, val action: Administration.ActionType) {
            fun display(tile: Tile, player: Player): String =
                "[yellow]${playerName}[] was last to perform [orange]${action.name}[] here"
        }

        val defaultPermission = TilePermission(BlockProtectionRank.Guest, PlayerActivityTracker())
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
                    } else {
                        val name = db.getPlayerNameByUuid(uuid)
                        if (name != null) {
                            db.addAfkTime(name, minAfkPeriod.toLong())
                        }
                    }
                }
            }
            for (uuid in toRemove) playerActivityByUuid.remove(uuid)
        }, 0f, minAfkPeriod.toFloat() / 1000)

        val channel = if (bot != null && config.discord.bridgeChannelId != null) bot.getChannelById(
            MessageChannel::class.java,
            config.discord.bridgeChannelId.toString()
        ) else null

        mindustry.Vars.netServer.admins.chatFilters.clear() // we ll define our own
        mindustry.Vars.netServer.admins.addChatFilter { player, message ->
            if (player == null) return@addChatFilter message

            val rankName = db.getRank(player)
            val rank = config.getRank(player, rankName) ?: return@addChatFilter null

            if (System.currentTimeMillis() - player.info.lastMessageTime < rank.messageTimeout && !player.admin) {
                player.send("message.cooldown", "time" to (rank.messageTimeout).displayTime())

                player.info.messageInfractions++

                if (player.info.messageInfractions > Administration.Config.messageSpamKick.num() &&
                    Administration.Config.messageSpamKick.num() != 0
                ) {
                    db.markGriefer(player.info, "spamming")
                }

                return@addChatFilter null
            }

            if (player.info.lastSentMessage == message) {
                player.send("message.duplicate")
                return@addChatFilter null
            }

            player.info.messageInfractions = 0
            player.info.lastMessageTime = System.currentTimeMillis()
            player.info.lastSentMessage = message

            playerActivityByUuid.getOrPut(player.uuid()) { PlayerActivityTracker() }.onAction(player)

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
            for (player in Groups.player) {
                if (player.team() != event.tile.team()) {
                    val name = db.getPlayerNameByUuid(player.uuid())
                    if (name != null) {
                        db.addBlocksDestroyed(name)
                    }
                }
            }

            permissionTable.remove(event.tile)
        }


        arc.Events.on(EventType.UnitChangeEvent::class.java) { event ->
            if (event.unit == null) {
                val name = db.getPlayerNameByUuid(event.player.uuid())
                if (name != null) {
                    db.addDeaths(name)
                }
            }
        }

        val unitDeaths = CachedCounter()
        arc.Events.on(EventType.UnitDestroyEvent::class.java) { event ->
            unitDeaths.inc(1f) { stackedUnitDeaths ->
                for (player in Groups.player) {
                    if (player.team() != event.unit.team()) {
                        val name = db.getPlayerNameByUuid(player.uuid())
                        if (name != null) {
                            db.addEnemiesKilled(name, stackedUnitDeaths)
                        }
                    }
                }
            }
        }

        arc.Events.on(EventType.BlockBuildEndEvent::class.java) { event ->
            if (event?.unit?.player != null) {
                val name = db.getPlayerNameByUuid(event.unit.player.uuid())
                if (name != null) {
                    if (event.breaking) {
                        db.addBlockBroken(name)
                    } else {
                        db.addBlockPlaced(name)
                    }
                }
            }

            if (event.breaking) {
                permissionTable.remove(event.tile)
            }
        }

        arc.Events.on(EventType.WaveEvent::class.java) { event ->
            for (player in Groups.player) {
                val name = db.getPlayerNameByUuid(player.uuid())
                if (name != null) {
                    db.addWavesSurvived(name)
                }
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

        // We do it this way because nobody wants to handle
        // all the cases when player gets disconnected
        arc.util.Timer.schedule({
            for (player in Groups.player) {
                val name = db.getPlayerNameByUuid(player.uuid()) ?: continue
                db.addPlayTime(name, 60 * 1000)
            }
        }, 0f, 60f)

        arc.Events.on(EventType.PlayerConnect::class.java) { event ->
            if (config.redirect.ip != null && config.redirect.port != null) {
                Call.connect(event.player.con, config.redirect.ip!!, config.redirect.port!!)
                return@on
            }

            val err = db.loadPlayer(event.player, config)
            if (err != null) {
                event.player.send(err)
                greetNewUser(event.player)
            } else {
                event.player.send("hello.user", "name" to event.player.plainName())
                pets.populate(event.player, config.getRank(event.player, db.getRank(event.player)))
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

        arc.Events.on(EventType.GameOverEvent::class.java) { event ->
            val elapsed = System.currentTimeMillis() - gameStartTime
            db.saveMapScore(
                Vars.state.map.name(), Vars.state.wave, elapsed,
                event.winner == Team.derelict
            )
        }

        arc.Events.on(EventType.PlayEvent::class.java) { event ->
            permissionTable.clear()
            playerActivityByUuid.clear()
            interactions.clear()
            gameStartTime = System.currentTimeMillis()
            listSaves().forEach { java.io.File("config/saves/${it}.msav").delete() }
        }

        arc.Events.on(EventType.PlayerLeave::class.java) { event ->
            db.clearCachesFor(event.player)
            for (session in voteSessions) {
                session.nay.remove(event.player)
                session.yea.remove(event.player)
            }
            doubleTaps.remove(event.player.uuid())
            pets.remove(event.player)
        }

        arc.Events.on(EventType.PlayerChatEvent::class.java) { event ->
            val name = db.getPlayerNameByUuid(event.player.uuid())
            if (name != null) {
                if (event.message.startsWith("/")) {
                    db.addCommandsExecuted(name)
                } else {
                    db.addMessagesSent(name)
                }
            }
        }

        arc.Events.on(EventType.PlayerBanEvent::class.java) { event ->
            db.markGriefer(event.player.info, "admin ID ban")
        }

        arc.Events.on(EventType.PlayerIpBanEvent::class.java) { event ->
            val player = Groups.player.find { it.con.address == event.ip }
            if (player != null) {
                db.markGriefer(player.info, "admin ip ban")
            }
        }
    }


    fun greetNewUser(player: Player) {
        displayDiscordInvite(player)
        Call.infoPopup(player.con, player.fmt("intro"), 10f, arc.util.Align.topRight, 160, 0, 0, 10)
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
            val width = Vars.world.width()
            val height = Vars.world.height()

            val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)

            for (x in 0..<width) {
                for (y in 0..<height) {
                    val tile = Vars.world.tile(x, y);
                    val color = MapIO.colorFor(tile.block(), tile.floor(), tile.overlay(), tile.team())
                    image.setRGB(x, height - y - 1, color.rotateRight(8))
                }
            }

            val baos = ByteArrayOutputStream()
            ImageIO.write(image, "png", baos)
            val data = baos.toByteArray()

            event.channel.sendMessage(
                MessageCreateBuilder()
                    .setContent(
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
                    )
                    .setFiles(FileUpload.fromData(data, "current-map.png"))
                    .build()
            ).queue()
        }
    }

    override fun registerServerCommands(handler: CommandHandler) {
        serverCommandHandler = handler

        discordCommands.register(
            "server-cmd",
            "<args...>",
            "execute a server command"
        ) { args, event: MessageReceivedEvent ->
            if (event.channel.id != config.discord.adminChannelId.toString()) {
                event.channel.sendMessage("wrong channel for commands, use <#${config.discord.adminChannelId}>")
                    .queue()
                return@register
            }

            if (args.getOrNull(0)?.split(" ")?.getOrNull(0) in config.discord.serverCmdBlacklist) {
                event.channel.sendMessage(
                    "this command is disallowed here," +
                            " connect to the server directly"
                ).queue()
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


        handler.register(
            "migrate-db",
            "<file>",
            "migrate the database by running the sql commands in <file>, dont forget to back up the db"
        ) { args ->
            val commands = object {}.javaClass
                .getResource("/migrations/${args[0]}.sql")!!
                .readText()
            db.migrate(commands)
        }

        handler.register("griefer", "<add/remove> <username/id>", "mark or unpark a griefer") { args ->
            val action = args[0]
            val nameOrId = args[1]

            val player = Groups.player.find { it.plainName().contains(nameOrId, true) }
            val info = if (player != null) player.info else Vars.netServer.admins.getInfoOptional(nameOrId)

            if (info == null) {
                err("player $nameOrId not found")
                return@register
            }

            when (action) {
                "add" -> db.markGriefer(info, "manually marked")
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

        handler.register("set-rank", "<name/uuid> <rank>", "set rank of a player") { args ->
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
            var name = db.getPlayerNameByUuid(nameOrId) ?: nameOrId
            if (!db.playerExists(name)) {
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

        handler.register("appeal-status", "<uuid/ip>", "get the appeal status of a given uuid/ip of a player") { args ->
            val id = args[0]

            if (db.hasAppealedKey(id)) {
                info("player has already appealed")
            } else {
                info("player has not appealed yet")
            }
        }
    }


    override fun registerClientCommands(handler: CommandHandler) {
        fun addSession(session: VoteSession) {
            val existing = voteSessions.find { it.initiator.uuid() == session.initiator.uuid() }
            if (existing != null) {
                voteSessions.remove(existing)
                session.initiator.send("vote.session-replaced")
            }

            voteSessions.add(session)
            handler.handleMessage("/vote y #${voteSessions.size}", session.initiator)
        }

        fun register(
            name: String,
            signature: String,
            callbalck: (Array<String>, Player) -> Unit
        ) {
            handler.register(name, signature, Translations.assertDefault("$name.desc")) { args, player: Player ->
                if (db.isGriefer(player.info)) {
                    player.send("command.griefer")
                    return@register
                }

                callbalck(args, player)
            }
        }

        fun register(name: String, callbalck: (Array<String>, Player) -> Unit) {
            register(name, "", callbalck)
        }


        var appealChannel: MessageChannel? = null
        if (config.discord.appealChannelId != null && bot != null) {
            appealChannel = bot.getChannelById(MessageChannel::class.java, config.discord.appealChannelId.toString())
        }


        var wavesToSkip = 0;

        arc.Events.on(EventType.UnitDestroyEvent::class.java) { event ->
            if (Vars.state.enemies == 1 && wavesToSkip > 0) {
                Vars.logic.runWave()
                wavesToSkip--

                if (wavesToSkip == 0) {
                    sendToAll("skip-waves.success")
                } else {
                    sendToAll("skip-waves.waves-left", "waves" to wavesToSkip)
                }
            }
        }

        register("giveup") { args, player ->
            addSession(object : VoteSession(player) {
                override fun onDisplay(player: Player): String = player.fmt("giveup.session")

                override fun onPass() {
                    serverCommandHandler!!.handleMessage("gameover")
                }
            })
        }

        register("skip-waves", "<amount>") { args, player ->
            val amount = args[0].toIntOrNull() ?: run {
                player.send("skip-waves.invalid-amount")
                return@register
            }

            addSession(object : VoteSession(player) {
                override fun onDisplay(player: Player): String =
                    player.fmt("skip-waves.session", "waves" to amount)

                override fun onPass() {
                    wavesToSkip = amount
                }
            })
        }


        handler.register("appeal", "<message...>", Translations.assertDefault("appeal.desc")) { args, player: Player ->
            if (!db.isGriefer(player.info)) {
                player.send("appeal.not-griefer")
                return@register
            }

            if (db.hasAppealed(player.info)) {
                player.send("appeal.already-appealed")
                return@register
            }


            var message = args[0]

            if (message.isEmpty()) {
                message = "no reason given"
            }

            val chan = appealChannel ?: run {
                player.send("appeal.disabled")
                return@register
            }

            chan.sendMessage("${player.plainName()} (${player.uuid()}) appealed: $message").queue()
            db.markAppealed(player.info)
        }

        register("equip-rank", "<rank>") { args, player ->
            val name = db.getPlayerNameByUuid(player.uuid()) ?: run {
                player.send("no-login")
                return@register
            }

            val currentRank = config.getRank(player, db.getRank(player)) ?: return@register

            if (currentRank.blockProtectionRank != BlockProtectionRank.Member) {
                player.send("equip-rank.not-verified")
            }

            val rankName = args[0]

            val rank = config.getRank(player, rankName) ?: run {
                player.send("rank-info.not-found")
                return@register
            }

            if (rank.blockProtectionRank != BlockProtectionRank.Member && !player.admin) {
                player.send("rank-info.not-equipable")
                return@register
            }

            val playerStats = db.getPlayerScore(name) ?: run {
                err("player ${player.name} has no stats")
                player.send("bug.msg")
                return@register
            }

            if (rank.quest == null && rank.adminOnly && !player.admin) {
                player.send("equip-rank.admin-only")
                return@register
            }

            if (rank.quest != null && !rank.quest.isObtained(playerStats)) {
                player.send("rank-info.not-obtained", "name" to rankName)
                return@register
            }

            db.setRank(name, rankName)
            player.stateKick("rank-change")
        }

        register("list-ranks") { args, player ->
            val ranks = config.ranks.keys.sorted()

            val name = db.getPlayerNameByUuid(player.uuid())
            val playerStats = if (name != null) db.getPlayerScore(name) else null

            val message = buildString {
                for (rank in ranks) {
                    val rankData = config.ranks.getValue(rank)
                    if (rankData.quest == null || playerStats == null || rankData.quest.isObtained(playerStats)) {
                        appendLine("[${rankData.color}]$rank[]")
                    } else {
                        appendLine("[gray]$rank[]")
                    }
                }
            }

            player.sendMessage(message)
        }

        register("rank-info", "<rank>") { args, player ->
            val rankName = args[0]

            val rank = config.ranks[rankName] ?: run {
                player.send("rank-info.not-found")
                return@register
            }

            val name = db.getPlayerNameByUuid(player.uuid())
            val playerStats = if (name != null) db.getPlayerScore(name) else null

            player.send(
                "rank-info.table",
                "name" to rank.display(rankName),
                "block-protection-rank" to rank.blockProtectionRank,
                "message-timeout" to rank.messageTimeout.displayTime(),
                "vote-weight" to rank.voteWeight,
                "visible" to rank.visible.isIsNot(),
                "admin-only" to (rank.adminOnly && rank.quest == null).isIsNot(),
                "pets" to if (rank.pets.isEmpty()) "none" else rank.pets.joinToString(", "),
                "quest" to
                        if (rank.quest != null) if (playerStats != null)
                            rank.quest.displayReference(player, "requirements", playerStats)
                        else rank.quest.display(player, "requirements")
                        else player.fmt("rank-info.no-quest")
            )
        }

        register("appear-afk") { args, player ->
            playerActivityByUuid
                .getOrPut(player.uuid()) { PlayerActivityTracker() }
                .forceAfk(player)
        }

        register("player-stats", "[name]") { args, player ->
            val name = if (args.isNotEmpty()) args[0] else db.getPlayerNameByUuid(player.uuid()) ?: run {
                player.send("no-login")
                return@register
            }

            val playerScore = db.getPlayerScore(name) ?: run {
                player.send("player-stats.not-found", "name" to name)
                return@register
            }

            player.send(playerScore.display(player, name))
        }

        register("explain", "<griefer/rewind/pew-pew/account>") { args, player ->
            when (val feature = args[0]) {
                "griefer" -> player.send("explain.griefer")
                "rewind" -> player.send(
                    "explain.rewind",
                    "grace-period" to config.rewind.gracePeriodMin,
                    "rewind-minutes" to config.rewind.maxSaves * config.rewind.saveSpacingMin
                )

                "pew-pew" -> player.send("explain.pew-pew")
                "account" -> player.send("explain.account")
                else -> player.send("explain.unknown-feature", "feature" to feature)
            }
        }


        register("rewind", "<minutes>") { args, player ->
            val minutes = args[0].toIntOrNull() ?: run {
                player.send("rewind.minutes-nan")
                return@register
            }

            if (minutes <= 0) {
                player.send("rewind.into-future")
                return@register
            }

            if (System.currentTimeMillis() - grieferMarkTime > config.rewind.gracePeriodMin * 60 * 1000 && !player.admin) {
                player.send("rewind.not-applicable", "grace-period" to config.rewind.gracePeriodMin)
                return@register
            }

            addSession(object : VoteSession(player) {
                override fun onDisplay(player: Player): String = player.fmt("rewind.session", "minutes" to minutes)

                override fun onPass() {
                    val saveFileName = java.io.File("config/saves/")
                        .listFiles()
                        .map { it.name.replace(".msav", "").toLongOrNull() }
                        .filterNotNull()
                        .minBy {
                            (System.currentTimeMillis() - minutes * 1000 * 60 - it)
                                .absoluteValue
                        }

                    for (player in Groups.player) {
                        player.kick(Packets.KickReason.serverRestarting)
                    }

                    serverCommandHandler!!.handleMessage("stop")
                    serverCommandHandler!!.handleMessage("load $saveFileName")
                }
            })

        }

        register("delete-core") { args, player ->
            val cores = setOf(
                Blocks.coreShard,
                Blocks.coreBastion,
                Blocks.coreNucleus,
                Blocks.coreCitadel,
                Blocks.coreAcropolis,
                Blocks.coreFoundation,
            )

            var tile = Vars.world.tile(player.tileX(), player.tileY())

            if (tile.build?.block !in cores) {
                player.send("what you are hovering over is not a core, allowed blocks are $cores")
            }

            addSession(object : VoteSession(player) {
                override fun onDisplay(player: Player): String = player.fmt("delet the core at ${tile.x}:${tile.y}")

                override fun onPass() {
                    if (tile.build?.block !in cores) {
                        sendToAll("core deletion failed, core is no longer there")
                    }

                    Call.deconstructFinish(tile, tile.build?.block, null)
                }
            });
        }


        register("build-core") { args, player ->
            val buildCoreBlock = mapOf(
                Blocks.vault to (Blocks.coreShard to config.buildCore.serpuloScalingMap),
                Blocks.reinforcedVault to (Blocks.coreBastion to config.buildCore.erekirScalingMap),
            )

            var tile = Vars.world.tile(player.tileX(), player.tileY())
            val core = Vars.state.teams[Team.sharded]?.core() ?: run {
                player.send("build-core.no-core")
                return@register
            }

            val (toBuild, requirements) = buildCoreBlock[tile.build?.block] ?: run {
                player.send("build-core.wrong-block", "expected" to buildCoreBlock.keys.joinToString(" or "))
                return@register
            };

            val totalCapacity = core.storageCapacity
            val itemstack = mutableListOf<ItemStack>()
            for (req in requirements) {
                itemstack.add(ItemStack(req.key, (req.value * totalCapacity).toInt()))
            }

            if (!core.items.has(itemstack)) {
                val sb = StringBuilder()
                var first = true
                for (req in itemstack) {
                    if (!first) sb.append(", ")
                    first = false
                    sb.append(Util.itemIcons[req.item.name])
                    sb.append(" x ")
                    sb.append(req.amount)
                }
                player.send("build-core.missing-resources", "requirements" to sb.toString())
                return@register
            }

            addSession(object : VoteSession(player) {
                override fun onDisplay(player: Player): String = player.fmt(
                    "build-core.session",
                    "tilex" to tile.centerX().toString(),
                    "tiley" to tile.centerY().toString(),
                )

                override fun onPass() {
                    if (!core.items.has(itemstack)) {
                        sendToAll("build-core.failed.missing-resources")
                        return
                    }

                    core.items.remove(itemstack)

                    mindustry.gen.Call.constructFinish(
                        tile.build.tile,
                        toBuild,
                        null,
                        0,
                        initiator.team(),
                        null
                    );
                }
            })
        }

        register("list-maps", "[page]") { args, player ->
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

        fun getMap(player: Player, mapId: String): mindustry.maps.Map? {
            val allMaps = Vars.maps.all()

            return if (mapId.startsWith("#")) {
                val id = mapId.substring(1).toIntOrNull() ?: run {
                    player.send("switch-map.id-nan")
                    return null
                }

                if (id !in 1..allMaps.size) {
                    player.send("switch-map.id-oob", "max-id" to allMaps.size)
                    return null
                }

                allMaps[id - 1]
            } else {
                Vars.maps.byName(mapId) ?: run {
                    player.send("switch-map.map-not-found")
                    return null
                }
            }
        }

        register("map-score", "[#map-id/map-name]") { args, player ->
            val map = (if (args.isNotEmpty()) getMap(player, args[0]) else Vars.state.map) ?: return@register

            val score = db.getMapScore(map.name()) ?: run {
                player.send("map-score.not-played-yet")
                return@register
            }

            player.send(
                "[orange]-- ${map.name()} --[]\n" +
                        (if (score.maxWave == 0) "" else "\t[yellow]${score.maxWave}[] waves\n") +
                        (if (score.shortestPlaytime == Long.MAX_VALUE) "" else
                            "\t[yellow]${score.shortestPlaytime.displayTime()}[] fastest game\n") +
                        "\t[yellow]${score.longestPlaytime.displayTime()}[] longest game"
            )
        }

        register("switch-map", "<#map-id/map-name>") { args, player ->
            val map = getMap(player, args[0]) ?: return@register

            addSession(object : VoteSession(player) {
                override fun onDisplay(player: Player): String =
                    player.fmt("switch-map", "name" to map.name())

                override fun onPass() {
                    val reload = WorldReloader()

                    reload.begin()


                    Vars.world.loadMap(map, map.applyRules(Gamemode.survival))
                    Vars.state.rules = Vars.state.map.applyRules(Gamemode.survival)
                    Vars.logic.play()

                    reload.end()
                }
            })
        }

        register("help", "[page]") { args, player: Player ->
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

        if (config.discord.invite != null) {
            register("discord-invite", "") { args, player ->
                displayDiscordInvite(player)
            }
        }

        if (bot != null) {
            register("connect-discord", "<discord-user-id>") { args, player ->

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

                val pin = player.fmt("connect-discord.pin-message", "pin" to Random.nextInt(1000, 9999))

                bot.retrieveUserById(id).queue({ user ->
                    user.openPrivateChannel().queue {
                        it.sendMessage(pin).queue()

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

            register("connect-discord-confirm", "<pin>") { args, player ->
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
        register("tws-test-start") { args, player ->
            val name = db.getPlayerNameByUuid(player.uuid()) ?: run {
                player.send("no-login")
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

        register("tws-test-show") { args, player ->
            val name = db.getPlayerNameByUuid(player.uuid()) ?: run {
                player.send("no-login")
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

        register("tws-test-answer", "<answer>") { args, player ->
            val name = db.getPlayerNameByUuid(player.uuid()) ?: run {
                player.send("no-login")
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


        register("votekick", "<name/#id> <reason...>") { args, player ->
            if (Groups.player.size() < 3 && !player.admin) {
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

            addSession(object : VoteSession(player) {
                override fun onDisplay(player: Player): String =
                    player.fmt("votekick.session", "name" to target.plainName(), "reason" to reason)

                override fun onPass() {
                    grieferMarkTime = System.currentTimeMillis()
                    db.markGriefer(target.info, reason)
                }
            })
        }

        register("vote", "<y/n/c> [#id]") { args, player ->
            val vote = args[0]

            if (voteSessions.isEmpty()) {
                player.send("vote.no-sessions")
                return@register
            }

            var index = 1
            if (voteSessions.size > 1) {
                if (args.size < 2) {
                    player.send("vote.expected-id")
                    return@register
                }

                index = min(max(args[1].replace("#", "").toIntOrNull() ?: run {
                    player.send("vote.expected-id")
                    return@register
                }, 1), voteSessions.size)
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
                        "for" to PlayerCapture { session.onDisplay(it) }
                    )
                }

                "n" -> {
                    session.yea.remove(player)
                    session.nay[player] = playerRank.voteWeight
                    sendToAll(
                        "vote.voted-against",
                        "voter" to player.plainName(),
                        "against" to PlayerCapture { session.onDisplay(it) }
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

            if (session.yeaVotes >= session.neededVotes || (vote == "y" && player.admin)) {
                session.onPass()
                voteSessions.remove(session)
                sendToAll("vote.vote-passed", "for" to PlayerCapture { session.onDisplay(it) })
            } else if (session.nayVotes >= session.neededVotes || (vote == "n" && player.admin)) {
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

        register("login", "<username>") { args, player ->
            val username = args[0]
            player.prompt("Login", "password") { password ->
                val err = db.loginPlayer(player, username, password)
                if (err != null) player.send(err)
            }
        }

        register("change-name", "<name>") { args, player ->
            val name = args[0]

            val oldName = db.getPlayerNameByUuid(player.uuid()) ?: run {
                player.send("no-login")
                return@register
            }

            val err = db.setPlayerName(oldName, name)
            if (err != null) player.send(err)
            else player.stateKick("name-change")
        }

        register("change-password") { args, player ->
            val name = db.getPlayerNameByUuid(player.uuid()) ?: run {
                player.send("no-login")
                return@register
            }

            player.prompt("Change Password", "old-password") { oldPassword ->
                player.prompt("Change Password", "new-password") { newPassword ->
                    player.prompt("Change Password", "new-password-again") { newPassword2 ->
                        if (newPassword != newPassword2) {
                            player.send("change-password.mismatch")
                            return@prompt
                        }

                        val err = db.changePassword(name, oldPassword, newPassword)
                        if (err != null) player.send(err)
                        else player.stateKick("change-password")
                    }
                }
            }
        }

        register("logout") { args, player ->
            val err = db.logoutPlayer(player)
            if (err != null) {
                player.send(err)
            } else {
                player.stateKick("logout.success")
            }
        }

        register("register", "<username>") { args, player ->
            val name = args[0]

            player.prompt("Register", "password") { password ->
                player.prompt("Register", "password-again") { password2 ->
                    if (password != password2) {
                        player.send("register.password-mismatch")
                        return@prompt
                    }

                    val err = db.registerPlayer(name, password)
                    if (err != null) {
                        player.send(err)
                    } else {
                        player.send("register.success", "name" to name)
                    }
                }
            }
        }

        register("status") { args, player ->
            player.sendMessage(db.status(player))
        }

        register("show-locale") { args, player ->
            player.sendMessage(player.locale)
        }
    }
}


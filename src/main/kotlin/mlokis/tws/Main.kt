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
import mlokis.tws.DbReactor

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

    override fun init() {
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
    }

    override fun registerClientCommands(handler: CommandHandler) {
        handler.register("login", "<username> <password>", "login to your account") { args, player: Player ->
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

        handler.register("logout", "logout from your account") { args, player: Player ->
            val err = db.logoutPlayer(player)
            if (err != null) {
                player.sendMessage("[red]$err")
            } else {
                player.sendMessage("[green]You are now logged out!")
            }
        }

        class RegisterSession(val name: String, val password: String)

        val loginSessions = mutableMapOf<Player, RegisterSession>()
        handler.register("register", "<name> <password>", "register to your account") { args, player: Player ->
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

        handler.register("status", "check your account status") { args, player: Player ->
            player.sendMessage(db.status(player))
        }
    }
}


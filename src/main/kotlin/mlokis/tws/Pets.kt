package mlokis.tws

import arc.graphics.Color
import arc.math.Mathf
import arc.math.geom.Vec2
import arc.util.Log.*
import arc.util.Time
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import mindustry.entities.Effect
import mindustry.game.EventType
import mindustry.gen.Call
import mindustry.gen.Player

class Pets {
    val dif = Vec2()
    val vel = Vec2()
    val playerPos = Vec2()
    var config = mapOf<String, Pets.Stats>()

    val instances = mutableMapOf<Player, MutableList<Pet>>()

    fun populate(player: Player, rank: Rank?) {
        instances[player] = mutableListOf()

        for (pet in (rank ?: return).pets) {
            val stats = config[pet] ?: run {
                warn("pet '$pet' does not exist in config")
                continue
            }

            instances[player]!!.add(Pet(stats))
        }
    }

    fun remove(player: Player) {
        instances.remove(player)
    }


    fun update() {
        for ((player, pets) in instances) {
            for (p in pets) {
                if (!p.materialized) {
                    if (player.unit() != null) {
                        p.pos.set(player.unit().x, player.unit().y)
                        p.vel.setLength(p.stats.attachment)
                        p.vel.setAngle(Mathf.random() * 360f)
                        p.materialized = true
                    } else {
                        continue
                    }
                }

                val unit = player.unit() ?: run {
                    p.materialized = false
                    continue
                }

                val delta = Time.delta / 60f

                for (o in pets) {
                    if (p == o) continue
                    p.vel.add(dif.set(o.pos).sub(p.pos).scl(p.stats.mating * delta))
                }

                val velLen = p.vel.len()

                dif.set(unit.x, unit.y).sub(p.pos)

                p.vel.add(dif.setLength(p.stats.attachment * delta))

                if (p.stats.maxSpeed != p.stats.minSpeed) {
                    p.vel.scl(Mathf.clamp(velLen, p.stats.minSpeed, p.stats.maxSpeed) / velLen)
                }

                p.vel.sub(vel.set(p.vel).scl(p.stats.friction * delta))
                p.pos.add(vel.set(p.vel).scl(delta))

                Call.effect(p.stats.effect, p.pos.x, p.pos.y, p.vel.angle(), Color.white)
            }
        }
    }

    fun reload(config: Config, db: DbReactor) {
        try {
            for ((name, p) in config.pets) {
                p.init(name)
            }
            this.config = config.pets

            var players = instances.keys.toList()
            for (player in players) {
                populate(player, config.getRank(player, db.getRank(player)))
            }

        } catch (e: Exception) {
            err("Error reloading pet config: ${e.message}")
            e.printStackTrace()
        }
    }

    class Pet(var stats: Stats) {
        var materialized = false
        val pos = Vec2()
        val vel = Vec2(1f, 0f)
    }

    @Serializable
    class Stats(
        val acceleration: Float,
        val maxSpeed: Float,
        val minSpeed: Float,
        val friction: Float,
        val mating: Float,
        val attachment: Float,
        val effectName: String,
    ) {
        @Transient
        lateinit var effect: Effect

        fun init(name: String) {
            effect = (Util.property(effectName, mindustry.content.Fx::class.java) ?: error(
                "effect '$effectName' does not exist, following effects are available: ${
                    Util.propertyNameList(mindustry.content.Fx::class.java)
                }"
            )) as Effect
        }
    }
}

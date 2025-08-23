package mlokis.tws

import kotlinx.serialization.Serializable
import arc.math.Mathf
import arc.math.geom.Vec2
import arc.struct.Seq
import arc.util.Time
import mindustry.content.Bullets
import mindustry.content.Items
import mindustry.content.UnitTypes
import mindustry.entities.bullet.BulletType
import mindustry.game.EventType
import mindustry.game.Team
import mindustry.gen.Call
import mindustry.gen.Unit
import mindustry.gen.Groups
import mindustry.type.Item
import mindustry.type.UnitType
import java.lang.Exception
import java.util.*


class PewPew {
    val state = HashMap<Unit, State>()
    private val pool = Seq<State>()
    val weaponSets = HashMap<UnitType, HashMap<Item, Weapon>>()

    init {
        arc.Events.run(EventType.Trigger.update) {
            update()
        }

        arc.Events.on(EventType.UnitDestroyEvent::class.java) {
            val state = state.remove(it.unit)
            if (state != null) {
                state.reset()
                pool.add(state)
            }
        }

        arc.Events.on(EventType.GameOverEvent::class.java) {
            for (state in state.values) {
                state.reset()
                pool.add(state)
            }
            state.clear()
        }
    }

    private fun update() {
        for (u in Groups.unit) {
            val unit = u ?: continue
            if (!unit.hasItem()) continue
            val set = weaponSets[unit.type] ?: continue
            val weapon = set[unit.stack.item] ?: continue
            val state = state.computeIfAbsent(unit) { pool.pop { State() } }
            state.reload += Time.delta / 60f
            if (unit.isShooting) {
                weapon.shoot(unit, state)
            }
        }
    }

    @Serializable
    class Stats(
        val bullet: String,
        val inaccuracy: Float,
        val damageMultiplier: Float,
        val speedMultiplier: Float,
        val lifetimeMultiplier: Float,
        val burstSpacing: Float,
        val reload: Float,
        val bulletsPerShot: Int,
        val ammoMultiplier: Int,
        val itemsPerScoop: Int,
    ) {
        companion object {
            val DEFAULT = Stats("gamma-1", 2f, 1f, .3f, 1f, 1f, 0f, 2, 4, 1)
        }
    }

    class Weapon(val stats: Stats, ut: UnitType? = null, name: String = "ambiguos") {
        var bullet: BulletType = try {
            if ("-" in stats.bullet) {
                Util.unitOrTurretBullet(stats.bullet, ut!!)
            } else {
                Util.bullet(stats.bullet)
                    ?: error("weapon '${stats.bullet}' does not exist, these exist: ${Util.propertyNameList(Bullets::class.java)}")
            }
        } catch (e: Exception) {
            throw Exception("weapon '$name' is invalid: ${e.message}")
        }

        private lateinit var original: BulletType

        init {
            // finding bullet with biggest range
            ut?.weapons?.forEach {
                if (!this::original.isInitialized || original.range < it.bullet.range) {
                    original = it.bullet
                }
            }

            if (!this::original.isInitialized) {
                original = bullet
            }
        }

        fun shoot(unit: Unit, state: State) {
            if (state.reload < stats.reload) {
                return
            }
            state.reload = 0f

            // refilling ammo
            if (state.ammo == 0) {
                // not enough items to get new ammo
                if (unit.stack.amount < stats.itemsPerScoop) {
                    return
                }
                unit.stack.amount -= stats.itemsPerScoop
                state.ammo += stats.ammoMultiplier
            }
            state.ammo--

            shoot(h4.set(unit.aimX, unit.aimY), h5.set(unit.x, unit.y), unit.vel, unit.team, state)
        }

        fun shoot(aim: Vec2, pos: Vec2, vel: Vec2, team: Team, d: State) {
            h1
                .set(original.range, 0f) // set length to range
                .rotate(h2.set(aim).sub(pos).angle()) // rotate to shooting direction
                .add(h3.set(vel).scl(60f * Time.delta)) // add velocity offset

            // its math
            val velLen = h1.len() / original.lifetime / bullet.speed
            var life = original.lifetime / bullet.lifetime
            val dir = h1.angle()
            if (!bullet.collides) {
                // h2 is already in state of vector from u.pos to u.aim and we only care about length
                life *= (h2.len() / bullet.range).coerceAtMost(1f) // bullet is controlled by cursor
            }
            for (i in 0..<stats.bulletsPerShot) {
                Call.createBullet(
                    bullet,
                    team,
                    pos.x, pos.y,
                    dir + Mathf.range(-stats.inaccuracy, stats.inaccuracy),  // apply inaccuracy
                    stats.damageMultiplier * bullet.damage,
                    velLen * stats.speedMultiplier,
                    life * stats.lifetimeMultiplier
                )
            }
        }

        companion object {
            // helper vectors to reduce allocations.
            var h1 = Vec2()
            var h2 = Vec2()
            var h3 = Vec2()
            var h4 = Vec2()
            var h5 = Vec2()
        }
    }

    class State {
        var ammo = 0
        var reload = 0f

        fun reset() {
            ammo = 0
            reload = 0f
        }
    }

    fun reload(config: PewPewConfig) {
        weaponSets.clear()
        try {
            for ((k, set) in config.links) {
                val unit =
                    Util.unit(k)
                        ?: throw error("unit '$k' does not exits ${Util.propertyNameList(UnitTypes::class.java)}")
                if (unit.weapons.isEmpty) throw error("unit '$k' has no weapons thus it cannot hold any extra")
                val map = HashMap<Item, Weapon>()
                for ((i, s) in set) {
                    val item = Util.item(i) ?: throw error(
                        "unit '$k' contains unknown item '$i', valud items are ${
                            Util.propertyNameList(
                                Items::class.java
                            )
                        }"
                    )
                    val stats = config.def[s]
                        ?: throw error(
                            "unit '$k' contains undefined link under '$i'," +
                                    " it should be contained in 'def' field"
                        )
                    map[item] = Weapon(stats, unit, "$k-$i")
                }
                weaponSets[unit] = map
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}

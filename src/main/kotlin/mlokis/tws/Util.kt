package mlokis.tws


import mindustry.content.Bullets
import mindustry.content.Fx
import mindustry.content.Items
import mindustry.content.UnitTypes
import mindustry.entities.Effect
import mindustry.entities.bullet.BulletType
import mindustry.content.Blocks
import mindustry.type.Item
import mindustry.type.Liquid
import mindustry.type.UnitType
import mindustry.world.blocks.defense.turrets.ItemTurret
import mindustry.world.blocks.defense.turrets.LiquidTurret
import mindustry.world.blocks.defense.turrets.PowerTurret

object Util {

    val itemIcons = mapOf(
        "scrap" to "\uf830",
        "copper" to "\uf838",
        "lead" to "\uf837",
        "graphite" to "\uf835",
        "coal" to "\uf833",
        "titanium" to "\uf832",
        "thorium" to "\uf831",
        "silicon" to "\uf82f",
        "plastanium" to "\uf82e",
        "phase-fabric" to "\uf82d",
        "surge-alloy" to "\uf82c",
        "spore-pod" to "\uf82b",
        "sand" to "\uf834",
        "blast-compound" to "\uf82a",
        "pyratite" to "\uf829",
        "metaglass" to "\uf836",
    )

    fun unit(name: String): UnitType? = property(name, UnitTypes::class.java) as? UnitType?

    fun effect(name: String): Effect? = property(name, Fx::class.java) as? Effect?

    fun bullet(name: String): BulletType? = property(name, Bullets::class.java) as? BulletType?

    fun item(name: String): Item? = property(name, Items::class.java) as? Item?

    fun unitOrTurretBullet(ptr: String, unit: UnitType): BulletType {
        var ut = unit
        val parts = ptr.split("-")

        val turret = property(parts[0], Blocks::class.java)
        if (turret is PowerTurret) {
            return turret.shootType;
        }

        if (parts.size != 2) {
            error("the unit bullet has to be unit name, and weapon index separated by '-'")
        }

        if (turret is ItemTurret) {
            val item = item(parts[1]) ?: error(
                "item '${parts[1]}' does not exist, allowed items : ${
                    propertyNameList(Items::class.java)
                }"
            )

            return turret.ammoTypes.get(item) ?: error(
                "item '${parts[1]}' does not have ammo type for turret '${parts[0]}', available items are: ${
                    turret.ammoTypes.joinToString(", ") { it.key.name }
                }"
            )
        }

        if (turret is LiquidTurret) {
            val liquid = property(parts[1], Liquid::class.java) ?: error(
                "liquid '${parts[1]}' does not exist, allowed liquids : ${
                    propertyNameList(Liquid::class.java)
                }"
            )


            return turret.ammoTypes.get(liquid as Liquid) ?: error(
                "liquid '${parts[1]}' does not have ammo type for turret '${parts[0]}', available liquids are: ${
                    turret.ammoTypes.joinToString(", ") { it.key.name }
                }"
            )
        }

        if (parts[0] != "self") {
            ut = unit(parts[0]) ?: error(
                "unit '${parts[0]}' does not exist, allowed unists : ${
                    propertyNameList(UnitTypes::class.java)
                }"
            )
        }

        if (parts[1].toUIntOrNull() == null) {
            error("cannot parse ${parts[1]} to integer")
        }

        val idx = parts[1].toInt() - 1
        if (idx < 0 || idx >= ut.weapons.size) {
            error("the maximal weapon is ${ut.weapons.size} and min is 1, you entered ${parts[1]}")
        }

        return ut.weapons[idx].bullet
    }

    fun property(name: String, target: Class<*>, obj: Any? = null): Any? = try {
        val field = target.getDeclaredField(name)
        field.get(obj)
    } catch (_: Exception) {
        null
    }


    fun propertyList(target: Class<*>): List<Any> =
        target.declaredFields.map { property(it.name, target)!! }

    fun propertyNameList(target: Class<*>): List<String> =
        target.declaredFields.map { it.name }
}

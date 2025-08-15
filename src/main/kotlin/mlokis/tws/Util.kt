package mlokis.tws


import mindustry.content.Bullets
import mindustry.content.Fx
import mindustry.content.Items
import mindustry.content.UnitTypes
import mindustry.entities.Effect
import mindustry.entities.bullet.BulletType
import mindustry.type.Item
import mindustry.type.UnitType

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

    fun item(name: String): Item? = itemList().find { it.name == name }

    fun unitBullet(ptr: String, unit: UnitType): BulletType {
        var ut = unit
        val parts = ptr.split("-")
        if (parts.size != 2) {
            error("the unit bullet has to be unit name, and weapon index separated by '-'")
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
        if (idx >= ut.weapons.size || idx < 0) {
            error("the maximal weapon is ${ut.weapons.size} and min is 1, you entered ${parts[1]}")
        }

        return ut.weapons[idx].bullet
    }

    fun property(name: String, target: Class<*>, obj: Any? = null): Any? = try {
        val field = target.getDeclaredField(name)
        field.get(obj)
    } catch (e: Exception) {
        e.printStackTrace()
        null
    }


    private lateinit var items: List<Item>
    fun itemList(): List<Item> {
        if (!this::items.isInitialized) {
            items = propertyList(Items::class.java).filter { it is Item }.map { it as Item }
        }
        return items
    }

    fun propertyList(target: Class<*>): List<Any> =
        target.declaredFields.map { property(it.name, target)!! }

    fun propertyNameList(target: Class<*>): List<String> =
        target.declaredFields.map { it.name }
}

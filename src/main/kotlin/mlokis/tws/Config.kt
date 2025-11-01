package mlokis.tws

import arc.util.Log.err
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import mindustry.gen.Player
import mindustry.type.Item
import java.nio.file.FileSystems
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardWatchEventKinds
import kotlin.reflect.full.declaredMemberProperties
import kotlin.reflect.full.primaryConstructor

@Serializable
data class RedirectConfig(val ip: String?, val port: Int?)

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
    var appealChannelId: ULong?,
    val prefix: String,
    val invite: String?,
    val serverCmdBlacklist: Set<String>,
)

@Serializable
data class TestConfig(val questions: List<TestQuestion>, val timeout: Int)

@Serializable
data class Rank(
    val color: String,
    val blockProtectionRank: BlockProtectionRank,
    val voteWeight: Int,
    val messageTimeout: Long,
    val visible: Boolean,
    val pets: List<String> = listOf(),
    val adminOnly: Boolean = true,
    val quest: PlayerScore? = null,
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

@Serializable
data class BuildCoreConfig(val serpuloScaling: Map<String, Float>, val erekirScaling: Map<String, Float>) {
    @Transient
    lateinit var serpuloScalingMap: Map<Item, Float>

    @Transient
    lateinit var erekirScalingMap: Map<Item, Float>

    fun init() {
        erekirScalingMap =
            erekirScaling.map { (k, v) -> (Util.item(k) ?: error("erekir item $k not found")) to v.toFloat() }
                .toMap()
        serpuloScalingMap =
            serpuloScaling.map { (k, v) -> (Util.item(k) ?: error("serpulo item $k not found")) to v.toFloat() }
                .toMap()
    }
}

@Serializable
data class Config(
    val ranks: Map<String, Rank>,
    val test: TestConfig,
    val discord: DiscordConfig,
    val pewPew: PewPewConfig,
    val pewPewAi: PewPewConfig,
    val rewind: RewindConfig,
    val pets: Map<String, Pets.Stats>,
    val buildCore: BuildCoreConfig,
    val redirect: RedirectConfig,
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
            mapOf(
                Rank.GRIEFER to Rank(
                    color = "pink",
                    blockProtectionRank = BlockProtectionRank.Griefer,
                    voteWeight = 0,
                    visible = true,
                    messageTimeout = 1000 * 10,
                    pets = listOf(),
                ),
                Rank.GUEST to Rank(
                    color = "",
                    blockProtectionRank = BlockProtectionRank.Guest,
                    voteWeight = 1,
                    visible = true,
                    messageTimeout = 1000 * 2,
                    pets = listOf(),
                ),
                Rank.NEWCOMER to Rank(
                    color = "",
                    blockProtectionRank = BlockProtectionRank.Unverified,
                    voteWeight = 1,
                    visible = false,
                    messageTimeout = 1000 * 1,
                    pets = listOf(),
                ),
                Rank.VERIFIED to Rank(
                    color = "",
                    blockProtectionRank = BlockProtectionRank.Member,
                    voteWeight = 1,
                    visible = false,
                    messageTimeout = 800,
                    pets = listOf(),
                ),
                "dev" to Rank(
                    color = "purple",
                    blockProtectionRank = BlockProtectionRank.Member,
                    voteWeight = 1,
                    visible = true,
                    messageTimeout = 0,
                    pets = listOf("dev-pet", "dev-pet"),
                ),
                "owner" to Rank(
                    color = "gold",
                    blockProtectionRank = BlockProtectionRank.Member,
                    voteWeight = 1,
                    visible = true,
                    messageTimeout = 0,
                    pets = listOf(),
                ),
                "active" to Rank(
                    color = "white",
                    blockProtectionRank = BlockProtectionRank.Member,
                    voteWeight = 1,
                    visible = true,
                    messageTimeout = 0,
                    pets = listOf(),
                    quest = PlayerScore(
                        blocksBroken = 10,
                        blocksPlaced = 10,
                    ),
                ),
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
            DiscordConfig(
                bridgeChannelId = null,
                commandsChannelId = null,
                adminChannelId = null,
                appealChannelId = null,
                prefix = "$",
                invite = null,
                serverCmdBlacklist = setOf("stop", "exit", "migrate-db"),
            ),
            PewPewConfig(
                mapOf(
                    "copper-gun" to PewPew.Stats.DEFAULT,
                ),
                mapOf(
                    "alpha" to mapOf("copper" to "copper-gun"),
                    "beta" to mapOf("copper" to "copper-gun"),
                    "gamma" to mapOf("copper" to "copper-gun"),
                    "dagger" to mapOf("copper" to "copper-gun"),
                )
            ),
            PewPewConfig(
                mapOf(
                    "copper-gun-fast" to PewPew.Stats(
                        bullet = "gamma-1",
                        inaccuracy = 2f,
                        damageMultiplier = 1f,
                        speedMultiplier = .3f,
                        lifetimeMultiplier = 1f,
                        burstSpacing = 0.05f,
                        reload = 0.1f,
                        bulletsPerBurst = 2,
                        burstCount = 1,
                        ammoMultiplier = 4,
                        itemsPerAmmo = 1,
                    ),
                ),
                mapOf(
                    "alpha" to mapOf("copper" to "copper-gun"),
                    "beta" to mapOf("copper" to "copper-gun"),
                    "gamma" to mapOf("copper" to "copper-gun"),
                    "dagger" to mapOf("copper" to "copper-gun-fast"),
                )
            ),
            RewindConfig(
                gracePeriodMin = 5,
                maxSaves = 15,
                saveSpacingMin = 1,
            ),
            hashMapOf(
                "dev-pet" to Pets.Stats(
                    acceleration = 100f,
                    maxSpeed = 1000f,
                    minSpeed = 0f,
                    friction = 1f,
                    mating = 1f,
                    attachment = 100f,
                    effectName = "fire",
                )
            ),
            BuildCoreConfig(
                serpuloScaling = mapOf(
                    "copper" to 0.01f,
                ),
                erekirScaling = mapOf(
                    "graphite" to 0.01f,
                ),
            ),
            RedirectConfig(null, null),
        )

        fun hotReload(apply: () -> Unit) {
            val path = Paths.get(PATH)
            val watchService = FileSystems.getDefault().newWatchService()

            path.register(
                watchService,
                StandardWatchEventKinds.ENTRY_MODIFY
            )


            Thread {
                while (true) {
                    val key = watchService.take()
                    for (event in key.pollEvents()) {
                        val kind = event.kind()
                        val fileName = event.context() as Path
                        apply()
                    }
                    val valid = key.reset()
                    if (!valid) break
                }

                err("Stopped hot reloading")
            }.start()
        }

        fun load(): Config {
            java.io.File(PATH).mkdirs()

            val format = Json { prettyPrint = true }

            val fields = mutableListOf<Any>()

            for (prop in Config::class.primaryConstructor!!.parameters) {
                val file = java.io.File(PATH + prop.name + ".json")

                val serde = Json.serializersModule.serializer(prop.type);
                if (!file.exists()) {
                    file.createNewFile()
                    val default = Config::class.declaredMemberProperties
                        .find { it.name == prop.name }!!.get(default)
                    file.writeText(format.encodeToString(serde, default));
                }

                fields.add(Json.decodeFromString(serde, file.readText())!!)
            }

            return Config::class.primaryConstructor!!
                .call(*fields.toTypedArray())
        }
    }
}




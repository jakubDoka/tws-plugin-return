package mlokis.tws

fun main(args: Array<String>) {
    val translations = java.io.File("config/translations/").listFiles()

    val reference = java.io.File("config/translations/en_US.ini")
        .readText()
        .lines().map { it.split("=", limit = 2) }

    for (file in translations) {
        if (file.name.startsWith("en_US")) continue

        val keys = file.readText()
            .lines().map { it.split("=", limit = 2) }
            .filter { it.size == 2 }
            .associate { it[0] to it[1] }


        val normalized = buildString {
            for (line in reference) {
                if (line.size != 2) {
                    appendLine()
                }

                append(line[0])
                append(" = ")
                append(keys[line[0]] ?: line[1])
                appendLine()
            }
        }

        file.writeText(normalized)
    }

}

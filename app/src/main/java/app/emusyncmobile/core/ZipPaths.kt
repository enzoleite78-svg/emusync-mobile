package app.emusyncmobile.core

object ZipPaths {
    /**
     * Converte o nome de uma entrada do zip em partes de caminho seguras.
     * - Zips criados no Windows usam "\" como separador.
     * - Retorna null para entradas de diretório (o EmuSync só grava arquivos).
     * - Lança SecurityException para caminhos que sairiam da pasta (zip-slip).
     */
    fun normalize(entryName: String): List<String>? {
        val path = entryName.replace('\\', '/')
        if (path.endsWith("/")) return null
        if (path.startsWith("/")) throw SecurityException("Caminho absoluto no zip: $entryName")

        val parts = path.split('/').filter { it.isNotEmpty() && it != "." }
        if (parts.isEmpty()) return null
        if (parts.any { it == ".." }) throw SecurityException("Caminho inválido no zip: $entryName")
        if (parts.first().length == 2 && parts.first()[1] == ':') {
            throw SecurityException("Letra de unidade no zip: $entryName")
        }
        return parts
    }
}

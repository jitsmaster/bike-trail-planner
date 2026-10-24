package com.biketrailplanner.data

/** Reads bundled asset files. Tests supply their own reader. */
interface AssetReader {
    fun read(name: String): String
}

class ClasspathAssetReader(private val classLoader: ClassLoader = AssetReader::class.java.classLoader) : AssetReader {
    override fun read(name: String): String =
        classLoader.getResourceAsStream("assets/$name")?.bufferedReader()?.use { it.readText() }
            ?: throw IllegalStateException("asset not found: $name")
}

class MapAssetReader(private val contents: Map<String, String>) : AssetReader {
    override fun read(name: String): String =
        contents[name] ?: throw IllegalStateException("asset not found: $name")
}

/** Android implementation, wired in the Application/DI holder. */
class AndroidAssetReader(private val assets: android.content.res.AssetManager) : AssetReader {
    override fun read(name: String): String = assets.open(name).bufferedReader().use { it.readText() }
}

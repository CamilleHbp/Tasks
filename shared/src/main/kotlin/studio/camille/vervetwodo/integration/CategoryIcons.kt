package studio.camille.vervetwodo.integration

object CategoryIcons {
    val ids = SharedIconCatalogue.ids
    fun displayId(id: String?) = id?.takeIf { it in ids } ?: "tag"
    fun segmentSweep(categoryCount: Int): Float = if (categoryCount > 0) 360f / categoryCount else 360f
}

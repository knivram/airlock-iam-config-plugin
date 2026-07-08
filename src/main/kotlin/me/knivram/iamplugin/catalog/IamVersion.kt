package me.knivram.iamplugin.catalog

/**
 * An IAM product version as used by the catalog: major.minor. Patch components are deliberately
 * dropped ([parse] maps `8.6.1` to 8.6) — catalogs are published per minor release.
 */
data class IamVersion(val major: Int, val minor: Int) : Comparable<IamVersion> {

    override fun compareTo(other: IamVersion): Int =
        compareValuesBy(this, other, IamVersion::major, IamVersion::minor)

    override fun toString(): String = "$major.$minor"

    companion object {
        private val VERSION = Regex("""(\d+)\.(\d+)(?:\.\d+)*""")

        /**
         * Tolerant parse of a `metadata.iamVersion` value: trims whitespace and surrounding
         * quotes, accepts `8.6` and `8.6.1` (patch dropped). Null for anything else.
         */
        fun parse(raw: String?): IamVersion? {
            val trimmed = raw?.trim()?.trim('\'', '"')?.trim() ?: return null
            val match = VERSION.matchEntire(trimmed) ?: return null
            return IamVersion(match.groupValues[1].toInt(), match.groupValues[2].toInt())
        }
    }
}

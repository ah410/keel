package com.arceusvault.keel.data

/**
 * The key's effective roles. TrueNAS roles include other roles; [includes] is the server's own
 * table from `privilege.roles`, read at login, so the app knows what a key can do before trying.
 */
class Roles(granted: Collection<String>, includes: Map<String, List<String>> = emptyMap()) {
    val fullAdmin = "FULL_ADMIN" in granted
    val granted: Set<String> = granted.toSet()
    val effective: Set<String> = expand(granted, includes)

    fun has(role: String) = fullAdmin || role in effective

    /** True if any of [roles] is held; matches how the API lists alternatives with "|". */
    fun any(vararg roles: String) = fullAdmin || roles.any { it in effective }

    companion object {
        val NONE = Roles(emptyList())

        fun expand(roles: Collection<String>, includes: Map<String, List<String>>): Set<String> {
            val out = mutableSetOf<String>()
            val queue = ArrayDeque(roles)
            while (queue.isNotEmpty()) {
                val r = queue.removeFirst()
                if (out.add(r)) includes[r]?.let(queue::addAll)
            }
            return out
        }
    }
}

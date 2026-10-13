package com.arceusvault.keel.data

import com.arceusvault.keel.core.network.JsonRpcClient
import com.arceusvault.keel.core.network.RawApiKey
import com.arceusvault.keel.core.network.Scram
import com.arceusvault.keel.core.network.trustedHttp
import com.arceusvault.keel.domain.CertTrust
import com.arceusvault.keel.domain.ConnectionMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class LoginException(message: String) : Exception(message)

/** Key material for SCRAM: the raw key on first setup, the stored derived keys afterwards. */
sealed interface KeyMaterial {
    class Raw(val key: RawApiKey) : KeyMaterial
    class Derived(val keys: Scram.DerivedKeys) : KeyMaterial
}

class LoginResult(val rpc: JsonRpcClient, val keys: Scram.DerivedKeys, val roles: Roles, val user: JsonObject?) {
    val fullAdmin: Boolean get() = roles.fullAdmin
}

class Connector(private val json: Json) {

    /** Opens the socket. Throws UntrustedCertificateException when a trust decision is needed. */
    suspend fun open(url: String, trust: CertTrust?, mode: ConnectionMode): JsonRpcClient = withContext(Dispatchers.IO) {
        JsonRpcClient(trustedHttp(trust, privateOnly = mode == ConnectionMode.SECURE), url, json).also { it.connect() }
    }

    suspend fun login(rpc: JsonRpcClient, username: String, apiKeyId: Long, material: KeyMaterial): LoginResult {
        try {
            val session = Scram.start(username, apiKeyId)
            val first = loginEx("CLIENT_FIRST_MESSAGE", session.clientFirst, rpc)
            val serverFirst = Scram.parseServerFirst(session, first.rfc())
            val keys = when (material) {
                is KeyMaterial.Derived -> material.keys
                is KeyMaterial.Raw -> withContext(Dispatchers.Default) {
                    Scram.deriveKeys(material.key.secret, serverFirst.salt, serverFirst.iterations)
                }
            }
            val clientFinal = Scram.clientFinal(session, serverFirst, keys)
            val final = loginEx("CLIENT_FINAL_MESSAGE", clientFinal.message, rpc)
            Scram.verifyServerFinal(clientFinal, final.rfc())
            val user = final["user_info"] as? JsonObject
            return LoginResult(rpc, keys, Roles(rolesOf(user), roleIncludes(rpc)), user)
        } catch (e: Throwable) {
            rpc.close()
            throw if (e is Scram.ScramException) LoginException(e.message ?: "Login failed") else e
        }
    }

    /** The server's role inheritance table. Any session may read it; on failure only granted roles count. */
    private suspend fun roleIncludes(rpc: JsonRpcClient): Map<String, List<String>> = runCatching {
        val params = buildJsonArray {
            add(JsonArray(emptyList()))
            add(buildJsonObject { put("select", buildJsonArray { add(JsonPrimitive("name")); add(JsonPrimitive("includes")) }) })
        }
        (rpc.call("privilege.roles", params) as JsonArray).mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val name = (o["name"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            name to (o["includes"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        }.toMap()
    }.getOrElse { if (it is kotlinx.coroutines.CancellationException) throw it else emptyMap() }

    private suspend fun loginEx(type: String, rfc: String, rpc: JsonRpcClient): JsonObject {
        val params = buildJsonArray {
            add(buildJsonObject {
                put("mechanism", "SCRAM")
                put("scram_type", type)
                put("rfc_str", rfc)
            })
        }
        val resp = rpc.call("auth.login_ex", params, timeoutMs = 60_000) as? JsonObject
            ?: throw LoginException("Unexpected login response")
        when (val t = resp["response_type"]?.jsonPrimitive?.contentOrNull) {
            "SCRAM_RESPONSE" -> return resp
            "AUTH_ERR" -> throw LoginException("Login rejected. Check the username and that the API key is valid and not revoked.")
            "EXPIRED" -> throw LoginException("This API key has expired. Create a new one in TrueNAS.")
            "DENIED" -> throw LoginException("Login denied by the server's security policy.")
            "OTP_REQUIRED" -> throw LoginException("This account requires two-factor login, which API keys can't complete.")
            "REDIRECT" -> throw LoginException("This node redirected login to its HA peer. Connect to the active controller.")
            else -> throw LoginException("Unexpected login response: $t")
        }
    }

    private fun JsonObject.rfc() = this["rfc_str"]?.jsonPrimitive?.contentOrNull
        ?: throw LoginException("Login response missing SCRAM data")

    companion object {
        // privilege.roles lists role names, as a list or a {"$set": [...]} wrapper on some versions.
        fun rolesOf(userInfo: JsonObject?): List<String> {
            val raw = (userInfo?.get("privilege") as? JsonObject)?.get("roles")
            val roles = raw as? JsonArray ?: (raw as? JsonObject)?.get("\$set") as? JsonArray ?: return emptyList()
            return roles.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull }
        }
    }
}

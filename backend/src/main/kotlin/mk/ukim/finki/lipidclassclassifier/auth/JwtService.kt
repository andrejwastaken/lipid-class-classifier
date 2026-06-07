package mk.ukim.finki.lipidclassclassifier.auth

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import tools.jackson.databind.ObjectMapper
import java.time.Instant
import java.util.Base64
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

@Service
class JwtService(
    private val objectMapper: ObjectMapper,
    @Value("\${app.jwt.secret}") private val secret: String,
    @Value("\${app.jwt.expiration-ms}") private val expirationMs: Long,
) {
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()

    fun createToken(userId: UUID, email: String): String {
        val now = Instant.now()
        val header = mapOf("alg" to "HS256", "typ" to "JWT")
        val payload = mapOf(
            "sub" to userId.toString(),
            "email" to email,
            "iat" to now.epochSecond,
            "exp" to now.plusMillis(expirationMs).epochSecond,
        )
        val unsigned = "${encodeJson(header)}.${encodeJson(payload)}"
        return "$unsigned.${sign(unsigned)}"
    }

    fun parseToken(token: String): UserPrincipal? {
        val parts = token.split(".")
        if (parts.size != 3) return null

        val unsigned = "${parts[0]}.${parts[1]}"
        if (!constantTimeEquals(parts[2], sign(unsigned))) return null

        val payload = objectMapper.readValue(decoder.decode(parts[1]), Map::class.java)
        val exp = (payload["exp"] as? Number)?.toLong() ?: return null
        if (Instant.now().epochSecond >= exp) return null

        val subject = payload["sub"] as? String ?: return null
        val email = payload["email"] as? String ?: return null
        return UserPrincipal(UUID.fromString(subject), email)
    }

    private fun encodeJson(value: Any): String = encoder.encodeToString(objectMapper.writeValueAsBytes(value))

    private fun sign(value: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return encoder.encodeToString(mac.doFinal(value.toByteArray(Charsets.UTF_8)))
    }

    private fun constantTimeEquals(left: String, right: String): Boolean {
        val leftBytes = left.toByteArray(Charsets.UTF_8)
        val rightBytes = right.toByteArray(Charsets.UTF_8)
        return java.security.MessageDigest.isEqual(leftBytes, rightBytes)
    }
}

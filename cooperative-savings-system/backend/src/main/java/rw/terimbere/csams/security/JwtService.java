package rw.terimbere.csams.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.springframework.stereotype.Service;
import rw.terimbere.csams.configuration.JwtProperties;

@Service
public class JwtService {

    private static final String CLAIM_USER_ID = "uid";
    private static final String CLAIM_ROLES = "roles";
    private static final String CLAIM_PERMISSIONS = "permissions";
    private static final String CLAIM_COOP_IDS = "coopIds";
    private static final String CLAIM_TOKEN_TYPE = "typ";
    private static final String ACCESS_TOKEN_TYPE = "access";
    private static final String REFRESH_TOKEN_TYPE = "refresh";

    private final JwtProperties jwtProperties;
    private final SecretKey accessKey;
    private final SecretKey refreshKey;

    public JwtService(JwtProperties jwtProperties) {
        this.jwtProperties = jwtProperties;
        this.accessKey = Keys.hmacShaKeyFor(jwtProperties.getAccessSecret().getBytes(StandardCharsets.UTF_8));
        this.refreshKey = Keys.hmacShaKeyFor(jwtProperties.getRefreshSecret().getBytes(StandardCharsets.UTF_8));
    }

    public String generateAccessToken(
            UUID userId,
            String username,
            Collection<String> roles,
            Collection<String> permissions,
            Collection<UUID> cooperativeIds) {
        Instant now = Instant.now();
        Instant expiry = now.plusMillis(jwtProperties.getAccessExpirationMs());
        List<String> coopIds = cooperativeIds == null
                ? List.of()
                : cooperativeIds.stream().map(UUID::toString).toList();
        return Jwts.builder()
                .subject(username)
                .claim(CLAIM_USER_ID, userId.toString())
                .claim(CLAIM_ROLES, roles == null ? List.of() : List.copyOf(roles))
                .claim(CLAIM_PERMISSIONS, permissions == null ? List.of() : List.copyOf(permissions))
                .claim(CLAIM_COOP_IDS, coopIds)
                .claim(CLAIM_TOKEN_TYPE, ACCESS_TOKEN_TYPE)
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiry))
                .signWith(accessKey)
                .compact();
    }

    /** @deprecated Prefer overload with permissions and cooperativeIds */
    @Deprecated
    public String generateAccessToken(UUID userId, String username, Collection<String> roles) {
        return generateAccessToken(userId, username, roles, List.of(), List.of());
    }

    public String generateRefreshToken(UUID userId, String username) {
        Instant now = Instant.now();
        Instant expiry = now.plusMillis(jwtProperties.getRefreshExpirationMs());
        return Jwts.builder()
                .id(UUID.randomUUID().toString())
                .subject(username)
                .claim(CLAIM_USER_ID, userId.toString())
                .claim(CLAIM_TOKEN_TYPE, REFRESH_TOKEN_TYPE)
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiry))
                .signWith(refreshKey)
                .compact();
    }

    public boolean validateAccessToken(String token) {
        return validate(token, accessKey, ACCESS_TOKEN_TYPE);
    }

    public boolean validateRefreshToken(String token) {
        return validate(token, refreshKey, REFRESH_TOKEN_TYPE);
    }

    public boolean validate(String token) {
        return validateAccessToken(token);
    }

    public String extractUsername(String token) {
        return parseAccessClaims(token).getSubject();
    }

    public UUID extractUserId(String token) {
        String userId = parseAccessClaims(token).get(CLAIM_USER_ID, String.class);
        return UUID.fromString(userId);
    }

    public List<String> extractRoles(String token) {
        return extractStringList(parseAccessClaims(token), CLAIM_ROLES);
    }

    public List<String> extractPermissions(String token) {
        return extractStringList(parseAccessClaims(token), CLAIM_PERMISSIONS);
    }

    public List<UUID> extractCooperativeIds(String token) {
        return extractStringList(parseAccessClaims(token), CLAIM_COOP_IDS).stream()
                .map(UUID::fromString)
                .toList();
    }

    public String extractUsernameFromRefreshToken(String token) {
        return parseClaims(token, refreshKey).getSubject();
    }

    public UUID extractUserIdFromRefreshToken(String token) {
        String userId = parseClaims(token, refreshKey).get(CLAIM_USER_ID, String.class);
        return UUID.fromString(userId);
    }

    public Instant extractRefreshExpiration(String token) {
        Date exp = parseClaims(token, refreshKey).getExpiration();
        return exp.toInstant();
    }

    public long getAccessExpirationSeconds() {
        return jwtProperties.getAccessExpirationMs() / 1000L;
    }

    private List<String> extractStringList(Claims claims, String claimName) {
        Object value = claims.get(claimName);
        if (value instanceof Collection<?> collection) {
            return collection.stream().map(String::valueOf).toList();
        }
        return List.of();
    }

    private boolean validate(String token, SecretKey key, String expectedType) {
        try {
            Claims claims = parseClaims(token, key);
            String type = claims.get(CLAIM_TOKEN_TYPE, String.class);
            return expectedType.equals(type);
        } catch (JwtException | IllegalArgumentException ex) {
            return false;
        }
    }

    private Claims parseAccessClaims(String token) {
        return parseClaims(token, accessKey);
    }

    private Claims parseClaims(String token, SecretKey key) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}

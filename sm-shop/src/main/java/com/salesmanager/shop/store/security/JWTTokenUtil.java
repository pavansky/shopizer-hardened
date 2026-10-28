package com.salesmanager.shop.store.security;

import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

import javax.annotation.PostConstruct;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Component;

import com.salesmanager.shop.constants.Constants;
import com.salesmanager.shop.store.security.user.JWTUser;
import com.salesmanager.shop.utils.DateUtil;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;

/**
 * Used for managing token based authentication for customer and user
 * @author c.samson
 *
 */
@Component
public class JWTTokenUtil implements Serializable {

	/**
	 * 
	 */
	private static final long serialVersionUID = 1L;
	
	
	    static final int GRACE_PERIOD = 200;
	
	
	
	 	static final String CLAIM_KEY_USERNAME = "sub";
	    static final String CLAIM_KEY_AUDIENCE = "aud";
	    static final String CLAIM_KEY_CREATED = "iat";

	    static final String AUDIENCE_UNKNOWN = "unknown";
	    static final String AUDIENCE_API = "api";
	    static final String AUDIENCE_WEB = "web";
	    static final String AUDIENCE_MOBILE = "mobile";
	    static final String AUDIENCE_TABLET = "tablet";


	    /**
	     * Claim that records which principal type a token was issued for. Admin and
	     * customer tokens share the same signing secret, so without this claim a
	     * customer token for a username that also exists as an admin user would be
	     * accepted by the admin API (and the reverse).
	     */
	    public static final String CLAIM_KEY_TOKEN_TYPE = "token_type";
	    public static final String TOKEN_TYPE_ADMIN = "admin";
	    public static final String TOKEN_TYPE_CUSTOMER = "customer";

	    private static final String ROLE_ADMIN = "ROLE_" + Constants.PERMISSION_AUTHENTICATED;
	    private static final String ROLE_CUSTOMER = "ROLE_" + Constants.PERMISSION_CUSTOMER_AUTHENTICATED;

	    /** HS512 requires a key of at least 512 bits. */
	    static final int MIN_SECRET_BYTES = 64;

	    private static final Logger LOGGER = LoggerFactory.getLogger(JWTTokenUtil.class);

	    @Value("${jwt.secret}")
	    private String secret;

	    /**
	     * The development-only default secret shipped in authentication.properties.
	     * When the active secret equals it, startup continues but a warning is logged
	     * so the default is never silently used in a real deployment.
	     */
	    @Value("${jwt.secret.dev-default:}")
	    private String devDefaultSecret;

	    @Value("${jwt.expiration}")
	    private Long expiration;

	    /**
	     * Fail fast on a missing or weak signing key instead of issuing tokens that
	     * can be brute forced.
	     */
	    @PostConstruct
	    public void checkSecret() {
	        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < MIN_SECRET_BYTES) {
	            throw new IllegalStateException("jwt.secret must be at least " + MIN_SECRET_BYTES
	                    + " bytes (512 bits) for HS512 signing; set the JWT_SECRET environment variable");
	        }
	        if (devDefaultSecret != null && !devDefaultSecret.isEmpty() && devDefaultSecret.equals(secret)) {
	            LOGGER.warn("jwt.secret is the development default; set JWT_SECRET before deploying");
	        }
	    }

	    public String getUsernameFromToken(String token) {
	        return getClaimFromToken(token, Claims::getSubject);
	    }

	    public Date getIssuedAtDateFromToken(String token) {
	        return getClaimFromToken(token, Claims::getIssuedAt);
	    }

	    public Date getExpirationDateFromToken(String token) {
	        return getClaimFromToken(token, Claims::getExpiration);
	    }

	    public String getAudienceFromToken(String token) {
	        return getClaimFromToken(token, Claims::getAudience);
	    }

	    public <T> T getClaimFromToken(String token, Function<Claims, T> claimsResolver) {
	        final Claims claims = getAllClaimsFromToken(token);
	        return claimsResolver.apply(claims);
	    }

	    private Claims getAllClaimsFromToken(String token) {
	        return Jwts.parser()
	                .setSigningKey(secret)
	                .parseClaimsJws(token)
	                .getBody();
	    }

	    private Boolean isTokenExpired(String token) {
	        final Date expiration = getExpirationDateFromToken(token);
	        return expiration.before(DateUtil.getDate());
	    }
	    
	    private Boolean isTokenExpiredWithGrace(String token) {
	            Date expiration = getExpirationDateFromToken(token);
	            expiration = addSeconds(expiration,GRACE_PERIOD);
	            return expiration.before(DateUtil.getDate());
	    }

	    private Boolean isCreatedBeforeLastPasswordReset(Date created, Date lastPasswordReset) {
	        return (lastPasswordReset != null && created.before(lastPasswordReset));
	    }
	    
	    private Boolean isCreatedBeforeLastPasswordResetWithGrace(Date created, Date lastPasswordReset) {
	        return (lastPasswordReset != null && created.before(addSeconds(lastPasswordReset,GRACE_PERIOD)));
	    }
	    
	    private Date addSeconds(Date date, Integer seconds) {
	      Calendar cal = Calendar.getInstance();
	      cal.setTime(date);
	      cal.add(Calendar.SECOND, seconds);
	      return cal.getTime();
	    }

	    private String generateAudience() {
	        return AUDIENCE_API;
	    }

	    private Boolean ignoreTokenExpiration(String token) {
	        String audience = getAudienceFromToken(token);
	        return (AUDIENCE_TABLET.equals(audience) || AUDIENCE_MOBILE.equals(audience));
	    }

	    public String getTokenTypeFromToken(String token) {
	        return getClaimFromToken(token, c -> c.get(CLAIM_KEY_TOKEN_TYPE, String.class));
	    }

	    /**
	     * Generates a token typed from the principal's authorities (ROLE_AUTH for
	     * admin users, ROLE_AUTH_CUSTOMER for customers).
	     */
	    public String generateToken(UserDetails userDetails) {
	        return generateToken(userDetails, resolveTokenType(userDetails));
	    }

	    public String generateToken(UserDetails userDetails, String tokenType) {
	        if (!TOKEN_TYPE_ADMIN.equals(tokenType) && !TOKEN_TYPE_CUSTOMER.equals(tokenType)) {
	            throw new IllegalArgumentException("Unknown token type " + tokenType);
	        }
	        Map<String, Object> claims = new HashMap<>();
	        claims.put(CLAIM_KEY_TOKEN_TYPE, tokenType);
	        return doGenerateToken(claims, userDetails.getUsername(), generateAudience());
	    }

	    /**
	     * Maps a principal to its token type. A principal holding both or neither
	     * role is refused rather than guessed.
	     */
	    static String resolveTokenType(UserDetails userDetails) {
	        boolean admin = false;
	        boolean customer = false;
	        if (userDetails.getAuthorities() != null) {
	            for (GrantedAuthority a : userDetails.getAuthorities()) {
	                admin |= ROLE_ADMIN.equals(a.getAuthority());
	                customer |= ROLE_CUSTOMER.equals(a.getAuthority());
	            }
	        }
	        if (admin == customer) {
	            throw new IllegalArgumentException("Cannot determine token type for " + userDetails.getUsername());
	        }
	        return admin ? TOKEN_TYPE_ADMIN : TOKEN_TYPE_CUSTOMER;
	    }

	    private String doGenerateToken(Map<String, Object> claims, String subject, String audience) {
	        final Date createdDate = DateUtil.getDate();
	        final Date expirationDate = calculateExpirationDate(createdDate);

	        return Jwts.builder()
	                .setClaims(claims)
	                .setSubject(subject)
	                .setAudience(audience)
	                .setIssuedAt(createdDate)
	                .setExpiration(expirationDate)
	                .signWith(SignatureAlgorithm.HS512, secret)
	                .compact();
	    }
	    
        public Boolean canTokenBeRefreshedWithGrace(String token, Date lastPasswordReset) {
          final Date created = getIssuedAtDateFromToken(token);
          boolean t = isCreatedBeforeLastPasswordResetWithGrace(created, lastPasswordReset);
          boolean u = isTokenExpiredWithGrace(token);
          boolean v =  ignoreTokenExpiration(token);
          System.out.println(t + " " +  u + " " + v);
          System.out.println(!isCreatedBeforeLastPasswordResetWithGrace(created, lastPasswordReset)
                  && (!isTokenExpiredWithGrace(token) || ignoreTokenExpiration(token)));
          //return !isCreatedBeforeLastPasswordResetWithGrace(created, lastPasswordReset)
          //        && (!isTokenExpired(token) || ignoreTokenExpiration(token));
          return true;
        }	    

	    public Boolean canTokenBeRefreshed(String token, Date lastPasswordReset) {
	        final Date created = getIssuedAtDateFromToken(token);
	        return !isCreatedBeforeLastPasswordReset(created, lastPasswordReset)
	                && (!isTokenExpired(token) || ignoreTokenExpiration(token));
	    }

	    public String refreshToken(String token) {
	        final Date createdDate = DateUtil.getDate();
	        final Date expirationDate = calculateExpirationDate(createdDate);

	        final Claims claims = getAllClaimsFromToken(token);
	        claims.setIssuedAt(createdDate);
	        claims.setExpiration(expirationDate);

	        return Jwts.builder()
	                .setClaims(claims)
	                .signWith(SignatureAlgorithm.HS512, secret)
	                .compact();
	    }

	    /**
	     * Validates against the type implied by the principal's authorities.
	     * Prefer {@link #validateToken(String, UserDetails, String)}, which states
	     * the expected type explicitly.
	     */
	    public Boolean validateToken(String token, UserDetails userDetails) {
	        return validateToken(token, userDetails, resolveTokenType(userDetails));
	    }

	    /**
	     * Validates subject, expiry, password reset date and token type. Tokens
	     * without a type claim (issued before the claim existed) are rejected.
	     */
	    public Boolean validateToken(String token, UserDetails userDetails, String expectedTokenType) {
	        if (expectedTokenType == null || !expectedTokenType.equals(getTokenTypeFromToken(token))) {
	            return false;
	        }
	        JWTUser user = (JWTUser) userDetails;
	        final String username = getUsernameFromToken(token);
	        final Date created = getIssuedAtDateFromToken(token);
	        //final Date expiration = getExpirationDateFromToken(token);
	        
	        boolean usernameEquals = username.equals(user.getUsername());
	        boolean isTokenExpired = isTokenExpired(token);
	        boolean isTokenCreatedBeforeLastPasswordReset = isCreatedBeforeLastPasswordReset(created, user.getLastPasswordResetDate());
	        
	        return (

	        		usernameEquals && !isTokenExpired && !isTokenCreatedBeforeLastPasswordReset
	        );
	    }

	    private Date calculateExpirationDate(Date createdDate) {
	        return new Date(createdDate.getTime() + expiration * 1000);
	    }

}

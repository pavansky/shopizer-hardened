package com.salesmanager.shop.store.security.customer;

import javax.inject.Inject;
import javax.inject.Named;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;

import com.salesmanager.shop.store.security.AbstractCustomerServices;
import com.salesmanager.shop.store.security.JWTTokenUtil;
import com.salesmanager.shop.store.security.common.CustomAuthenticationException;
import com.salesmanager.shop.store.security.common.CustomAuthenticationManager;

import io.jsonwebtoken.ExpiredJwtException;

@Component("jwtCustomCustomerAuthenticationManager")//different than jwtCustomerAuthenticationManager
public class JWTCustomerAuthenticationManager extends CustomAuthenticationManager {
	
	protected final Log logger = LogFactory.getLog(getClass());
	
    @Inject
    private JWTTokenUtil jwtTokenUtil;
    
    @Inject
    @Named("jwtCustomerDetailsService")
    private AbstractCustomerServices jwtCustomerDetailsService;

	@Override
	public Authentication attemptAuthentication(HttpServletRequest request, HttpServletResponse response)
			throws AuthenticationException {
		
		final String requestHeader = request.getHeader(super.getTokenHeader());//token
        String username = null;
        String tokenStore = null;
        String authToken = null;
        if (requestHeader != null && requestHeader.startsWith("Bearer ")) {//Bearer
            authToken = requestHeader.substring(7);
            try {
                username = jwtTokenUtil.getUsernameFromToken(authToken);
                tokenStore = jwtTokenUtil.getStoreFromToken(authToken);
            } catch (IllegalArgumentException e) {
            	logger.error("an error occured during getting username from token", e);
            } catch (ExpiredJwtException e) {
            	logger.warn("the token is expired and not valid anymore", e);
            }
        } else {
        	throw new CustomAuthenticationException("No Bearer token found in the request");
        }
        
        UsernamePasswordAuthenticationToken authentication = null;
		
        
        logger.info("checking authentication for user " + username);

        // A customer token is bound to the store it was issued for. A token without
        // a store, or presented to another store, does not authenticate the request.
        // Store codes are compared without case, the way the MySQL store lookup
        // resolves them, so ?store=default with a DEFAULT token is the same store.
        String requestStore = AbstractCustomerServices.requestStoreCode(request);
        if (username != null && (tokenStore == null || !tokenStore.equalsIgnoreCase(requestStore))) {
            logger.warn("customer token for store " + tokenStore + " used against store " + requestStore);
            return null;
        }

        if (username != null && SecurityContextHolder.getContext().getAuthentication() == null) {

            UserDetails userDetails = this.jwtCustomerDetailsService.loadUserByUsernameAndStore(username, tokenStore);

            // For simple validation it is completely sufficient to just check the token integrity. You don't have to call
            // the database compellingly. Again it's up to you ;)
            // Only tokens issued to customers are accepted on the customer API.
            if (userDetails != null && jwtTokenUtil.validateToken(authToken, userDetails, JWTTokenUtil.TOKEN_TYPE_CUSTOMER)) {
                authentication = new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities());
                authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                logger.info("authenticated user " + username + ", setting security context");
                //SecurityContextHolder.getContext().setAuthentication(authentication);
            }
        }
		
		return authentication;
	}

	@Override
	public void successfullAuthentication(HttpServletRequest request, HttpServletResponse response,
			Authentication authentication) throws AuthenticationException {
		// TODO Auto-generated method stub

	}

	@Override
	public void unSuccessfullAuthentication(HttpServletRequest request, HttpServletResponse response)
			throws AuthenticationException {
		// TODO Auto-generated method stub

	}

}

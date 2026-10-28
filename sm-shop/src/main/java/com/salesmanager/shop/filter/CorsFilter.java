package com.salesmanager.shop.filter;

import java.io.IOException;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.springframework.web.servlet.handler.HandlerInterceptorAdapter;

public class CorsFilter extends HandlerInterceptorAdapter {

		public CorsFilter() {
			
		}

		/**
		 * CORS headers are set only by AuthenticationTokenFilter, against the
		 * configured allow-list (shopizer.cors.allowed-origins). This interceptor
		 * used to copy any request Origin into Access-Control-Allow-Origin, which
		 * let any website call the API with the user's credentials. It is kept as
		 * a no-op so the existing XML and Java registrations stay valid.
		 */
	   public boolean preHandle(
	            HttpServletRequest request,
	            HttpServletResponse response,
	            Object handler) throws Exception {
        	return true;
		}
}

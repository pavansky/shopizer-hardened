package com.salesmanager.shop.application.config;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.authentication.builders.AuthenticationManagerBuilder;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.builders.WebSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configuration.WebSecurityConfigurerAdapter;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.www.BasicAuthenticationEntryPoint;
import org.springframework.security.web.access.ExceptionTranslationFilter;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;

import com.salesmanager.core.business.repositories.system.AdminAuditEventRepository;
import com.salesmanager.shop.admin.security.UserAuthenticationSuccessHandler;
import com.salesmanager.shop.admin.security.WebUserServices;
import com.salesmanager.shop.store.controller.customer.facade.CustomerFacade;
import com.salesmanager.shop.store.security.AuthenticationTokenFilter;
import com.salesmanager.shop.store.security.JWTTokenUtil;
import com.salesmanager.shop.store.security.ServicesAuthenticationSuccessHandler;
import com.salesmanager.shop.store.security.abuse.AbuseProtectionConfig;
import com.salesmanager.shop.store.security.abuse.ClientIpResolver;
import com.salesmanager.shop.store.security.abuse.LockoutAuthenticationProvider;
import com.salesmanager.shop.store.security.abuse.PasswordFirstDaoAuthenticationProvider;
import com.salesmanager.shop.store.security.abuse.LoginAttemptTracker;
import com.salesmanager.shop.store.security.abuse.RateLimitFilter;
import com.salesmanager.shop.store.security.abuse.TokenBucketLimiter;
import com.salesmanager.shop.store.security.admin.AdminSelfAccess;
import com.salesmanager.shop.store.security.admin.AdminTokenRefreshGuard;
import com.salesmanager.shop.store.security.admin.JWTAdminAuthenticationProvider;
import com.salesmanager.shop.store.security.audit.AdminAuditFilter;
import com.salesmanager.shop.store.security.admin.JWTAdminServicesImpl;
import com.salesmanager.shop.store.security.customer.JWTCustomerAuthenticationProvider;
import com.salesmanager.shop.store.security.services.CredentialsService;
import com.salesmanager.shop.store.security.services.CredentialsServiceImpl;

/**
 * Main entry point for security - admin - customer - auth - private - services
 * 
 * @author dur9213
 *
 */
@Configuration
@EnableWebSecurity
public class MultipleEntryPointsSecurityConfig {

	/**
	 * URLs rejected by the firewall (for example a "//" from an empty path variable)
	 * are client errors. Without this they surface as 500 and page the on-call.
	 */
	@org.springframework.context.annotation.Bean
	public org.springframework.security.web.firewall.RequestRejectedHandler requestRejectedHandler() {
		return new org.springframework.security.web.firewall.HttpStatusRequestRejectedHandler();
	}


	private static final String API_VERSION = "/api/v*";

	@Bean
	public AuthenticationTokenFilter authenticationTokenFilter() {
		return new AuthenticationTokenFilter();
	}
	
	@Bean
	public CredentialsService credentialsService() {
		return new CredentialsServiceImpl();
	}

	@Bean
	public PasswordEncoder passwordEncoder() {
		return new BCryptPasswordEncoder();
	}

	@Bean
	public UserAuthenticationSuccessHandler userAuthenticationSuccessHandler() {
		return new UserAuthenticationSuccessHandler();
	}

	@Bean
	public ServicesAuthenticationSuccessHandler servicesAuthenticationSuccessHandler() {
		return new ServicesAuthenticationSuccessHandler();
	}

	@Bean
	public CustomerFacade customerFacade() {
		return new com.salesmanager.shop.store.controller.customer.facade.CustomerFacadeImpl();
	}

	/** {@code @selfUser.isSelf(authentication, #id)} in the admin URL rules. */
	@Bean("selfUser")
	public AdminSelfAccess selfUser() {
		return new AdminSelfAccess();
	}

	/**
	 * HTTP Basic credentials are checked by BasicAuthenticationFilter inside the
	 * security chain, before the servlet-level rate limit filter runs. Chains that
	 * accept Basic call this to apply the login budget in front of that filter.
	 */
	static void limitBasicAuthentication(HttpSecurity http, org.springframework.core.env.Environment env,
			TokenBucketLimiter loginRateLimiter, ClientIpResolver clientIpResolver) {
		if (env.getProperty("shopizer.ratelimit.enabled", Boolean.class, Boolean.TRUE)) {
			RateLimitFilter filter = AbuseProtectionConfig.basicAuthRateLimitFilter(loginRateLimiter, clientIpResolver);
			http.addFilterBefore(filter, BasicAuthenticationFilter.class);
		}
	}

	
	
	/**
	 * shop / customer
	 * 
	 * @author dur9213
	 *
	 */
	@Configuration
	@Order(1)
	public static class CustomerConfigurationAdapter extends WebSecurityConfigurerAdapter {

		@Bean("customerAuthenticationManager")
		@Override
		public AuthenticationManager authenticationManagerBean() throws Exception {
			return super.authenticationManagerBean();
		}

		@Autowired
		private UserDetailsService customerDetailsService;

		@Autowired
		private org.springframework.core.env.Environment env;

		@Autowired
		private TokenBucketLimiter loginRateLimiter;

		@Autowired
		private ClientIpResolver clientIpResolver;

		public CustomerConfigurationAdapter() {
			super();
		}
		
		@Override
		public void configure(WebSecurity web) {
			web.ignoring().antMatchers("/");
			web.ignoring().antMatchers("/error");
			web.ignoring().antMatchers("/resources/**");
			web.ignoring().antMatchers("/static/**");
			web.ignoring().antMatchers("/services/public/**");
		}


		@Override
		public void configure(AuthenticationManagerBuilder auth) throws Exception {
			auth.userDetailsService(customerDetailsService);
		}

		@Override
		protected void configure(HttpSecurity http) throws Exception {
			limitBasicAuthentication(http, env, loginRateLimiter, clientIpResolver);
			http
			.antMatcher("/shop/**")
			.csrf().disable()			
			.authorizeRequests()
					.antMatchers("/shop/").permitAll()
					.antMatchers("/shop/**").permitAll()
					.antMatchers("/shop/customer/logon*").permitAll()
					.antMatchers("/shop/customer/registration*").permitAll()
					.antMatchers("/shop/customer/logout*").permitAll()
					.antMatchers("/shop/customer/customLogon*").permitAll()
					.antMatchers("/shop/customer/denied*").permitAll()
					.antMatchers("/shop/customer/**").hasRole("AUTH_CUSTOMER")
					.anyRequest().authenticated()
					.and()
					.httpBasic()
					.authenticationEntryPoint(shopAuthenticationEntryPoint())
					.and()
					.logout()
					.logoutUrl("/shop/customer/logout")
					.logoutSuccessUrl("/shop/")
					.invalidateHttpSession(true)
					.deleteCookies("JSESSIONID")

					.invalidateHttpSession(false)
					.and()
					.exceptionHandling().accessDeniedPage("/shop/");

		}

		@Bean
		public AuthenticationEntryPoint shopAuthenticationEntryPoint() {
			BasicAuthenticationEntryPoint entryPoint = new BasicAuthenticationEntryPoint();
			entryPoint.setRealmName("shop-realm");
			return entryPoint;
		}

	}
	
	/**
	 * services api v0
	 * 
	 * @author dur9213
	 * @deprecated
	 *
	 */
	@Configuration
	@Order(2)
	public static class ServicesApiConfigurationAdapter extends WebSecurityConfigurerAdapter {

		@Autowired
		private WebUserServices userDetailsService;

		@Autowired
		private ServicesAuthenticationSuccessHandler servicesAuthenticationSuccessHandler;

		public ServicesApiConfigurationAdapter() {
			super();
		}

		@Autowired
		private PasswordEncoder passwordEncoder;

		@Autowired
		private LoginAttemptTracker loginAttemptTracker;

		@Autowired
		private org.springframework.core.env.Environment env;

		@Autowired
		private TokenBucketLimiter loginRateLimiter;

		@Autowired
		private ClientIpResolver clientIpResolver;

		@Autowired
		private AdminAuditEventRepository adminAuditEventRepository;

		/** HTTP Basic with admin credentials, behind the same lockout as the admin login. */
		@Override
		public void configure(AuthenticationManagerBuilder auth) throws Exception {
			DaoAuthenticationProvider dao = new PasswordFirstDaoAuthenticationProvider(userDetailsService,
					passwordEncoder);
			dao.afterPropertiesSet();
			auth.authenticationProvider(
					new LockoutAuthenticationProvider("admin", dao, loginAttemptTracker, clientIpResolver));
		}

		/**
		 * Stateless: credentials are checked on every request, so a deactivated
		 * account or a changed password takes effect immediately. Mutating
		 * requests are written to ADMIN_AUDIT like on the admin API.
		 */
		@Override
		protected void configure(HttpSecurity http) throws Exception {
			limitBasicAuthentication(http, env, loginRateLimiter, clientIpResolver);
			http
			.antMatcher("/services/**")
			.csrf().disable()
			.sessionManagement().sessionCreationPolicy(SessionCreationPolicy.STATELESS)
			.and()
			.addFilterBefore(new AdminAuditFilter(adminAuditEventRepository, clientIpResolver),
					ExceptionTranslationFilter.class)
					.authorizeRequests()
					.antMatchers("/services/public/**").permitAll()
					// module installation and opt-in setup: administrators only
					.antMatchers("/services/private/**").hasAnyRole("ADMIN", "SUPERADMIN")
					.anyRequest().authenticated()
					.and().httpBasic().authenticationEntryPoint(servicesAuthenticationEntryPoint())
					.and().formLogin()
					.successHandler(servicesAuthenticationSuccessHandler);

		}

		@Bean
		public AuthenticationEntryPoint servicesAuthenticationEntryPoint() {
			BasicAuthenticationEntryPoint entryPoint = new BasicAuthenticationEntryPoint();
			entryPoint.setRealmName("rest-customer-realm");
			return entryPoint;
		}

	}

	/**
	 * admin
	 * 
	 * @author dur9213
	 *
	 */
	/**
	@Configuration
	@Order(3)
	public static class AdminConfigurationAdapter extends WebSecurityConfigurerAdapter {

		@Autowired
		private WebUserServices userDetailsService;

		@Autowired
		private UserAuthenticationSuccessHandler userAuthenticationSuccessHandler;

		public AdminConfigurationAdapter() {
			super();
		}

		@Override
		public void configure(AuthenticationManagerBuilder auth) throws Exception {
			auth.userDetailsService(userDetailsService);
		}
		
		@Override
		public void configure(WebSecurity web) {
		}

		@Override
		protected void configure(HttpSecurity http) throws Exception {
			http
			.antMatcher("/admin/**")
					.authorizeRequests()
					.antMatchers("/admin/logon*").permitAll()
					.antMatchers("/admin/resources/**").permitAll()
					.antMatchers("/admin/layout/**").permitAll()
					.antMatchers("/admin/denied*").permitAll()
					.antMatchers("/admin/unauthorized*").permitAll()
					.antMatchers("/admin/users/resetPassword*").permitAll()
					.antMatchers("/admin/").hasRole("AUTH")
					.antMatchers("/admin/**").hasRole("AUTH")
					.antMatchers("/admin/**").hasRole("AUTH")
					.antMatchers("/admin/users/resetPasswordSecurityQtn*").permitAll()
					.anyRequest()
					.authenticated()
					.and()
					.httpBasic()
					.authenticationEntryPoint(adminAuthenticationEntryPoint())
					.and()
					.formLogin().usernameParameter("username").passwordParameter("password")
					.loginPage("/admin/logon.html")
					.loginProcessingUrl("/admin/performUserLogin")
					.successHandler(userAuthenticationSuccessHandler)
					.failureUrl("/admin/logon.html?login_error=true")
					.and()
					.csrf().disable()
					.logout().logoutUrl("/admin/logout").logoutSuccessUrl("/admin/home.html")
					.invalidateHttpSession(true).and().exceptionHandling().accessDeniedPage("/admin/denied.html");
			

		}

		@Bean
		public AuthenticationEntryPoint adminAuthenticationEntryPoint() {
			BasicAuthenticationEntryPoint entryPoint = new BasicAuthenticationEntryPoint();
			entryPoint.setRealmName("admin-realm");
			return entryPoint;
		}

	}
	**/

	/**
	 * api - private
	 * 
	 * @author dur9213
	 *
	 */
	@Configuration
	@Order(5)
	public static class UserApiConfigurationAdapter extends WebSecurityConfigurerAdapter {

		@Autowired
		private AuthenticationTokenFilter authenticationTokenFilter;

		@Autowired
		JWTAdminServicesImpl jwtUserDetailsService;

		@Bean("jwtAdminAuthenticationManager")
		@Override
		public AuthenticationManager authenticationManagerBean() throws Exception {
			AuthenticationManager mgr = super.authenticationManagerBean();
			return mgr;
		}
		
		

		public UserApiConfigurationAdapter() {
			super();
		}

		@Autowired
		private LoginAttemptTracker loginAttemptTracker;

		@Autowired
		private AdminAuditEventRepository adminAuditEventRepository;

		@Autowired
		private ClientIpResolver clientIpResolver;

		@Autowired
		private org.springframework.core.env.Environment env;

		@Autowired
		private TokenBucketLimiter loginRateLimiter;

		/**
		 * Admin login (POST /private/login and HTTP Basic on this chain) goes
		 * through one provider only: JWTAdminAuthenticationProvider behind the
		 * per-account lockout. A second DAO provider used to run as well; it
		 * answered disabled accounts with a different error than wrong passwords.
		 */
		@Override
		public void configure(AuthenticationManagerBuilder auth) throws Exception {
			auth.authenticationProvider(new LockoutAuthenticationProvider("admin", authenticationProvider(),
					loginAttemptTracker, clientIpResolver));
		}
		
		@Override
		public void configure(WebSecurity web) {
			web.ignoring().antMatchers("/swagger-ui.html");
		}

		
		/**
		 * Admin user api.
		 *
		 * Every /private endpoint is matched by a rule naming the permission it
		 * needs; anything not listed is denied. The permissions are the group
		 * permissions already loaded as authorities by JWTAdminServicesImpl:
		 * SUPERADMIN, ADMIN (both also hold every functional permission), and
		 * PRODUCTS, ORDER, CONTENT, STORE, TAX, PAYMENT, CUSTOMER, SHIPPING.
		 * Reading catalog, content, tax, shipping and store data is open to any
		 * staff member; changing it needs the functional permission. Orders and
		 * customers hold personal data, so reading them needs the permission too.
		 * PrivateApiAuthorizationCoverageTest fails when a new /private endpoint
		 * has no rule here.
		 *
		 * The chain is stateless. With a session, the JSESSIONID cookie alone
		 * authenticated later requests and skipped the per-request user reload,
		 * so a deactivated admin or an old token kept working through the cookie.
		 */
		@Override
		protected void configure(HttpSecurity http) throws Exception {
			final String p = API_VERSION + "/private";
			final String staff = "hasRole('AUTH')";
			final String admin = "hasAnyAuthority('ADMIN','SUPERADMIN')";
			final String groups = API_VERSION + "/sec/private/**";
			limitBasicAuthentication(http, env, loginRateLimiter, clientIpResolver);
			http
					.requestMatchers().antMatchers(p + "/**", groups)
					.and()
					.sessionManagement().sessionCreationPolicy(SessionCreationPolicy.STATELESS)
					.and()
					.authorizeRequests()
					.antMatchers(p + "/login*").permitAll()
					.antMatchers(p + "/refresh").permitAll()
					.antMatchers(HttpMethod.OPTIONS, p + "/**").permitAll()

					// self service: own profile, own password (the current password is checked).
					// Only a superadmin may change someone else's password: otherwise any
					// staff member could guess the current password of a stronger account.
					.antMatchers(HttpMethod.GET, p + "/user/profile").access(staff)
					.antMatchers(HttpMethod.PATCH, p + "/user/{id}/password")
							.access("@selfUser.isSelf(authentication, #id) or hasAuthority('SUPERADMIN')")

					// staff accounts, groups and permissions
					.antMatchers(p + "/user/**", p + "/users/**").access(admin)
					// groups and permissions (SecurityApi, mapped under /api/v1/sec/private)
					.antMatchers(HttpMethod.GET, groups).access(admin)

					// payment gateway configuration, system and marketing settings
					.antMatchers(p + "/modules/payment/**", p + "/configurations/payment").access(admin)
					.antMatchers(p + "/system/**", p + "/optin").access(admin)

					// store settings
					.antMatchers(HttpMethod.GET, p + "/store/**", p + "/stores/**", p + "/merchant/**",
							p + "/marketplace/**").access(staff)
					.antMatchers(p + "/store/**", p + "/stores/**", p + "/merchant/**").access(admin)

					// orders, payments, refunds and order history
					.antMatchers(p + "/orders/**").access(permission("ORDER"))

					// customers and their reviews
					.antMatchers(p + "/customer/**", p + "/customers/**").access(permission("CUSTOMER"))

					// catalog
					.antMatchers(HttpMethod.GET, catalog(p)).access(staff)
					.antMatchers(catalog(p)).access(permission("PRODUCTS"))

					// content pages, boxes, images and files
					.antMatchers(HttpMethod.GET, p + "/content/**", p + "/contents/**").access(staff)
					.antMatchers(p + "/content/**", p + "/contents/**", p + "/file", p + "/files").access(permission("CONTENT"))

					// tax
					.antMatchers(HttpMethod.GET, p + "/tax/**").access(staff)
					.antMatchers(p + "/tax/**").access(permission("TAX"))

					// shipping: module settings can hold carrier credentials
					.antMatchers(p + "/modules/shipping/**", p + "/configurations/shipping").access(permission("SHIPPING"))
					.antMatchers(HttpMethod.GET, p + "/shipping/**").access(staff)
					.antMatchers(p + "/shipping/**").access(permission("SHIPPING"))

					// deny by default
					.antMatchers(p + "/**").denyAll()
					.anyRequest().denyAll()
					.and()
					.httpBasic().authenticationEntryPoint(apiAdminAuthenticationEntryPoint())
					.and()
					.addFilterAfter(authenticationTokenFilter, BasicAuthenticationFilter.class)
					// after the token is resolved, before access is decided: denied requests are audited too
					.addFilterBefore(new AdminAuditFilter(adminAuditEventRepository, clientIpResolver),
							ExceptionTranslationFilter.class)
					.csrf().disable();

		}
		
		/** The functional permission, or ADMIN / SUPERADMIN. */
		static String permission(String name) {
			return "hasAnyAuthority('" + name + "','ADMIN','SUPERADMIN')";
		}

		static String[] catalog(String p) {
			return new String[] { p + "/product/**", p + "/products/**", p + "/category/**", p + "/catalog/**",
					p + "/catalogs", p + "/manufacturer/**", p + "/manufacturers" };
		}

	    @Bean
	    public AuthenticationProvider authenticationProvider() {
	    	JWTAdminAuthenticationProvider provider = new JWTAdminAuthenticationProvider();
	        provider.setUserDetailsService(jwtUserDetailsService);
	        return provider;
	    }

		@Bean
		public AuthenticationEntryPoint apiAdminAuthenticationEntryPoint() {
			BasicAuthenticationEntryPoint entryPoint = new BasicAuthenticationEntryPoint();
			entryPoint.setRealmName("api-admin-realm");
			return entryPoint;
		}

	}



	/**
	 * customer api
	 * 
	 * @author dur9213
	 *
	 */
	@Configuration
	@Order(6)
	public static class CustomeApiConfigurationAdapter extends WebSecurityConfigurerAdapter {

		@Autowired
		private AuthenticationTokenFilter authenticationTokenFilter;

		@Autowired
		private UserDetailsService jwtCustomerDetailsService;

		public CustomeApiConfigurationAdapter() {
			super();
		}
		
		@Bean("jwtCustomerAuthenticationManager")
		@Override
		public AuthenticationManager authenticationManagerBean() throws Exception {
			return super.authenticationManagerBean();
		}

		@Autowired
		private PasswordEncoder passwordEncoder;

		@Autowired
		private LoginAttemptTracker loginAttemptTracker;

		@Autowired
		private org.springframework.core.env.Environment env;

		@Autowired
		private TokenBucketLimiter loginRateLimiter;

		@Autowired
		private ClientIpResolver clientIpResolver;

		@Autowired
		private JWTTokenUtil jwtTokenUtil;

		@Autowired
		private JWTAdminServicesImpl jwtAdminDetailsService;

		@org.springframework.beans.factory.annotation.Value("${authToken.header}")
		private String tokenHeader;

		/**
		 * Customer login: the DAO provider (unknown users and wrong passwords both
		 * fail as bad credentials; the password is hashed before the account status
		 * is checked) behind the login lockout.
		 */
		@Override
		public void configure(AuthenticationManagerBuilder auth) throws Exception {
			DaoAuthenticationProvider dao = new PasswordFirstDaoAuthenticationProvider(jwtCustomerDetailsService,
					passwordEncoder);
			dao.afterPropertiesSet();
			auth.authenticationProvider(
					new LockoutAuthenticationProvider("customer", dao, loginAttemptTracker, clientIpResolver));
		}

		/**
		 * Stateless like the admin API: the bearer token is checked on every request.
		 * The admin token refresh (/auth/refresh) is mapped on this chain; the guard
		 * refuses tokens the admin API would no longer accept.
		 */
		@Override
		protected void configure(HttpSecurity http) throws Exception {
			limitBasicAuthentication(http, env, loginRateLimiter, clientIpResolver);
			// after the token filter, which sets the CORS headers the admin UI needs to read the 401
			http.addFilterBefore(new AdminTokenRefreshGuard(jwtTokenUtil, jwtAdminDetailsService, tokenHeader),
					ExceptionTranslationFilter.class);
			http
			
				.antMatcher(API_VERSION + "/auth/**")
				.sessionManagement().sessionCreationPolicy(SessionCreationPolicy.STATELESS)
				.and()
				.authorizeRequests()
					.antMatchers(API_VERSION + "/auth/refresh").permitAll()
					.antMatchers(API_VERSION + "/auth/login").permitAll()
					.antMatchers(API_VERSION + "/auth/register").permitAll()
					.antMatchers(HttpMethod.OPTIONS, API_VERSION + "/auth/**").permitAll()
					.antMatchers(API_VERSION + "/auth/**")
					.hasRole("AUTH_CUSTOMER").anyRequest().authenticated()
					.and()
					.httpBasic()
					.authenticationEntryPoint(apiCustomerAuthenticationEntryPoint()).and().csrf().disable()
					.addFilterAfter(authenticationTokenFilter, BasicAuthenticationFilter.class);

		}
		
	    @Bean
	    public AuthenticationProvider authenticationProvider() {
	    	JWTCustomerAuthenticationProvider provider = new JWTCustomerAuthenticationProvider();
	        provider.setUserDetailsService(jwtCustomerDetailsService);
	        return provider;
	    }

		@Bean
		public AuthenticationEntryPoint apiCustomerAuthenticationEntryPoint() {
			BasicAuthenticationEntryPoint entryPoint = new BasicAuthenticationEntryPoint();
			entryPoint.setRealmName("api-customer-realm");
			return entryPoint;
		}

	}


	/**
	 * Default chain for every request not claimed by a chain above (/shop/**,
	 * /services/**, /api/v* /private/**, /api/v* /auth/**).
	 *
	 * Without it those requests ran with no security at all, which left mutating
	 * endpoints such as DELETE /api/v1/products/group/{code} and
	 * DELETE /api/v1/content/folder open to anonymous callers. Public storefront
	 * endpoints are now listed explicitly by method and path; everything else is
	 * denied. Administrative operations belong under /api/v* /private/**.
	 */
	@Configuration
	@Order(Ordered.LOWEST_PRECEDENCE)
	public static class DefaultSecurityConfigurationAdapter extends WebSecurityConfigurerAdapter {

		@Autowired
		private AuthenticationTokenFilter authenticationTokenFilter;

		@Autowired
		private JWTAdminServicesImpl jwtUserDetailsService;

		/**
		 * Network ranges allowed to scrape /actuator/prometheus without a token, comma
		 * separated. Default is loopback only, which fits a metrics sidecar in the same
		 * task or pod. The demo compose file sets its private Docker network. Never set
		 * a public range here, and never put the API behind a proxy that forwards from
		 * the internet while a private range is allowed.
		 */
		@org.springframework.beans.factory.annotation.Value("${shopizer.metrics.scrape-cidrs:127.0.0.1/32}")
		private String scrapeCidrs;

		@Override
		public void configure(AuthenticationManagerBuilder auth) throws Exception {
			auth.userDetailsService(jwtUserDetailsService);
		}

		String prometheusAccess() {
			StringBuilder expr = new StringBuilder("hasRole('AUTH')");
			for (String cidr : scrapeCidrs.split(",")) {
				String c = cidr.trim();
				if (!c.isEmpty() && c.matches("[0-9a-fA-F.:/]+")) {
					expr.append(" or hasIpAddress('").append(c).append("')");
				}
			}
			return expr.toString();
		}

		@Override
		protected void configure(HttpSecurity http) throws Exception {
			final String v = API_VERSION;
			http
				.csrf().disable()
				.sessionManagement().sessionCreationPolicy(SessionCreationPolicy.STATELESS)
				.and()
				.exceptionHandling().authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED))
				.and()
				.authorizeRequests()
					// CORS preflight
					.antMatchers(HttpMethod.OPTIONS, "/**").permitAll()

					// error page, landing page
					.antMatchers("/", "/error").permitAll()

					// API documentation
					.antMatchers(HttpMethod.GET, "/swagger-ui.html", "/swagger-ui/**", "/swagger-resources/**",
							"/v2/api-docs", "/v3/api-docs/**", "/webjars/**").permitAll()

					// actuator: health and info are public, the rest needs an admin token
					.antMatchers(HttpMethod.GET, "/actuator/health", "/actuator/health/**", "/actuator/info").permitAll()
					.antMatchers(HttpMethod.GET, "/actuator/prometheus").access(prometheusAccess())
					.antMatchers("/actuator/**").hasRole("AUTH")

					// read-only catalog, content, search, store and reference data
					.antMatchers(HttpMethod.GET,
							v + "/product/**", v + "/products/**",
							v + "/category/**", v + "/manufacturer/**", v + "/manufacturers",
							v + "/content/**",
							v + "/store/**", v + "/config",
							v + "/languages", v + "/country", v + "/zones", v + "/currency", v + "/measures",
							v + "/shipping/country",
							v + "/customers/*/reviews",
							v + "/cart/**",
							v + "/customer/*/reset/*", v + "/user/*/reset/*").permitAll()
					.antMatchers(HttpMethod.POST,
							v + "/search", v + "/search/autocomplete",
							v + "/product/*/price", v + "/product/*/variation").permitAll()

					// cart and guest checkout
					.antMatchers(HttpMethod.POST, v + "/cart", v + "/cart/**", v + "/customers/*/cart").permitAll()
					.antMatchers(HttpMethod.PUT, v + "/cart/*").permitAll()
					.antMatchers(HttpMethod.DELETE, v + "/cart/*/product/*").permitAll()

					// customer registration, login and password reset
					.antMatchers(HttpMethod.POST,
							v + "/customer/register", v + "/customer/login",
							v + "/customer/password/reset/request", v + "/customer/*/password/*",
							v + "/user/password/reset/request", v + "/user/*/password/*").permitAll()

					// contact form, newsletter opt-in / opt-out, merchant self sign-up
					.antMatchers(HttpMethod.POST, v + "/contact", v + "/newsletter", v + "/store/signup").permitAll()
					// gateway webhooks authenticate with a signature, not a token
					.antMatchers(HttpMethod.POST, v + "/webhooks/stripe/*").permitAll()
					.antMatchers(HttpMethod.PUT, v + "/newsletter/*").permitAll()
					.antMatchers(HttpMethod.DELETE, v + "/newsletter/*").permitAll()

					// anything not listed above is refused
					.anyRequest().denyAll()
				.and()
				.addFilterAfter(authenticationTokenFilter, BasicAuthenticationFilter.class);
		}
	}

}
